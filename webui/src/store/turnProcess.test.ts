import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { MirrorFrame } from '../api/types'
import type { ConversationState } from './conversations'
import { useConversations } from './conversations'
import {
  deriveTurnProcessView, isSubagentDelegationTool, openTurnsOf, useTurnProcess,
} from './turnProcess'
import { useTranscriptView } from './transcriptView'

vi.mock('../api/client', () => ({ fetchSnapshot: vi.fn() }))

const SESSION = 'sess-1'
const EMPTY_SET: ReadonlySet<number> = new Set()

let nextFrameId = 0

/**
 * Drive the real reduction path: these are the mirror frames the gateway
 * publishes, not hand-assembled message rows. The fold used to be tested only
 * against hand-assembled multi-row transcripts, which the live frame path
 * never produced — so every rule passed while the feature never folded a
 * single live turn.
 */
function apply(event: MirrorFrame['event'], data: Record<string, unknown>): void {
  nextFrameId += 1
  useConversations.getState().applyFrame(
    { event, id: nextFrameId, data: { session_id: SESSION, ...data } } as MirrorFrame,
  )
}

function conversation(): ConversationState {
  return useConversations.getState().conversations[SESSION]
}

function view(expanded: ReadonlySet<number> = EMPTY_SET) {
  return deriveTurnProcessView(conversation(), expanded)
}

/** One tool call that starts and completes inside step {@code messageId}. */
function toolStep(messageId: string, name: string, useId: string): void {
  apply('tool.started', { name, tool_use_id: useId, input: {}, message_id: messageId })
  apply('tool.completed', {
    status: 'completed',
    tool_use_id: useId,
    result: { type: 'tool_result', data: 'ok' },
  })
}

beforeEach(() => {
  nextFrameId = 0
  useConversations.setState({ conversations: {} })
  useTurnProcess.setState({ openTurns: {} })
  useTranscriptView.getState().setMode('compact')
})

describe('turn-process fold over the live frame path', () => {
  it('folds a thinking + tool turn behind its final answer', () => {
    apply('turn.started', { display_text: '查一下', permission_mode: 'default', origin: 'web' })
    apply('output.thinking', { content: '先看目录', message_id: 'msg-1' })
    toolStep('msg-1', 'Bash', 'tu-1')
    toolStep('msg-1', 'Read', 'tu-2')
    apply('output.text', { content: '看完了，结论是……', message_id: 'msg-2' })
    apply('turn.completed', { done: true, elapsed_ms: 1200, user_cancel: false, turn: 1 })

    const rows = conversation().messages
    // The step split is the whole point: two assistant rows, not one.
    expect(rows.map((row) => row.kind)).toEqual(['user', 'assistant', 'assistant'])

    const folded = view()
    expect(folded.roles[rows[1].id]).toBe('member')
    expect(folded.roles[rows[2].id]).toBe('answer')
    expect(folded.controlTurns[rows[0].id]).toBe(1)
    expect(folded.counts[rows[0].id]).toEqual({
      toolCallCount: 2, messageCount: 0, subagentCount: 0,
    })
    expect(folded.memberTurns[rows[1].id]).toBe(1)
  })

  it('never folds while the turn is still running', () => {
    apply('turn.started', { display_text: '跑吧', permission_mode: 'default', origin: 'web' })
    toolStep('msg-1', 'Bash', 'tu-1')
    apply('output.text', { content: '中间结论', message_id: 'msg-2' })

    expect(conversation().turnRunning).toBe(true)
    expect(view().roles).toEqual({})
  })

  it('keeps every row visible when the last step is reasoning only', () => {
    // Upstream reads the LATEST step alone: an earlier reply-bearing step is
    // process, never a fallback answer.
    apply('turn.started', { display_text: 'hi', permission_mode: 'default', origin: 'web' })
    toolStep('msg-1', 'Bash', 'tu-1')
    apply('output.text', { content: '中途汇报', message_id: 'msg-2' })
    apply('output.thinking', { content: '还得再想', message_id: 'msg-3' })
    apply('turn.completed', { done: true, elapsed_ms: 900, user_cancel: false, turn: 1 })

    expect(view().roles).toEqual({})
    expect(view().controlTurns).toEqual({})
  })

  it('keeps every row visible when the last step still calls a tool', () => {
    apply('turn.started', { display_text: 'hi', permission_mode: 'default', origin: 'web' })
    apply('output.text', { content: '我来跑一下', message_id: 'msg-1' })
    toolStep('msg-1', 'Bash', 'tu-1')
    apply('turn.completed', { done: true, elapsed_ms: 400, user_cancel: false, turn: 1 })

    expect(view().roles).toEqual({})
  })

  it('counts tool calls across the whole turn, subagents separately', () => {
    // Upstream publishes state.toolCallCount unfiltered — a tool call in the
    // answer step counts too, unlike messageCount.
    apply('turn.started', { display_text: 'hi', permission_mode: 'default', origin: 'web' })
    apply('output.text', { content: '中间消息', message_id: 'msg-1' })
    toolStep('msg-1', 'Bash', 'tu-1')
    toolStep('msg-1', 'Agent', 'tu-2')
    toolStep('msg-1', 'Task', 'tu-3')
    apply('output.text', { content: '最终答案', message_id: 'msg-2' })
    apply('turn.completed', { done: true, elapsed_ms: 100, user_cancel: false, turn: 1 })

    const rows = conversation().messages
    expect(view().counts[rows[0].id]).toEqual({
      toolCallCount: 1, messageCount: 1, subagentCount: 2,
    })
  })

  it('hides the answer row own reasoning when the answer step carries it', () => {
    apply('turn.started', { display_text: 'hi', permission_mode: 'default', origin: 'web' })
    toolStep('msg-1', 'Bash', 'tu-1')
    apply('output.thinking', { content: '答案前的思考', message_id: 'msg-2' })
    apply('output.text', { content: '答案', message_id: 'msg-2' })
    apply('turn.completed', { done: true, elapsed_ms: 100, user_cancel: false, turn: 1 })

    const rows = conversation().messages
    const answer = rows[rows.length - 1]
    expect(view().roles[answer.id]).toBe('answer')
    expect(view().inlineReasoningAnswers.has(answer.id)).toBe(true)
    expect(view().compactAnswers.has(answer.id)).toBe(true)
  })

  it('treats synthetic System text as a foldable context row, never an answer', () => {
    apply('turn.started', { display_text: 'hi', permission_mode: 'default', origin: 'web' })
    toolStep('msg-1', 'Bash', 'tu-1')
    apply('output.text', { content: '系统提示：额度不足', synthetic: true })
    apply('output.text', { content: '真正的答案', message_id: 'msg-2' })
    apply('turn.completed', { done: true, elapsed_ms: 100, user_cancel: false, turn: 1 })

    const rows = conversation().messages
    const synthetic = rows.find((row) => row.kind === 'assistant' && row.synthetic === true)
    const answer = rows[rows.length - 1]
    expect(synthetic).toBeDefined()
    expect(view().roles[answer.id]).toBe('answer')
    expect(view().roles[synthetic!.id]).toBe('member')
  })

  it('marks nothing outside compact mode', () => {
    useTranscriptView.getState().setMode('normal')
    apply('turn.started', { display_text: 'hi', permission_mode: 'default', origin: 'web' })
    toolStep('msg-1', 'Bash', 'tu-1')
    apply('output.text', { content: '答案', message_id: 'msg-2' })
    apply('turn.completed', { done: true, elapsed_ms: 100, user_cancel: false, turn: 1 })

    expect(view().roles).toEqual({})
    expect(view().controlTurns).toEqual({})
  })

  it('handles consecutive turns independently', () => {
    apply('turn.started', { display_text: '一', permission_mode: 'default', origin: 'web' })
    toolStep('msg-1', 'Bash', 'tu-1')
    apply('output.text', { content: '答案一', message_id: 'msg-2' })
    apply('turn.completed', { done: true, elapsed_ms: 100, user_cancel: false, turn: 1 })
    apply('turn.started', { display_text: '二', permission_mode: 'default', origin: 'web' })
    apply('output.text', { content: '答案二', message_id: 'msg-3' })
    apply('turn.completed', { done: true, elapsed_ms: 100, user_cancel: false, turn: 2 })

    const rows = conversation().messages
    const firstUser = rows[0]
    const secondUser = rows.find((row) => row.kind === 'user' && row.id !== firstUser.id)
    expect(view().controlTurns[firstUser.id]).toBe(1)
    // An answer-only turn has no process rows to fold.
    expect(view().controlTurns[secondUser!.id]).toBeUndefined()
  })

  it('degrades to one row when the gateway reports no step id', () => {
    // An older gateway publishes no message_id; the reduction keeps its
    // single-row behavior and the turn stays unfolded rather than guessing a
    // boundary from block order.
    apply('turn.started', { display_text: 'hi', permission_mode: 'default', origin: 'web' })
    apply('tool.started', { name: 'Bash', tool_use_id: 'tu-1', input: {} })
    apply('tool.completed', {
      status: 'completed', tool_use_id: 'tu-1', result: { type: 'tool_result', data: 'ok' },
    })
    apply('output.text', { content: '答案' })
    apply('turn.completed', { done: true, elapsed_ms: 100, user_cancel: false, turn: 1 })

    expect(conversation().messages.filter((row) => row.kind === 'assistant')).toHaveLength(1)
    expect(view().roles).toEqual({})
  })

  it('passes the expanded set through for manually opened groups', () => {
    apply('turn.started', { display_text: 'hi', permission_mode: 'default', origin: 'web' })
    toolStep('msg-1', 'Bash', 'tu-1')
    apply('output.text', { content: '答案', message_id: 'msg-2' })
    apply('turn.completed', { done: true, elapsed_ms: 100, user_cancel: false, turn: 1 })

    expect(view(new Set([1])).expandedTurns.has(1)).toBe(true)
  })

  it('folds a reasoning-only process behind the answer (the 已思考 label case)', () => {
    apply('turn.started', { display_text: 'hi', permission_mode: 'default', origin: 'web' })
    apply('output.thinking', { content: '长时间推敲', message_id: 'msg-1' })
    apply('output.text', { content: '答案', message_id: 'msg-2' })
    apply('turn.completed', { done: true, elapsed_ms: 100, user_cancel: false, turn: 1 })

    const rows = conversation().messages
    expect(view().roles[rows[1].id]).toBe('member')
    expect(view().counts[rows[0].id]).toEqual({
      toolCallCount: 0, messageCount: 0, subagentCount: 0,
    })
  })
})

describe('per-turn derivation memo', () => {
  /** Build a settled turn that folds, plus a following turn to stream into. */
  function twoSettledTurns(): void {
    apply('turn.started', { display_text: '一', permission_mode: 'default', origin: 'web' })
    toolStep('msg-1', 'Bash', 'tu-1')
    apply('output.text', { content: '答案一', message_id: 'msg-2' })
    apply('turn.completed', { done: true, elapsed_ms: 100, user_cancel: false, turn: 1 })
    apply('turn.started', { display_text: '二', permission_mode: 'default', origin: 'web' })
    toolStep('msg-3', 'Read', 'tu-2')
    apply('output.text', { content: '答案二', message_id: 'msg-4' })
    apply('turn.completed', { done: true, elapsed_ms: 100, user_cancel: false, turn: 2 })
  }

  it('returns the same derived view whether or not the memo is warm', () => {
    twoSettledTurns()
    const cold = view()
    const warm = view()
    // The memo must be transparent: identical output, re-derived or cached.
    expect(warm.roles).toEqual(cold.roles)
    expect(warm.controlTurns).toEqual(cold.controlTurns)
    expect(warm.counts).toEqual(cold.counts)
    expect(warm.memberTurns).toEqual(cold.memberTurns)
    expect([...warm.compactAnswers]).toEqual([...cold.compactAnswers])
    expect([...warm.inlineReasoningAnswers]).toEqual([...cold.inlineReasoningAnswers])
  })

  it('reuses settled turns derived results across streaming deltas', () => {
    // The whole point of the fix: a token delta rebuilds the messages array,
    // so without a per-turn memo every settled turn is re-specced per token.
    // The memo returns the *same* derived objects for untouched turns, which
    // is the observable proof the vendored rules did not run again.
    // (Spying on `processSpec` cannot show this: `turnProcess.ts` binds the
    // import at module load, so a namespace spy is never consulted.)
    twoSettledTurns()
    apply('turn.started', { display_text: '三', permission_mode: 'default', origin: 'web' })
    apply('output.text', { content: '流式片段', message_id: 'msg-5' })
    const before = view()

    apply('output.text', { content: '更多片段', message_id: 'msg-5' })
    const after = view()

    const settledAnchors = Object.keys(before.counts)
    expect(settledAnchors.length).toBe(2)
    for (const anchor of settledAnchors) {
      // Identity, not just equality: a re-derive would allocate fresh objects.
      expect(after.counts[anchor]).toBe(before.counts[anchor])
    }
  })

  it('re-derives a turn whose own rows changed', () => {
    apply('turn.started', { display_text: '一', permission_mode: 'default', origin: 'web' })
    toolStep('msg-1', 'Bash', 'tu-1')
    apply('output.text', { content: '中间', message_id: 'msg-2' })
    // Still running: nothing folds yet.
    expect(view().controlTurns).toEqual({})

    apply('output.text', { content: '最终答案', message_id: 'msg-3' })
    apply('turn.completed', { done: true, elapsed_ms: 100, user_cancel: false, turn: 1 })

    // The cached "no fold" answer must not survive the turn closing.
    const rows = conversation().messages
    expect(view().controlTurns[rows[0].id]).toBe(1)
    expect(view().roles[rows[rows.length - 1].id]).toBe('answer')
  })

  it('re-derives when the transcript mode flips back to compact', () => {
    twoSettledTurns()
    const compact = view()
    expect(Object.keys(compact.controlTurns).length).toBeGreaterThan(0)

    useTranscriptView.getState().setMode('normal')
    expect(view().controlTurns).toEqual({})

    useTranscriptView.getState().setMode('compact')
    expect(view().controlTurns).toEqual(compact.controlTurns)
  })
})

describe('isSubagentDelegationTool', () => {  it('recognizes this product delegation tool and its alias', () => {
    // Deviation from upstream (subagent / subagent_*): the shipped name here
    // is Agent with the Task alias.
    expect(isSubagentDelegationTool('Agent')).toBe(true)
    expect(isSubagentDelegationTool('Task')).toBe(true)
    expect(isSubagentDelegationTool('SendMessage')).toBe(false)
    expect(isSubagentDelegationTool('Bash')).toBe(false)
  })
})

describe('useTurnProcess store', () => {
  it('toggles per-turn expansion immutably', () => {
    useTurnProcess.getState().setOpen(SESSION, 3, true)
    expect(openTurnsOf(useTurnProcess.getState(), SESSION).has(3)).toBe(true)
    useTurnProcess.getState().setOpen(SESSION, 3, false)
    expect(openTurnsOf(useTurnProcess.getState(), SESSION).has(3)).toBe(false)
  })

  it('keeps one session expansion out of another with the same turn number', () => {
    useTurnProcess.getState().setOpen(SESSION, 3, true)

    expect(openTurnsOf(useTurnProcess.getState(), 'other').has(3)).toBe(false)
    // Switching away and back must not lose it either — the state is keyed,
    // not reset on switch.
    expect(openTurnsOf(useTurnProcess.getState(), SESSION).has(3)).toBe(true)
  })

  it('gives an untouched session a stable empty set', () => {
    const first = openTurnsOf(useTurnProcess.getState(), 'never-touched')
    useTurnProcess.getState().setOpen(SESSION, 1, true)

    // A fresh Set each read would retrigger every memo keyed on it.
    expect(openTurnsOf(useTurnProcess.getState(), 'never-touched')).toBe(first)
    expect(first.size).toBe(0)
  })
})

import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { MessagesSnapshot, MirrorFrame } from '../api/types'
import type { ToolProgressEntry } from './conversations'
import { useConversations } from './conversations'

vi.mock('../api/client', () => ({ fetchSnapshot: vi.fn() }))

const SESSION = 'sess-1'

function frame(event: MirrorFrame['event'], id: number, data: Record<string, unknown>): MirrorFrame {
  return { event, id, data: { session_id: SESSION, ...data } } as MirrorFrame
}

const store = () => useConversations.getState()

describe('conversations frame reduction', () => {
  beforeEach(() => {
    useConversations.setState({ conversations: {} })
  })

  it('opens an assistant row on output.text and appends full blocks', () => {
    store().applyFrame(frame('output.text', 1, { content: '你好' }))
    store().applyFrame(frame('output.text', 2, { content: '世界' }))
    const conversation = useConversations.getState().conversations[SESSION]
    const last = conversation.messages[conversation.messages.length - 1]
    expect(last).toMatchObject({ kind: 'assistant', textBlocks: ['你好', '世界'] })
  })

  it('collects thinking blocks separately from text', () => {
    store().applyFrame(frame('output.thinking', 1, { content: '让我想想' }))
    store().applyFrame(frame('output.text', 2, { content: '答案' }))
    const conversation = useConversations.getState().conversations[SESSION]
    const last = conversation.messages[conversation.messages.length - 1]
    expect(last).toMatchObject({ kind: 'assistant', thinkingBlocks: ['让我想想'], textBlocks: ['答案'] })
  })

  it('pairs tool.started with tool.completed by tool_use_id', () => {
    store().applyFrame(frame('tool.started', 1, { name: 'Bash', tool_use_id: 'tu-1', input: { command: 'ls' } }))
    store().applyFrame(frame('tool.completed', 2, {
      status: 'completed',
      tool_use_id: 'tu-1',
      result: { type: 'execute_command_tool_result', data: 'a.txt\nb.txt' },
    }))
    const conversation = useConversations.getState().conversations[SESSION]
    const last = conversation.messages[conversation.messages.length - 1]
    expect(last).toMatchObject({
      kind: 'assistant',
      toolCalls: [{
        toolUseId: 'tu-1',
        name: 'Bash',
        // The mirror's "completed" maps to the snapshot vocabulary's
        // "executed" so live and restored rows share one status enum.
        status: 'executed',
        ready: true,
        resultData: 'a.txt\nb.txt',
        resultType: 'execute_command_tool_result',
      }],
    })
  })

  it('keeps a tool call pending until its result frame', () => {
    store().applyFrame(frame('tool.started', 1, { name: 'Read', tool_use_id: 'tu-9', input: {} }))
    const conversation = useConversations.getState().conversations[SESSION]
    const last = conversation.messages[conversation.messages.length - 1]
    expect(last).toMatchObject({
      kind: 'assistant',
      toolCalls: [{ toolUseId: 'tu-9', status: 'pending', ready: false }],
    })
  })

  it('closes the assistant row on turn.completed and clears the spinner', () => {
    store().applyFrame(frame('turn.started', 1, { display_text: 'go', permission_mode: 'ask', origin: 'chat' }))
    store().applyFrame(frame('output.text', 2, { content: 'done' }))
    store().applyFrame(frame('turn.completed', 3, { done: true, elapsed_ms: 10, user_cancel: false }))
    const conversation = useConversations.getState().conversations[SESSION]
    const last = conversation.messages[conversation.messages.length - 1]
    expect(last).toMatchObject({ kind: 'assistant', open: false })
    expect(conversation.turnRunning).toBe(false)
  })

  it('lands the turn.completed delta facts on the closing assistant row', () => {
    store().applyFrame(frame('turn.started', 1, { display_text: 'go', permission_mode: 'ask', origin: 'chat' }))
    store().applyFrame(frame('output.text', 2, { content: 'done' }))
    store().applyFrame(frame('turn.completed', 3, {
      done: true, elapsed_ms: 7_300, user_cancel: false, turn: 4, ttft_ms: 900,
      turn_usage: {
        uncached_input_tokens: 200, output_tokens: 200,
        cache_write_tokens: 100, cache_read_tokens: 900, total_tokens: 1_400,
      },
    }))
    const conversation = useConversations.getState().conversations[SESSION]
    const last = conversation.messages[conversation.messages.length - 1]
    // The turn tail chrome renders from these: the usage pill's buckets,
    // the time pill's wall clock and TTFT, and the turn number for ordering.
    expect(last).toMatchObject({
      kind: 'assistant',
      open: false,
      turn: 4,
      runMs: 7_300,
      ttftMs: 900,
      turnUsage: {
        uncached_input_tokens: 200, output_tokens: 200,
        cache_write_tokens: 100, cache_read_tokens: 900, total_tokens: 1_400,
      },
    })
  })

  it('a turn.completed frame without the delta leaves the row without tail facts', () => {
    store().applyFrame(frame('turn.started', 1, { display_text: 'go', permission_mode: 'ask', origin: 'chat' }))
    store().applyFrame(frame('output.text', 2, { content: 'done' }))
    // No metrics reader (unwired composition): the frame carries only the
    // completion facts; the wall time still lands (it is always present).
    store().applyFrame(frame('turn.completed', 3, { done: true, elapsed_ms: 5, user_cancel: false }))
    const conversation = useConversations.getState().conversations[SESSION]
    const last = conversation.messages[conversation.messages.length - 1]
    expect(last).toMatchObject({ kind: 'assistant', open: false, runMs: 5 })
    if (last.kind !== 'assistant') throw new Error('expected an assistant row')
    expect(last.turnUsage).toBeUndefined()
    expect(last.turn).toBeUndefined()
    expect(last.ttftMs).toBeUndefined()
  })

  it('stamps the turn number on every assistant step of the closing turn', () => {
    // One turn fans out into N assistant rows (one per message_id). The
    // snapshot path stamps `turn` on every one of them, so the live path
    // must too — otherwise a reload moves the turn-tail chrome to a
    // different row than the one it rendered on live.
    store().applyFrame(frame('turn.started', 1, { display_text: 'go', permission_mode: 'ask', origin: 'chat' }))
    store().applyFrame(frame('output.text', 2, { content: 'thinking out loud', message_id: 'm1' }))
    store().applyFrame(frame('tool.started', 3, { name: 'Bash', tool_use_id: 'tu-1', input: {}, message_id: 'm2' }))
    store().applyFrame(frame('tool.completed', 4, {
      status: 'completed', tool_use_id: 'tu-1', result: { type: 'tool_result', data: 'ok' },
    }))
    store().applyFrame(frame('output.text', 5, { content: 'the answer', message_id: 'm3' }))
    store().applyFrame(frame('turn.completed', 6, {
      done: true, elapsed_ms: 400, user_cancel: false, turn: 2,
    }))

    const rows = useConversations.getState().conversations[SESSION].messages
    const assistants = rows.filter((row) => row.kind === 'assistant')
    expect(assistants).toHaveLength(3)
    for (const row of assistants) {
      if (row.kind !== 'assistant') throw new Error('expected an assistant row')
      expect(row.turn).toBe(2)
    }
  })

  it('stamps the turn tail facts on every row, like the snapshot path', () => {
    // The tail's anchor is the turn's last text-bearing step, NOT necessarily
    // the closing row (a turn ending on a tool call anchors on an earlier
    // step), so the facts must reach every row of the turn exactly as
    // GatewayMessagesSnapshotHandler.assistantEntry stamps them. The single
    // tail is the fold view's `tailAnchors` gate, not a stamping restriction
    // — see turnProcess.test.ts. Stamping only the closing row left the
    // anchor factless on the live path while a reload showed the tail.
    store().applyFrame(frame('turn.started', 1, { display_text: 'go', permission_mode: 'ask', origin: 'chat' }))
    store().applyFrame(frame('output.text', 2, { content: 'step one', message_id: 'm1' }))
    store().applyFrame(frame('output.text', 3, { content: 'step two', message_id: 'm2' }))
    store().applyFrame(frame('turn.completed', 4, {
      done: true, elapsed_ms: 900, user_cancel: false, turn: 1, ttft_ms: 40,
      turn_usage: {
        uncached_input_tokens: 1, output_tokens: 2,
        cache_write_tokens: 0, cache_read_tokens: 0, total_tokens: 3,
      },
    }))

    const rows = useConversations.getState().conversations[SESSION].messages
    const assistants = rows.filter((row) => row.kind === 'assistant')
    expect(assistants).toHaveLength(2)
    for (const row of assistants) {
      if (row.kind !== 'assistant') throw new Error('expected assistant rows')
      expect(row.turn).toBe(1)
      expect(row.runMs).toBe(900)
      expect(row.ttftMs).toBe(40)
      expect(row.turnUsage?.total_tokens).toBe(3)
    }
  })

  it('leaves earlier turns untouched when closing the current one', () => {
    store().applyFrame(frame('turn.started', 1, { display_text: 'first', permission_mode: 'ask', origin: 'chat' }))
    store().applyFrame(frame('output.text', 2, { content: 'a1', message_id: 'm1' }))
    store().applyFrame(frame('turn.completed', 3, { done: true, elapsed_ms: 10, user_cancel: false, turn: 1 }))
    store().applyFrame(frame('turn.started', 4, { display_text: 'second', permission_mode: 'ask', origin: 'chat' }))
    store().applyFrame(frame('output.text', 5, { content: 'a2', message_id: 'm2' }))
    store().applyFrame(frame('turn.completed', 6, { done: true, elapsed_ms: 20, user_cancel: false, turn: 2 }))

    const rows = useConversations.getState().conversations[SESSION].messages
    const firstAnswer = rows[1]
    const secondAnswer = rows[3]
    if (firstAnswer.kind !== 'assistant' || secondAnswer.kind !== 'assistant') {
      throw new Error('expected assistant rows')
    }
    // Turn 2's completion must not restamp turn 1's row.
    expect(firstAnswer.turn).toBe(1)
    expect(firstAnswer.runMs).toBe(10)
    expect(secondAnswer.turn).toBe(2)
    expect(secondAnswer.runMs).toBe(20)
  })

  it('a turn ending on a tool call stamps the same rows live as after a reload', async () => {
    // The user-visible divergence: a turn whose last step is a tool call has
    // no trailing answer row, so the tail chrome's gating facts must sit on
    // the same rows in both paths.
    store().applyFrame(frame('turn.started', 1, { display_text: 'run it', permission_mode: 'ask', origin: 'chat' }))
    store().applyFrame(frame('output.text', 2, { content: 'let me run this', message_id: 'm1' }))
    store().applyFrame(frame('tool.started', 3, { name: 'Bash', tool_use_id: 'tu-9', input: {}, message_id: 'm2' }))
    store().applyFrame(frame('tool.completed', 4, {
      status: 'completed', tool_use_id: 'tu-9', result: { type: 'tool_result', data: 'ok' },
    }))
    store().applyFrame(frame('turn.completed', 5, {
      done: true, elapsed_ms: 50, user_cancel: false, turn: 3,
    }))
    const liveTurns = useConversations.getState().conversations[SESSION].messages
      .filter((row) => row.kind === 'assistant')
      .map((row) => (row.kind === 'assistant' ? row.turn : undefined))

    // The gateway stamps `turn` on every assistant entry of the turn.
    const snapshot: MessagesSnapshot = {
      session_id: SESSION,
      messages: [
        { id: 'u1', role: 'user', text: 'run it', content: [] },
        { id: 'm1', role: 'assistant', turn: 3, content: [{ type: 'text', text: 'let me run this' }] },
        {
          id: 'm2', role: 'assistant', turn: 3,
          content: [{
            type: 'tool_call',
            tool: {
              tool_use_id: 'tu-9', name: 'Bash', args: {}, status: 'executed', ready: true,
              result: { type: 'tool_result', data: 'ok' },
            },
          }],
        },
      ],
    } as unknown as MessagesSnapshot
    const { fetchSnapshot } = await import('../api/client')
    vi.mocked(fetchSnapshot).mockResolvedValue(snapshot)
    await store().loadSnapshot(SESSION)
    const reloadedTurns = useConversations.getState().conversations[SESSION].messages
      .filter((row) => row.kind === 'assistant')
      .map((row) => (row.kind === 'assistant' ? row.turn : undefined))

    expect(liveTurns).toEqual([3, 3])
    expect(reloadedTurns).toEqual(liveTurns)
  })

  it('stamps the running clock on turn.started and clears it when the turn ends', () => {
    store().applyFrame(frame('turn.started', 1, {
      display_text: 'go', permission_mode: 'ask', origin: 'tui', time: 1_700_000_000_000,
    }))
    expect(store().conversations[SESSION]).toMatchObject({ turnRunning: true, turnStartedAt: 1_700_000_000_000 })

    store().applyFrame(frame('turn.completed', 2, { done: true, elapsed_ms: 5 }))
    expect(store().conversations[SESSION].turnRunning).toBe(false)
    expect(store().conversations[SESSION]).not.toHaveProperty('turnStartedAt')
  })

  it.each(['turn.cancelled', 'session.idle', 'session.error'] as const)('clears the running clock on %s', (event) => {
    store().applyFrame(frame('turn.started', 1, {
      display_text: 'go', permission_mode: 'ask', origin: 'tui', time: 42,
    }))
    store().applyFrame(frame(event, 2, { message: 'boom' }))
    expect(store().conversations[SESSION].turnRunning).toBe(false)
    expect(store().conversations[SESSION]).not.toHaveProperty('turnStartedAt')
  })

  it('resumes the running status from a mid-turn snapshot', async () => {
    const { fetchSnapshot } = await import('../api/client')
    vi.mocked(fetchSnapshot).mockResolvedValue({
      session_id: SESSION, messages: [], running_since: 1_700_000_000_123,
    })
    await store().loadSnapshot(SESSION)
    expect(store().conversations[SESSION]).toMatchObject({ turnRunning: true, turnStartedAt: 1_700_000_000_123 })

    vi.mocked(fetchSnapshot).mockResolvedValue({ session_id: SESSION, messages: [] })
    await store().loadSnapshot(SESSION)
    expect(store().conversations[SESSION].turnRunning).toBe(false)
    expect(store().conversations[SESSION]).not.toHaveProperty('turnStartedAt')
  })

  it('builds a streaming draft from transient delta frames without moving the cursor', () => {
    store().applyFrame(frame('turn.started', 4, { display_text: 'q', permission_mode: 'ask', origin: 'chat' }))
    store().applyFrame(frame('output.text.delta', 0, { content: 'Hel', offset: 0 }))
    store().applyFrame(frame('output.text.delta', 0, { content: 'lo', offset: 3 }))
    const conversation = store().conversations[SESSION]
    expect(conversation.streaming).toEqual({ kind: 'text', content: 'Hello' })
    expect(conversation.lastFrameId).toBe(4)
    // Deltas never touch the rows, so the fold derivation keyed on them stays put.
    expect(conversation.messages).toHaveLength(1)
  })

  it('leaves the state identity alone for a gapped delta', () => {
    store().applyFrame(frame('output.text.delta', 0, { content: 'Hel', offset: 0 }))
    const before = store().conversations[SESSION]
    store().applyFrame(frame('output.text.delta', 0, { content: 'world', offset: 9 }))
    expect(store().conversations[SESSION]).toBe(before)
  })

  it('replaces the draft with the settled block', () => {
    store().applyFrame(frame('output.thinking.delta', 0, { content: 'hmm', offset: 0 }))
    store().applyFrame(frame('output.thinking', 1, { content: 'hmm', message_id: 'm1' }))
    expect(store().conversations[SESSION]).not.toHaveProperty('streaming')
    store().applyFrame(frame('output.text.delta', 0, { content: 'Hi', offset: 0 }))
    store().applyFrame(frame('output.text', 2, { content: 'Hi', synthetic: true }))
    // A System projection is not the streamed block.
    expect(store().conversations[SESSION].streaming).toEqual({ kind: 'text', content: 'Hi' })
    store().applyFrame(frame('output.text', 3, { content: 'Hi', message_id: 'm1' }))
    expect(store().conversations[SESSION]).not.toHaveProperty('streaming')
  })

  it('drops the draft when the turn ends', () => {
    store().applyFrame(frame('turn.started', 1, { display_text: 'q', permission_mode: 'ask', origin: 'chat' }))
    store().applyFrame(frame('output.text.delta', 0, { content: 'partial', offset: 0 }))
    store().applyFrame(frame('turn.cancelled', 2, {}))
    expect(store().conversations[SESSION]).not.toHaveProperty('streaming')
  })

  it('seeds the draft from a mid-block snapshot and continues it', async () => {
    const { fetchSnapshot } = await import('../api/client')
    vi.mocked(fetchSnapshot).mockResolvedValue({
      session_id: SESSION, messages: [], running_since: 1, streaming: { kind: 'thinking', content: 'let me' },
    })
    await store().loadSnapshot(SESSION)
    store().applyFrame(frame('output.thinking.delta', 0, { content: ' think', offset: 6 }))
    expect(store().conversations[SESSION].streaming).toEqual({ kind: 'thinking', content: 'let me think' })
  })

  it('appends the submitted message immediately on turn.started', () => {
    store().applyFrame(frame('turn.started', 1, { display_text: '你好', permission_mode: 'ask', origin: 'chat' }))
    const conversation = useConversations.getState().conversations[SESSION]
    expect(conversation.messages).toHaveLength(1)
    expect(conversation.messages[0]).toMatchObject({ kind: 'user', text: '你好' })
  })

  it('threads epoch time onto user rows and the closing assistant tail', () => {
    // The clock-label fact: turn.started stamps the user row, turn.completed
    // stamps the closing assistant row (the turn-tail clock renders from it).
    store().applyFrame(frame('turn.started', 1, {
      display_text: '几点了', permission_mode: 'ask', origin: 'chat', time: 1_700_000_000_000,
    }))
    store().applyFrame(frame('output.text', 2, { content: '刚刚' }))
    store().applyFrame(frame('turn.completed', 3, {
      done: true, elapsed_ms: 5, user_cancel: false, time: 1_700_000_005_000,
    }))
    const conversation = useConversations.getState().conversations[SESSION]
    const user = conversation.messages[0]
    const last = conversation.messages[conversation.messages.length - 1]
    expect(user).toMatchObject({ kind: 'user', time: 1_700_000_000_000 })
    expect(last).toMatchObject({ kind: 'assistant', open: false, time: 1_700_000_005_000 })
  })

  it('keeps a user row without a frame time clockless', () => {
    // An older gateway (or a synthetic frame) carries no time: the row stays
    // clockless and the chrome omits the label, not a placeholder.
    store().applyFrame(frame('turn.started', 1, { display_text: 'go', permission_mode: 'ask', origin: 'chat' }))
    const conversation = useConversations.getState().conversations[SESSION]
    expect(conversation.messages[0]).toMatchObject({ kind: 'user' })
    expect((conversation.messages[0] as { time?: number }).time).toBeUndefined()
  })

  it('tracks the running spinner between turn.started and completion', () => {
    store().applyFrame(frame('turn.started', 1, { display_text: 'go', permission_mode: 'ask', origin: 'chat' }))
    expect(useConversations.getState().conversations[SESSION].turnRunning).toBe(true)
    store().applyFrame(frame('turn.completed', 2, { done: true, elapsed_ms: 1, user_cancel: false }))
    expect(useConversations.getState().conversations[SESSION].turnRunning).toBe(false)
  })

  it('drops journal replay overlaps by frame id', () => {
    store().applyFrame(frame('output.text', 5, { content: '第一帧' }))
    store().applyFrame(frame('output.text', 5, { content: '重复帧' }))
    store().applyFrame(frame('output.text', 3, { content: '旧帧' }))
    const conversation = useConversations.getState().conversations[SESSION]
    const last = conversation.messages[conversation.messages.length - 1]
    expect(last).toMatchObject({ kind: 'assistant', textBlocks: ['第一帧'] })
    expect(conversation.lastFrameId).toBe(5)
  })

  it('records a failed tool result with its error message', () => {
    store().applyFrame(frame('tool.started', 1, { name: 'Bash', tool_use_id: 'tu-2' }))
    store().applyFrame(frame('tool.completed', 2, {
      status: 'failed',
      tool_use_id: 'tu-2',
      result: { type: 'execute_command_tool_result', data: null, errorMessage: 'boom', errorCode: 'tool_error' },
    }))
    const conversation = useConversations.getState().conversations[SESSION]
    const last = conversation.messages[conversation.messages.length - 1]
    expect(last).toMatchObject({
      kind: 'assistant',
      toolCalls: [{ status: 'failed', ready: true, resultError: 'boom' }],
    })
  })

  it('keeps sessions isolated by id', () => {
    store().applyFrame(frame('output.text', 1, { content: 'A' }))
    useConversations.getState().applyFrame({ event: 'output.text', id: 1, data: { session_id: 'sess-2', content: 'B' } })
    const conversations = useConversations.getState().conversations
    expect(conversations['sess-1'].messages).toHaveLength(1)
    expect(conversations['sess-2'].messages).toHaveLength(1)
  })

  it('records a session error and stops the spinner', () => {
    store().applyFrame(frame('turn.started', 1, { display_text: 'go', permission_mode: 'ask', origin: 'chat' }))
    store().applyFrame(frame('session.error', 2, { message: 'turn failed' }))
    const conversation = useConversations.getState().conversations[SESSION]
    expect(conversation.turnRunning).toBe(false)
    expect(conversation.lastError).toBe('turn failed')
  })

  it('opens a fresh assistant row after a completed one', () => {
    store().applyFrame(frame('output.text', 1, { content: '第一轮' }))
    store().applyFrame(frame('turn.completed', 2, { done: true, elapsed_ms: 1, user_cancel: false }))
    store().applyFrame(frame('turn.started', 3, { display_text: 'again', permission_mode: 'ask', origin: 'chat' }))
    store().applyFrame(frame('output.text', 4, { content: '第二轮' }))
    const conversation = useConversations.getState().conversations[SESSION]
    expect(conversation.messages).toHaveLength(3)
    expect(conversation.messages[0]).toMatchObject({ kind: 'assistant', open: false })
    expect(conversation.messages[1]).toMatchObject({ kind: 'user', text: 'again' })
    expect(conversation.messages[2]).toMatchObject({ kind: 'assistant', textBlocks: ['第二轮'], open: true })
  })

  it('threads a tool.completed result\'s locations into the tool call', () => {
    store().applyFrame(frame('tool.started', 1, { name: 'Write', tool_use_id: 'tu-3' }))
    store().applyFrame(frame('tool.completed', 2, {
      status: 'completed',
      tool_use_id: 'tu-3',
      result: {
        type: 'replace_in_file_tool_result',
        data: 'File created successfully',
        locations: ['/work/new-file.txt'],
      },
    }))
    const conversation = useConversations.getState().conversations[SESSION]
    const last = conversation.messages[conversation.messages.length - 1]
    expect(last).toMatchObject({
      kind: 'assistant',
      toolCalls: [{ toolUseId: 'tu-3', locations: ['/work/new-file.txt'] }],
    })
  })

  it('leaves locations null for a tool.completed result without any', () => {
    store().applyFrame(frame('tool.started', 1, { name: 'Bash', tool_use_id: 'tu-4' }))
    store().applyFrame(frame('tool.completed', 2, {
      status: 'completed',
      tool_use_id: 'tu-4',
      result: { type: 'execute_command_tool_result', data: 'ok' },
    }))
    const conversation = useConversations.getState().conversations[SESSION]
    const last = conversation.messages[conversation.messages.length - 1]
    expect(last).toMatchObject({
      kind: 'assistant',
      toolCalls: [{ toolUseId: 'tu-4', locations: null }],
    })
  })

  it('threads a snapshot tool result\'s locations into the tool call', async () => {
    const { fetchSnapshot } = await import('../api/client')
    const snapshot: MessagesSnapshot = {
      session_id: SESSION,
      messages: [{
        id: 'm-1',
        role: 'assistant',
        complete: true,
        content: [{
          type: 'tool_call',
          tool: {
            tool_use_id: 'tu-5',
            name: 'Edit',
            args: null,
            status: 'executed',
            ready: true,
            result: {
              type: 'replace_in_file_tool_result',
              data: 'the file has been updated',
              locations: ['/work/existing-file.txt'],
            },
          },
        }],
      }],
    }
    vi.mocked(fetchSnapshot).mockResolvedValue(snapshot)
    await store().loadSnapshot(SESSION)
    const conversation = useConversations.getState().conversations[SESSION]
    expect(conversation.messages[0]).toMatchObject({
      kind: 'assistant',
      toolCalls: [{ toolUseId: 'tu-5', locations: ['/work/existing-file.txt'] }],
    })
  })

  it('threads a snapshot assistant entry turn and usage facts into the row', async () => {
    const { fetchSnapshot } = await import('../api/client')
    const snapshot: MessagesSnapshot = {
      session_id: SESSION,
      messages: [{
        id: 'm-2',
        role: 'assistant',
        complete: true,
        turn: 2,
        turn_usage: {
          uncached_input_tokens: 1_200, output_tokens: 900,
          cache_write_tokens: 300, cache_read_tokens: 9_800, total_tokens: 12_200,
        },
        content: [{ type: 'text', text: '回答完毕' }],
      }],
    }
    vi.mocked(fetchSnapshot).mockResolvedValue(snapshot)
    await store().loadSnapshot(SESSION)
    const conversation = useConversations.getState().conversations[SESSION]
    // The restored tail chrome renders from these: no live frame will ever
    // revisit a snapshot row, so the snapshot must carry the whole tail.
    expect(conversation.messages[0]).toMatchObject({
      kind: 'assistant',
      turn: 2,
      turnUsage: {
        uncached_input_tokens: 1_200, output_tokens: 900,
        cache_write_tokens: 300, cache_read_tokens: 9_800, total_tokens: 12_200,
      },
    })
  })

  it('threads snapshot epoch time onto user and assistant rows', async () => {
    // Both row kinds stamp `time` — the user row's leading clock and the
    // assistant tail's trailing clock render from the snapshot alone.
    const { fetchSnapshot } = await import('../api/client')
    const snapshot: MessagesSnapshot = {
      session_id: SESSION,
      messages: [
        { id: 'm-1', role: 'user', complete: true, text: '几点问的', time: 1_700_000_000_000 },
        {
          id: 'm-2',
          role: 'assistant',
          complete: true,
          time: 1_700_000_005_000,
          content: [{ type: 'text', text: '刚刚' }],
        },
      ],
    }
    vi.mocked(fetchSnapshot).mockResolvedValue(snapshot)
    await store().loadSnapshot(SESSION)
    const conversation = useConversations.getState().conversations[SESSION]
    expect(conversation.messages[0]).toMatchObject({ kind: 'user', time: 1_700_000_000_000 })
    expect(conversation.messages[1]).toMatchObject({ kind: 'assistant', time: 1_700_000_005_000 })
  })

  // The user row reached the transcript twice because the snapshot already
  // carried it while the replayed turn.started appended a second live row
  // under a different id space. The cursor is what stops that overlap.
  it('keeps the frame cursor across a snapshot reload', async () => {
    store().applyFrame(frame('output.text', 7, { content: '已落盘' }))
    expect(store().conversations[SESSION].lastFrameId).toBe(7)

    const { fetchSnapshot } = await import('../api/client')
    vi.mocked(fetchSnapshot).mockResolvedValue({ session_id: SESSION, messages: [] })
    await store().loadSnapshot(SESSION)

    expect(store().conversations[SESSION].lastFrameId).toBe(7)
  })

  it('drops a turn.started replayed after the snapshot already folded its prompt', async () => {
    const PROMPT_AT = 1_700_000_000_000
    const { fetchSnapshot } = await import('../api/client')
    vi.mocked(fetchSnapshot).mockResolvedValue({
      session_id: SESSION,
      messages: [{ id: 'm-1', role: 'user', complete: true, text: '继续吧', time: PROMPT_AT }],
    })
    await store().loadSnapshot(SESSION)
    // The snapshot re-baselined the cursor past a frame the ring still holds,
    // so this replay is no longer caught by the id check alone.
    store().applyFrame(frame('turn.started', 2, {
      display_text: '继续吧', permission_mode: 'ask', origin: 'tui', time: PROMPT_AT,
    }))

    const rows = useConversations.getState().conversations[SESSION].messages
      .filter((row) => row.kind === 'user')
    expect(rows).toHaveLength(1)
    // Only the duplicate row is suppressed; the turn itself still opens.
    expect(store().conversations[SESSION].turnRunning).toBe(true)
  })

  // The guard above must not swallow a genuine repeat of the same wording: a
  // second "继续吧" is dispatched later and carries its own clock.
  it('appends a second prompt that repeats the previous wording', () => {
    store().applyFrame(frame('turn.started', 1, {
      display_text: '继续吧', permission_mode: 'ask', origin: 'tui', time: 1_700_000_000_000,
    }))
    store().applyFrame(frame('turn.completed', 2, { done: true, elapsed_ms: 5 }))
    store().applyFrame(frame('turn.started', 3, {
      display_text: '继续吧', permission_mode: 'ask', origin: 'tui', time: 1_700_000_060_000,
    }))

    const rows = useConversations.getState().conversations[SESSION].messages
      .filter((row) => row.kind === 'user')
    expect(rows).toHaveLength(2)
    expect(rows.map((row) => (row.kind === 'user' ? row.time : undefined)))
      .toEqual([1_700_000_000_000, 1_700_000_060_000])
  })
})

describe('sub-agent progress reduction', () => {
  beforeEach(() => {
    useConversations.setState({ conversations: {} })
  })

  /** Launch a Task call and hand back its accumulated progress log. */
  function progressOf(toolUseId = 'tu-task'): readonly ToolProgressEntry[] {
    const rows = useConversations.getState().conversations[SESSION].messages
    for (const row of rows) {
      if (row.kind !== 'assistant') continue
      const call = row.toolCalls.find((entry) => entry.toolUseId === toolUseId)
      if (call != null) return call.progress
    }
    throw new Error('no tool call ' + toolUseId)
  }

  it('appends one entry per frame, in arrival order', () => {
    // The sub-agent's live channel is a discrete update log, not a token
    // stream: each frame is one whole message or status line, so the entries
    // concatenate by arrival rather than merge by character offset.
    store().applyFrame(frame('tool.started', 1, {
      name: 'Task', tool_use_id: 'tu-task', input: { description: 'audit the router' },
    }))
    store().applyFrame(frame('tool.progress', 2, {
      kind: 'agent_progress', tool_use_id: 'tu-task', agent_id: 'a1', content: 'reading files',
    }))
    store().applyFrame(frame('tool.progress', 3, {
      kind: 'agent_progress', tool_use_id: 'tu-task', agent_id: 'a1', message: '3 of 9',
    }))
    store().applyFrame(frame('tool.progress', 4, {
      kind: 'agent_progress', tool_use_id: 'tu-task', agent_id: 'a1', content: 'done',
    }))

    expect(progressOf()).toEqual([
      { kind: 'agent_progress', agentId: 'a1', content: 'reading files' },
      { kind: 'agent_progress', agentId: 'a1', message: '3 of 9' },
      { kind: 'agent_progress', agentId: 'a1', content: 'done' },
    ])
  })

  it('starts every call with an empty log, and leaves unrelated calls alone', () => {
    store().applyFrame(frame('tool.started', 1, { name: 'Bash', tool_use_id: 'tu-bash', input: {} }))
    store().applyFrame(frame('tool.started', 2, { name: 'Task', tool_use_id: 'tu-task', input: {} }))
    store().applyFrame(frame('tool.progress', 3, {
      kind: 'agent_progress', tool_use_id: 'tu-task', agent_id: 'a1', content: 'go',
    }))

    expect(progressOf('tu-bash')).toEqual([])
    expect(progressOf('tu-task')).toHaveLength(1)
  })

  it('ignores a progress frame for a tool call that never started', () => {
    // An agent keeps running across a turn boundary; its progress can reach
    // the client after the row was re-baselined by a snapshot and dropped.
    store().applyFrame(frame('tool.progress', 1, {
      kind: 'agent_progress', tool_use_id: 'tu-gone', agent_id: 'a1', content: 'orphan',
    }))
    expect(useConversations.getState().conversations[SESSION].messages).toEqual([])
  })

  it('applies a replayed frame id once, not twice', () => {
    // tool.progress is journalled with a real id (it is not a transient
    // delta), so the cursor check in applyFrame is what keeps an SSE
    // reconnect's replay from duplicating every entry.
    store().applyFrame(frame('tool.started', 1, { name: 'Task', tool_use_id: 'tu-task', input: {} }))
    store().applyFrame(frame('tool.progress', 2, {
      kind: 'agent_progress', tool_use_id: 'tu-task', agent_id: 'a1', content: 'once',
    }))
    store().applyFrame(frame('tool.progress', 2, {
      kind: 'agent_progress', tool_use_id: 'tu-task', agent_id: 'a1', content: 'twice',
    }))

    expect(progressOf()).toEqual([
      { kind: 'agent_progress', agentId: 'a1', content: 'once' },
    ])
  })

  it('collapses a re-sent activity line instead of appending it twice', () => {
    // The producer re-sends the current activity as the child works, so the
    // same line arrives on several frames. The TUI reads those through an
    // observed-id set; here the already-shown line is a no-op, so one activity
    // is one line rather than the same line repeated down the row.
    store().applyFrame(frame('tool.started', 1, { name: 'Task', tool_use_id: 'tu-task', input: {} }))
    store().applyFrame(frame('tool.progress', 2, {
      kind: 'agent_progress', tool_use_id: 'tu-task', agent_id: 'a1', content: 'Bash(find .)',
    }))
    store().applyFrame(frame('tool.progress', 3, {
      kind: 'agent_progress', tool_use_id: 'tu-task', agent_id: 'a1', content: 'Bash(find .)',
    }))

    expect(progressOf()).toEqual([
      { kind: 'agent_progress', agentId: 'a1', content: 'Bash(find .)' },
    ])
  })

  it('keeps a repeated line that is not the one on screen', () => {
    // Only a repeat of the *current* line is redundant: a child that returns
    // to an earlier activity has genuinely done something again, and dropping
    // it would misreport the sequence.
    store().applyFrame(frame('tool.started', 1, { name: 'Task', tool_use_id: 'tu-task', input: {} }))
    for (const [id, content] of [[2, 'Read(a)'], [3, 'Read(b)'], [4, 'Read(a)']] as const) {
      store().applyFrame(frame('tool.progress', id, {
        kind: 'agent_progress', tool_use_id: 'tu-task', agent_id: 'a1', content,
      }))
    }

    expect(progressOf().map((entry) => entry.content)).toEqual(['Read(a)', 'Read(b)', 'Read(a)'])
  })

  it('stores no entry for a frame that carried no text at all', () => {
    // Some frames are pure routing — the agent id with nothing to say. An
    // entry with neither `content` nor `message` would render as a blank line.
    store().applyFrame(frame('tool.started', 1, { name: 'Task', tool_use_id: 'tu-task', input: {} }))
    store().applyFrame(frame('tool.progress', 2, {
      kind: 'agent_progress', tool_use_id: 'tu-task', agent_id: 'a1', content: 'real',
    }))
    store().applyFrame(frame('tool.progress', 3, {
      kind: 'agent_progress', tool_use_id: 'tu-task', agent_id: 'a1', prompt: '',
    }))

    expect(progressOf().map((entry) => entry.content)).toEqual(['real'])
  })

  it('drops an out-of-order frame that arrives behind the cursor', () => {
    store().applyFrame(frame('tool.started', 1, { name: 'Task', tool_use_id: 'tu-task', input: {} }))
    store().applyFrame(frame('tool.progress', 5, {
      kind: 'agent_progress', tool_use_id: 'tu-task', agent_id: 'a1', content: 'later',
    }))
    store().applyFrame(frame('tool.progress', 3, {
      kind: 'agent_progress', tool_use_id: 'tu-task', agent_id: 'a1', content: 'earlier',
    }))
    store().applyFrame(frame('tool.progress', 6, {
      kind: 'agent_progress', tool_use_id: 'tu-task', agent_id: 'a1', content: 'newest',
    }))

    expect(progressOf().map((entry) => entry.content)).toEqual(['later', 'newest'])
  })

  it('keeps the whole log when the call completes', () => {
    // The result card and the live log are two sources for one row: the log
    // is the running view, the result is the settled one. Dropping the log on
    // completion would blank a still-expanded row.
    store().applyFrame(frame('tool.started', 1, { name: 'Task', tool_use_id: 'tu-task', input: {} }))
    store().applyFrame(frame('tool.progress', 2, {
      kind: 'agent_progress', tool_use_id: 'tu-task', agent_id: 'a1', content: 'step',
    }))
    store().applyFrame(frame('tool.completed', 3, {
      status: 'completed',
      tool_use_id: 'tu-task',
      result: { type: 'task_tool_result', data: 'the sub-agent summary' },
    }))

    expect(progressOf()).toEqual([
      { kind: 'agent_progress', agentId: 'a1', content: 'step' },
    ])
    const rows = useConversations.getState().conversations[SESSION].messages
    const call = rows.flatMap((row) => (row.kind === 'assistant' ? row.toolCalls : []))
      .find((entry) => entry.toolUseId === 'tu-task')
    expect(call).toMatchObject({ status: 'executed', ready: true, resultData: 'the sub-agent summary' })
  })

  it('carries an entry whose frame omitted the agent id', () => {
    // A status line emitted before the agent registers has no agent_id; the
    // entry must still land so the row can show the line.
    store().applyFrame(frame('tool.started', 1, { name: 'Task', tool_use_id: 'tu-task', input: {} }))
    store().applyFrame(frame('tool.progress', 2, {
      kind: 'agent_progress', tool_use_id: 'tu-task', message: 'starting',
    }))

    expect(progressOf()).toEqual([{ kind: 'agent_progress', message: 'starting' }])
  })
})

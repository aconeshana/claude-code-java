import { beforeEach, describe, expect, it } from 'vitest'
import type { ConversationState, MessageState } from './conversations'
import { deriveTurnProcessView, isSubagentDelegationTool, useTurnProcess } from './turnProcess'
import { useTranscriptView } from './transcriptView'

function assistant(id: string, overrides: Partial<Extract<MessageState, { kind: 'assistant' }>> = {}): MessageState {
  return {
    kind: 'assistant',
    id,
    textBlocks: [],
    thinkingBlocks: [],
    toolCalls: [],
    open: false,
    ...overrides,
  }
}

function userRow(id: string, text: string): MessageState {
  return { kind: 'user', id, text }
}

function conversation(messages: readonly MessageState[]): ConversationState {
  return { messages, lastFrameId: 0, turnRunning: false, lastError: null }
}

const EMPTY_SET: ReadonlySet<number> = new Set()

beforeEach(() => {
  useTurnProcess.setState({ openTurns: new Set() })
  useTranscriptView.getState().setMode('compact')
})

describe('deriveTurnProcessView eligibility', () => {
  it('marks nothing outside compact mode', () => {
    useTranscriptView.getState().setMode('normal')
    const view = deriveTurnProcessView(conversation([
      userRow('u1', 'hi'),
      assistant('a1', { thinkingBlocks: ['hmm'], toolCalls: [{ toolUseId: 't1', name: 'Bash', args: null, status: 'executed', ready: true, resultData: '', resultType: null, resultError: null, transcriptPath: null, locations: null }] }),
      assistant('a2', { textBlocks: ['done'], turn: 1 }),
    ]), EMPTY_SET)
    expect(view.roles).toEqual({})
    expect(view.controlTurns).toEqual({})
  })

  it('folds tool calls and thinking behind the final answer', () => {
    const view = deriveTurnProcessView(conversation([
      userRow('u1', 'hi'),
      assistant('a1', { thinkingBlocks: ['hmm'], toolCalls: [
        { toolUseId: 't1', name: 'Bash', args: null, status: 'executed', ready: true, resultData: '', resultType: null, resultError: null, transcriptPath: null, locations: null },
        { toolUseId: 't2', name: 'Read', args: null, status: 'executed', ready: true, resultData: '', resultType: null, resultError: null, transcriptPath: null, locations: null },
      ], turn: 1 }),
      assistant('a2', { textBlocks: ['done'], turn: 1 }),
    ]), EMPTY_SET)
    expect(view.roles['a1']).toBe('member')
    expect(view.roles['a2']).toBe('answer')
    expect(view.controlTurns['u1']).toBe(1)
    expect(view.counts['u1']).toEqual({ toolCallCount: 2, messageCount: 0, subagentCount: 0 })
    expect(view.memberTurns['a1']).toBe(1)
  })

  it('keeps every row visible when the turn has no answer', () => {
    const view = deriveTurnProcessView(conversation([
      userRow('u1', 'hi'),
      assistant('a1', { thinkingBlocks: ['hmm'], toolCalls: [{ toolUseId: 't1', name: 'Bash', args: null, status: 'executed', ready: true, resultData: '', resultType: null, resultError: null, transcriptPath: null, locations: null }], turn: 1 }),
    ]), EMPTY_SET)
    expect(view.roles).toEqual({})
    expect(view.controlTurns).toEqual({})
  })

  it('never folds an open (running) turn', () => {
    const view = deriveTurnProcessView(conversation([
      userRow('u1', 'hi'),
      assistant('a1', { thinkingBlocks: ['hmm'], open: true }),
    ]), EMPTY_SET)
    expect(view.roles).toEqual({})
  })

  it('keeps an answer-only turn unfolded', () => {
    const view = deriveTurnProcessView(conversation([
      userRow('u1', 'hi'),
      assistant('a1', { textBlocks: ['just the answer'], turn: 1 }),
    ]), EMPTY_SET)
    expect(view.roles).toEqual({})
    expect(view.controlTurns).toEqual({})
  })

  it('counts intermediate reply messages and subagent delegations separately', () => {
    const view = deriveTurnProcessView(conversation([
      userRow('u1', 'hi'),
      assistant('a1', {
        textBlocks: ['intermediate'], toolCalls: [
          { toolUseId: 't1', name: 'subagent', args: null, status: 'executed', ready: true, resultData: '', resultType: null, resultError: null, transcriptPath: null, locations: null },
          { toolUseId: 't2', name: 'subagent_fork', args: null, status: 'executed', ready: true, resultData: '', resultType: null, resultError: null, transcriptPath: null, locations: null },
          { toolUseId: 't3', name: 'Bash', args: null, status: 'executed', ready: true, resultData: '', resultType: null, resultError: null, transcriptPath: null, locations: null },
        ], turn: 1,
      }),
      assistant('a2', { textBlocks: ['final'], turn: 1 }),
    ]), EMPTY_SET)
    expect(view.counts['u1']).toEqual({ toolCallCount: 1, messageCount: 1, subagentCount: 2 })
  })

  it('picks the LAST text answer as the boundary, folding later tool steps too', () => {
    const view = deriveTurnProcessView(conversation([
      userRow('u1', 'hi'),
      assistant('a1', { textBlocks: ['early text'], toolCalls: [{ toolUseId: 't1', name: 'Bash', args: null, status: 'executed', ready: true, resultData: '', resultType: null, resultError: null, transcriptPath: null, locations: null }], turn: 1 }),
      assistant('a2', { textBlocks: ['middle'], turn: 1 }),
      assistant('a3', { textBlocks: ['the real answer'], turn: 1 }),
    ]), EMPTY_SET)
    expect(view.roles['a1']).toBe('member')
    expect(view.roles['a2']).toBe('member')
    expect(view.roles['a3']).toBe('answer')
    expect(view.counts['u1']).toEqual({ toolCallCount: 1, messageCount: 2, subagentCount: 0 })
  })

  it('treats a tool-calling step as process, not answer', () => {
    const view = deriveTurnProcessView(conversation([
      userRow('u1', 'hi'),
      assistant('a1', { textBlocks: ['running the tool now'], toolCalls: [{ toolUseId: 't1', name: 'Bash', args: null, status: 'executed', ready: true, resultData: '', resultType: null, resultError: null, transcriptPath: null, locations: null }], turn: 1 }),
      assistant('a2', { textBlocks: [], thinkingBlocks: ['more'], turn: 1 }),
    ]), EMPTY_SET)
    // No answer row: everything stays visible.
    expect(view.roles).toEqual({})
  })

  it('passes the expanded set through for manually opened groups', () => {
    const view = deriveTurnProcessView(conversation([
      userRow('u1', 'hi'),
      assistant('a1', { thinkingBlocks: ['hmm'], turn: 1 }),
      assistant('a2', { textBlocks: ['done'], turn: 1 }),
    ]), new Set([1]))
    expect(view.expandedTurns.has(1)).toBe(true)
  })

  it('folds a reasoning-only turn (the 已思考 label case)', () => {
    const view = deriveTurnProcessView(conversation([
      userRow('u1', 'hi'),
      assistant('a1', { thinkingBlocks: ['long deliberation'], turn: 1 }),
      assistant('a2', { textBlocks: ['done'], turn: 1 }),
    ]), EMPTY_SET)
    expect(view.roles['a1']).toBe('member')
    expect(view.counts['u1']).toEqual({ toolCallCount: 0, messageCount: 0, subagentCount: 0 })
  })

  it('handles consecutive turns independently', () => {
    const view = deriveTurnProcessView(conversation([
      userRow('u1', 'hi'),
      assistant('a1', { thinkingBlocks: ['hmm'], turn: 1 }),
      assistant('a2', { textBlocks: ['done'], turn: 1 }),
      userRow('u2', 'again'),
      assistant('a3', { textBlocks: ['answer two'], turn: 2 }),
    ]), EMPTY_SET)
    expect(view.controlTurns['u1']).toBe(1)
    expect(view.controlTurns['u2']).toBeUndefined()
  })
})

describe('isSubagentDelegationTool', () => {
  it('recognizes the shipped name and configured variants', () => {
    expect(isSubagentDelegationTool('subagent')).toBe(true)
    expect(isSubagentDelegationTool('subagent_fork')).toBe(true)
    expect(isSubagentDelegationTool('send_message')).toBe(false)
    expect(isSubagentDelegationTool('Bash')).toBe(false)
  })
})

describe('useTurnProcess store', () => {
  it('toggles per-turn expansion immutably', () => {
    useTurnProcess.getState().setOpen(3, true)
    expect(useTurnProcess.getState().openTurns.has(3)).toBe(true)
    useTurnProcess.getState().setOpen(3, false)
    expect(useTurnProcess.getState().openTurns.has(3)).toBe(false)
  })
})

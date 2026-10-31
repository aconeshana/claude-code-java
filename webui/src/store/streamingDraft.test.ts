import { describe, expect, it } from 'vitest'
import type { MessageState } from './conversations'
import { mergeDelta, withStreamingDraft } from './streamingDraft'

describe('mergeDelta', () => {
  it('appends a slice whose offset is the draft length', () => {
    const first = mergeDelta(undefined, 'text', 'Hel', 0)
    expect(mergeDelta(first, 'text', 'lo', 3)).toEqual({ kind: 'text', content: 'Hello' })
  })

  it('counts offsets in UTF-16 code units, like the gateway', () => {
    const first = mergeDelta(undefined, 'text', '你好😀', 0)
    expect(first?.content.length).toBe(4)
    expect(mergeDelta(first, 'text', '!', 4)).toEqual({ kind: 'text', content: '你好😀!' })
  })

  it('truncates to a restarted offset, then appends', () => {
    const draft = { kind: 'text' as const, content: 'stale answer' }
    expect(mergeDelta(draft, 'text', 'fresh', 0)).toEqual({ kind: 'text', content: 'fresh' })
    expect(mergeDelta(draft, 'text', 'X', 5)).toEqual({ kind: 'text', content: 'staleX' })
  })

  it('keeps the draft unchanged across a gap', () => {
    const draft = { kind: 'text' as const, content: 'Hel' }
    expect(mergeDelta(draft, 'text', 'world', 9)).toBe(draft)
    expect(mergeDelta(undefined, 'text', 'world', 9)).toBeUndefined()
  })

  it('starts a fresh draft when the kind changes, from offset 0 only', () => {
    const thinking = { kind: 'thinking' as const, content: 'hmm' }
    expect(mergeDelta(thinking, 'text', 'Hi', 0)).toEqual({ kind: 'text', content: 'Hi' })
    expect(mergeDelta(thinking, 'text', 'Hi', 2)).toBe(thinking)
  })
})

describe('withStreamingDraft', () => {
  const user: MessageState = { kind: 'user', id: 'u1', text: 'q' }
  const openRow = (overrides: Partial<Extract<MessageState, { kind: 'assistant' }>> = {}): MessageState => ({
    kind: 'assistant', id: 'm1', messageId: 'm1', textBlocks: [], thinkingBlocks: ['t'], toolCalls: [],
    open: true, ...overrides,
  })

  it('returns the rows untouched without a draft', () => {
    const rows = [user]
    expect(withStreamingDraft(rows, undefined)).toBe(rows)
    expect(withStreamingDraft(rows, { kind: 'text', content: '' })).toBe(rows)
  })

  it('stands the draft in a fresh open row after the prompt', () => {
    const rows = withStreamingDraft([user], { kind: 'thinking', content: 'let me' })
    expect(rows[1]).toMatchObject({ kind: 'assistant', id: 'streaming', open: true, thinkingBlocks: ['let me'] })
  })

  it('joins the open step that has no tool call yet as its trailing block', () => {
    const rows = withStreamingDraft([user, openRow()], { kind: 'text', content: 'Hel' })
    expect(rows).toHaveLength(2)
    expect(rows[1]).toMatchObject({ id: 'm1', thinkingBlocks: ['t'], textBlocks: ['Hel'] })
  })

  it('opens a new row once the open step has called a tool', () => {
    const call = {
      toolUseId: 'tu', name: 'Bash', args: null, status: 'executed' as const, ready: true,
      resultData: null, resultType: null, resultError: null, transcriptPath: null, locations: null,
    }
    const rows = withStreamingDraft([user, openRow({ toolCalls: [call] })], { kind: 'text', content: 'Done' })
    expect(rows).toHaveLength(3)
    expect(rows[2]).toMatchObject({ id: 'streaming', textBlocks: ['Done'] })
  })
})

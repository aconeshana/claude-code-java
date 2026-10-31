import { describe, expect, it } from 'vitest'
import type { SpinnerVerbsListing } from '../api/types'
import type { MessageState, ToolCallState } from '../store/conversations'
import { latestTurnTool, pickVerb, runningVerb } from './runningVerb'

const VOCABULARY: SpinnerVerbsListing = {
  active: ['Brewing', 'Pondering', 'Zesting', 'Moseying'],
  tools: { Read: 'Reading', Bash: 'Running' },
}

function tool(name: string, toolUseId: string): ToolCallState {
  return {
    toolUseId, name, args: null, status: 'pending', ready: true,
    resultData: null, resultType: null, resultError: null, transcriptPath: null, locations: null,
  }
}

function assistant(id: string, ...toolCalls: ToolCallState[]): MessageState {
  return { kind: 'assistant', id, textBlocks: [], thinkingBlocks: [], toolCalls, open: true }
}

function user(id: string): MessageState {
  return { kind: 'user', id, text: 'hi' }
}

describe('pickVerb', () => {
  it('is stable for one seed and stays inside the list', () => {
    const first = pickVerb(VOCABULARY.active, '1730000000000')
    expect(VOCABULARY.active).toContain(first)
    expect(pickVerb(VOCABULARY.active, '1730000000000')).toBe(first)
  })

  it('spreads different seeds across the list', () => {
    const picks = new Set(Array.from({ length: 64 }, (_, index) => pickVerb(VOCABULARY.active, `toolu_${index}`)))
    expect(picks.size).toBeGreaterThan(1)
  })

  it('answers undefined before the vocabulary loads', () => {
    expect(pickVerb([], 'seed')).toBeUndefined()
  })
})

describe('latestTurnTool', () => {
  it('takes the last call of the nearest assistant row that made one', () => {
    const messages = [
      user('u1'),
      assistant('a1', tool('Read', 't1'), tool('Bash', 't2')),
      assistant('a2'),
    ]
    expect(latestTurnTool(messages)?.toolUseId).toBe('t2')
  })

  it('does not reach past the turn opening user row', () => {
    const messages = [assistant('a0', tool('Read', 't0')), user('u1'), assistant('a1')]
    expect(latestTurnTool(messages)).toBeUndefined()
  })
})

describe('runningVerb', () => {
  it('opens the turn on a random active verb keyed by its start', () => {
    const verb = runningVerb(VOCABULARY, [user('u1')], 1_730_000_000_000)
    expect(verb).toBe(pickVerb(VOCABULARY.active, '1730000000000'))
  })

  it('switches to a mapped tool verb once that tool starts', () => {
    const messages = [user('u1'), assistant('a1', tool('Read', 't1'))]
    expect(runningVerb(VOCABULARY, messages, 1)).toBe('Reading')
  })

  it('gives an unmapped tool a random active verb keyed by its call', () => {
    const messages = [user('u1'), assistant('a1', tool('mcp__x__y', 'toolu_abc'))]
    expect(runningVerb(VOCABULARY, messages, 1)).toBe(pickVerb(VOCABULARY.active, 'toolu_abc'))
  })

  it('holds the tool verb through the text that follows it', () => {
    const messages = [user('u1'), assistant('a1', tool('Bash', 't1')), assistant('a2')]
    expect(runningVerb(VOCABULARY, messages, 1)).toBe('Running')
  })

  it('stays undefined while the vocabulary is empty, so the row keeps its label', () => {
    expect(runningVerb({ active: [], tools: {} }, [user('u1')], 1)).toBeUndefined()
  })
})

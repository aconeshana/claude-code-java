import { beforeEach, describe, expect, it } from 'vitest'
import type { MirrorFrame, PermissionAsk, PermissionQuestion } from '../api/types'
import { pendingAskFor, pendingInteractions, useApprovals } from './approvals'

const SESSION = 'sess-1'

function askedFrame(id: number, requestId: string, tool: string): MirrorFrame {
  return {
    event: 'permission.asked',
    id,
    data: { session_id: SESSION, request_id: requestId, tool },
  } as MirrorFrame
}

function ask(sessionId: string, requestId: string, questions?: readonly PermissionQuestion[]): PermissionAsk {
  return { session_id: sessionId, request_id: requestId, tool: 'Bash', ...(questions == null ? {} : { questions }) }
}

describe('approvals frame reduction', () => {
  beforeEach(() => {
    useApprovals.setState({ asks: [] })
  })

  it('registers an ask from permission.asked', () => {
    useApprovals.getState().applyFrame(askedFrame(1, 'req-1', 'Bash'))
    expect(useApprovals.getState().asks).toHaveLength(1)
    expect(useApprovals.getState().asks[0]).toMatchObject({ request_id: 'req-1', tool: 'Bash' })
  })

  it('does not duplicate the same request_id', () => {
    useApprovals.getState().applyFrame(askedFrame(1, 'req-1', 'Bash'))
    useApprovals.getState().applyFrame(askedFrame(2, 'req-1', 'Bash'))
    expect(useApprovals.getState().asks).toHaveLength(1)
  })

  it('removes the ask on permission.resolved regardless of who answered', () => {
    useApprovals.getState().applyFrame(askedFrame(1, 'req-1', 'Bash'))
    useApprovals.getState().applyFrame(askedFrame(2, 'req-2', 'Edit'))
    useApprovals.getState().applyFrame({
      event: 'permission.resolved',
      id: 3,
      data: { session_id: SESSION, request_id: 'req-1', origin: 'tui' },
    } as MirrorFrame)
    expect(useApprovals.getState().asks).toHaveLength(1)
    expect(useApprovals.getState().asks[0]).toMatchObject({ request_id: 'req-2' })
  })

  it('ignores non-permission frames', () => {
    useApprovals.getState().applyFrame({
      event: 'output.text', id: 1, data: { session_id: SESSION, content: 'text' },
    } as MirrorFrame)
    expect(useApprovals.getState().asks).toHaveLength(0)
  })
})

describe('pendingAskFor', () => {
  it('serves each session only its own ask', () => {
    const asks = [ask('sess-a', 'req-a'), ask('sess-b', 'req-b')]
    expect(pendingAskFor(asks, 'sess-a')).toMatchObject({ request_id: 'req-a' })
    expect(pendingAskFor(asks, 'sess-b')).toMatchObject({ request_id: 'req-b' })
  })

  // The regression: a `?? asks[0]` fallback used to render another session's
  // approval under the selected session's composer, so opening a fresh session
  // hijacked a pending authorization the user never triggered there.
  it('returns undefined rather than another session\'s ask', () => {
    expect(pendingAskFor([ask('sess-a', 'req-a')], 'sess-fresh')).toBeUndefined()
  })

  it('returns undefined when no session is selected', () => {
    expect(pendingAskFor([ask('sess-a', 'req-a')], null)).toBeUndefined()
  })
})

describe('pendingInteractions', () => {
  it('distinguishes questions from plain approvals', () => {
    const pending = pendingInteractions([
      ask('sess-a', 'req-a'),
      ask('sess-b', 'req-b', [{ question: 'Which?', header: 'Pick', options: [] }]),
    ])
    expect(pending.get('sess-a')).toBe('approval')
    expect(pending.get('sess-b')).toBe('question')
    expect(pending.get('sess-c')).toBeUndefined()
  })

  it('folds several asks for one session into a single entry', () => {
    const pending = pendingInteractions([ask('sess-a', 'req-1'), ask('sess-a', 'req-2')])
    expect(pending.size).toBe(1)
    expect(pending.get('sess-a')).toBe('approval')
  })
})

import { beforeEach, describe, expect, it } from 'vitest'
import type { MirrorFrame } from '../api/types'
import { useApprovals } from './approvals'

const SESSION = 'sess-1'

function askedFrame(id: number, requestId: string, tool: string): MirrorFrame {
  return {
    event: 'permission.asked',
    id,
    data: { session_id: SESSION, request_id: requestId, tool },
  } as MirrorFrame
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

import { beforeEach, describe, expect, it } from 'vitest'
import type { MirrorFrame } from '../api/types'
import { pendingSudoFor, useSudoAsks } from './sudoAsks'

const SESSION = 'sess-1'

function askedFrame(id: number, sessionId: string, requestId: string, command?: string): MirrorFrame {
  return {
    event: 'sudo.asked',
    id,
    data: { session_id: sessionId, request_id: requestId, executable: '/usr/bin/sudo', ...(command == null ? {} : { command }) },
  } as MirrorFrame
}

function resolvedFrame(id: number, sessionId: string, requestId: string, origin: 'tui' | 'web'): MirrorFrame {
  return {
    event: 'sudo.resolved',
    id,
    data: { session_id: sessionId, request_id: requestId, resolution: 'submitted', origin },
  } as MirrorFrame
}

describe('sudo ask frame reduction', () => {
  beforeEach(() => {
    useSudoAsks.setState({ asks: [] })
  })

  it('registers an ask from sudo.asked, without a password field', () => {
    useSudoAsks.getState().applyFrame(askedFrame(1, SESSION, 'req-1', 'sudo systemctl restart x'))
    expect(useSudoAsks.getState().asks).toHaveLength(1)
    expect(useSudoAsks.getState().asks[0]).toMatchObject({
      request_id: 'req-1',
      executable: '/usr/bin/sudo',
      command: 'sudo systemctl restart x',
    })
    expect(useSudoAsks.getState().asks[0]).not.toHaveProperty('password')
  })

  it('does not duplicate the same request_id', () => {
    useSudoAsks.getState().applyFrame(askedFrame(1, SESSION, 'req-1'))
    useSudoAsks.getState().applyFrame(askedFrame(2, SESSION, 'req-1'))
    expect(useSudoAsks.getState().asks).toHaveLength(1)
  })

  it('removes the ask when the terminal answered it first', () => {
    useSudoAsks.getState().applyFrame(askedFrame(1, SESSION, 'req-1'))
    useSudoAsks.getState().applyFrame(resolvedFrame(2, SESSION, 'req-1', 'tui'))
    expect(useSudoAsks.getState().asks).toHaveLength(0)
  })

  it('removes only the resolved ask, leaving another session alone', () => {
    useSudoAsks.getState().applyFrame(askedFrame(1, 'sess-a', 'req-a'))
    useSudoAsks.getState().applyFrame(askedFrame(2, 'sess-b', 'req-b'))
    useSudoAsks.getState().applyFrame(resolvedFrame(3, 'sess-a', 'req-a', 'web'))
    expect(useSudoAsks.getState().asks.map((ask) => ask.request_id)).toEqual(['req-b'])
  })

  it('ignores approval frames: a sudo prompt is not an allow/deny ask', () => {
    useSudoAsks.getState().applyFrame({
      event: 'permission.asked',
      id: 1,
      data: { session_id: SESSION, request_id: 'p-1', tool: 'Bash' },
    } as MirrorFrame)
    expect(useSudoAsks.getState().asks).toHaveLength(0)
  })

  it('scopes the lookup by session and never borrows another session\'s ask', () => {
    useSudoAsks.getState().applyFrame(askedFrame(1, 'sess-b', 'req-b'))
    expect(pendingSudoFor(useSudoAsks.getState().asks, 'sess-a')).toBeUndefined()
    expect(pendingSudoFor(useSudoAsks.getState().asks, 'sess-b')?.request_id).toBe('req-b')
    expect(pendingSudoFor(useSudoAsks.getState().asks, null)).toBeUndefined()
  })
})

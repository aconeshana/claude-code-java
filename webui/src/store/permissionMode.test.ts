import { beforeEach, describe, expect, it } from 'vitest'
import type { MirrorFrame, SessionPermissionMode } from '../api/types'
import { usePermissionMode } from './permissionMode'

const STATE: SessionPermissionMode = {
  current: 'plan',
  modes: [],
  bypass_permissions_available: true,
  bypass_permissions_disabled_by_policy: false,
}

function modeFrame(sessionId: string, mode: SessionPermissionMode | null): MirrorFrame {
  return { event: 'permission.mode', id: 1, data: { session_id: sessionId, permission_mode: mode } } as MirrorFrame
}

describe('permission mode mirror frames', () => {
  beforeEach(() => {
    usePermissionMode.setState({ state: null, sessionId: null })
  })

  it('applies a frame while following the active TUI session', () => {
    usePermissionMode.getState().applyFrame(modeFrame('tui-session', STATE))
    expect(usePermissionMode.getState().state).toEqual(STATE)
  })

  it('applies a frame addressed to the session this store is bound to', () => {
    usePermissionMode.setState({ sessionId: 'sess-a' })
    usePermissionMode.getState().applyFrame(modeFrame('sess-a', STATE))
    expect(usePermissionMode.getState().state).toEqual(STATE)
  })

  it('ignores a frame for a different session', () => {
    usePermissionMode.setState({ sessionId: 'sess-a', state: null })
    usePermissionMode.getState().applyFrame(modeFrame('sess-b', STATE))
    expect(usePermissionMode.getState().state).toBeNull()
  })

  it('ignores a frame of another event kind', () => {
    usePermissionMode.getState().applyFrame({
      event: 'session.idle', id: 2, data: { session_id: 'tui-session' },
    } as MirrorFrame)
    expect(usePermissionMode.getState().state).toBeNull()
  })
})
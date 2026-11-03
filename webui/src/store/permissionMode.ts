/**
 * Per-session permission-mode chip state, over the gateway's
 * /api/session/permission-mode endpoint — the webui counterpart of the
 * TUI Shift+Tab cycle. The addressed session is the conversation the
 * composer is bound to; the store tracks which session its answer belongs
 * to so a fast switch can discard a slower, stale answer (mirrors
 * ../store/sessionContext.ts).
 */
import { create } from 'zustand'
import { fetchPermissionMode, selectPermissionMode } from '../api/client'
import type { MirrorFrame, SessionPermissionMode } from '../api/types'

export interface PermissionModeStore {
  readonly state: SessionPermissionMode | null
  /** The session the current state answer belongs to, null for the active TUI session. */
  readonly sessionId: string | null
  refresh(sessionId?: string | null): Promise<void>
  /** Applies one permission-mode selection; resolves to an error string when rejected. */
  select(mode: string): Promise<string | null>
  /**
   * Applies one `permission.mode` mirror frame — a mode change another surface
   * (the TUI) made on the shared gate. Ignored for a session this store is not
   * bound to, and for a frame the store itself caused (select echoes back).
   */
  applyFrame(frame: MirrorFrame): void
}

export const usePermissionMode = create<PermissionModeStore>((set, get) => ({
  state: null,
  sessionId: null,

  async refresh(sessionId) {
    const target = sessionId ?? null
    if (get().sessionId !== target) set({ sessionId: target })
    try {
      const answer = await fetchPermissionMode(target)
      if (get().sessionId !== target) return
      set({ state: answer.permission_mode })
    } catch {
      if (get().sessionId !== target) return
      set({ state: null })
    }
  },

  async select(mode) {
    const target = get().sessionId
    try {
      const answer = await selectPermissionMode({ session_id: target, mode })
      if (get().sessionId !== target) return null
      set({ state: answer.permission_mode })
      return null
    } catch (failure) {
      return failure instanceof Error ? failure.message : String(failure)
    }
  },

  applyFrame(frame) {
    if (frame.event !== 'permission.mode') return
    // A null sessionId follows the active TUI session; the frame is always
    // addressed to that session, so an unbound store accepts it. A store bound
    // to a specific session takes only its own frames.
    const bound = get().sessionId
    if (bound != null && bound !== frame.data.session_id) return
    set({ state: frame.data.permission_mode })
  },
}))

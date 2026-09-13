/**
 * Per-session model seat + context meter state, over the gateway's
 * /api/session/context endpoint (mirrors dsh's per-session model
 * directory: one shared answer feeding both the composer's model seat
 * and the meter). The addressed session is the conversation the composer
 * is bound to; the store tracks which session its answer belongs to so a
 * fast switch can discard a slower, stale answer.
 *
 * The usage half is refetched after every settled turn (the finalized
 * usage anchor moves then) and after every selection change; it is not
 * interval-polled — the gateway computes it from the same claude-hud
 * token accounting the status line serves, and mid-turn deltas would
 * only jitter the ring.
 */
import { create } from 'zustand'
import { fetchSessionContext, selectSessionContext } from '../api/client'
import type { SessionContextSelectResponse, SessionContextUsage, SessionMetrics, SessionModelSelection } from '../api/types'

export interface SessionContextStore {
  readonly selection: SessionModelSelection | null
  readonly usage: SessionContextUsage | null
  /** The durable whole-session metrics fold (null while unavailable or unmeasured). */
  readonly metrics: SessionMetrics | null
  /** The session the current selection/usage answer belongs to, null for the active TUI session. */
  readonly sessionId: string | null
  refresh(sessionId?: string | null): Promise<void>
  /** Applies one model selection; resolves to an error string when rejected. */
  selectModel(model: string): Promise<string | null>
  /** Applies one effort selection; resolves to an error string when rejected. */
  selectEffort(effort: string): Promise<string | null>
}

export const useSessionContext = create<SessionContextStore>((set, get) => ({
  selection: null,
  usage: null,
  metrics: null,
  sessionId: null,

  async refresh(sessionId) {
    const target = sessionId ?? null
    // Bind the target before the request so a stale in-flight answer from
    // the previously selected session is dropped on arrival. Write only on a
    // real move: zustand always hands subscribers a fresh state object on
    // set, and a no-op write here would re-render every subscriber (and
    // re-fire any effect keyed on this store) for nothing.
    if (get().sessionId !== target) set({ sessionId: target })
    try {
      const answer = await fetchSessionContext(target)
      if (get().sessionId !== target) return
      set({ selection: answer.selection, usage: answer.context, metrics: answer.metrics ?? null })
    } catch {
      if (get().sessionId !== target) return
      // No answer for the addressed session (or the gateway is gone):
      // render no seat. The next refresh re-populates it.
      set({ selection: null, usage: null, metrics: null })
    }
  },

  async selectModel(model) {
    const target = get().sessionId
    try {
      const answer: SessionContextSelectResponse = await selectSessionContext({
        session_id: target, model,
      })
      if (get().sessionId !== target) return null
      set({ selection: answer.selection, usage: answer.context, metrics: answer.metrics ?? null })
      return null
    } catch (failure) {
      return failure instanceof Error ? failure.message : String(failure)
    }
  },

  async selectEffort(effort) {
    const target = get().sessionId
    try {
      const answer: SessionContextSelectResponse = await selectSessionContext({
        session_id: target, effort,
      })
      if (get().sessionId !== target) return null
      set({ selection: answer.selection, usage: answer.context, metrics: answer.metrics ?? null })
      return null
    } catch (failure) {
      return failure instanceof Error ? failure.message : String(failure)
    }
  },
}))

import { create } from 'zustand'
import type { MirrorFrame, PermissionAsk } from '../api/types'

/**
 * Pending permission asks (approval cards).
 *
 * permission.asked registers one ask keyed by its interaction request_id;
 * permission.resolved (fired by whichever endpoint answered — this UI, the
 * TUI, or the IM link) removes it. The respond endpoint answers by the same
 * request_id; a 409 means another endpoint won the first-responder race and
 * the card is already gone by then.
 *
 * The store is process-wide (one mirror stream carries every session's
 * frames), so every read of it is scoped by session id — see pendingAskFor.
 */

export interface ApprovalsStore {
  readonly asks: readonly PermissionAsk[]
  applyFrame(frame: MirrorFrame): void
}

/**
 * What a session is waiting on, mirroring the pending-interaction kinds this
 * gateway can produce. Upstream dsh's `PendingInteractionStatus` also has
 * `plan-review`, which has no wire equivalent here.
 */
export type PendingInteraction = 'approval' | 'question'

/** An ask's kind, off the same reading that picks QuestionCard over ApprovalCard. */
function interactionOf(ask: PermissionAsk): PendingInteraction {
  return (ask.questions?.length ?? 0) > 0 ? 'question' : 'approval'
}

/**
 * The ask `sessionId` owns, or undefined when it has none.
 *
 * There is deliberately no fallback to another session's ask: the card is
 * answered in place, so rendering session B's approval under session A's
 * composer asks the user to authorize a tool they never saw run. "Nothing
 * pending here" is the honest answer for a session that isn't waiting.
 */
export function pendingAskFor(
  asks: readonly PermissionAsk[],
  sessionId: string | null,
): PermissionAsk | undefined {
  if (sessionId == null) return undefined
  return asks.find((ask) => ask.session_id === sessionId)
}

/**
 * Session id → what it is waiting on, for the sidebar's status dots. The first
 * ask per session wins; a session with several queued is still one dot.
 */
export function pendingInteractions(
  asks: readonly PermissionAsk[],
): ReadonlyMap<string, PendingInteraction> {
  const pending = new Map<string, PendingInteraction>()
  for (const ask of asks) {
    if (!pending.has(ask.session_id)) pending.set(ask.session_id, interactionOf(ask))
  }
  return pending
}

export const useApprovals = create<ApprovalsStore>((set) => ({
  asks: [],

  applyFrame(frame: MirrorFrame) {
    if (frame.event === 'permission.asked') {
      const ask = frame.data
      set((state) => ({
        asks: state.asks.some((existing) => existing.request_id === ask.request_id)
          ? state.asks
          : [...state.asks, ask],
      }))
      return
    }
    if (frame.event === 'permission.resolved') {
      const requestId = frame.data.request_id
      set((state) => ({
        asks: state.asks.filter((ask) => ask.request_id !== requestId),
      }))
    }
  },
}))

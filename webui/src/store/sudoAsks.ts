import { create } from 'zustand'
import type { MirrorFrame, SudoAsk } from '../api/types'

/**
 * Pending sudo password asks.
 *
 * sudo.asked registers one ask keyed by its interaction request_id;
 * sudo.resolved (fired by whichever endpoint answered — this UI, the TUI, or a
 * second browser tab) removes it. The respond endpoint answers by the same
 * request_id and a 409 means another observer won the race, so the card is
 * already gone by then.
 *
 * This is deliberately a separate store from approvals: a sudo prompt is not
 * an allow/deny decision over a tool input, and neither store's cards may be
 * answered by the other's endpoint. As with approvals, the store is
 * process-wide (one mirror stream carries every session), so reads are scoped
 * by session id.
 */

export interface SudoAsksStore {
  readonly asks: readonly SudoAsk[]
  applyFrame(frame: MirrorFrame): void
}

/**
 * The sudo ask `sessionId` owns, or undefined when it has none.
 *
 * No fallback to another session's ask, for the same reason approvals has
 * none: a password typed into the wrong session's card authorizes a command
 * the user never saw.
 */
export function pendingSudoFor(
  asks: readonly SudoAsk[],
  sessionId: string | null,
): SudoAsk | undefined {
  if (sessionId == null) return undefined
  return asks.find((ask) => ask.session_id === sessionId)
}

export const useSudoAsks = create<SudoAsksStore>((set) => ({
  asks: [],

  applyFrame(frame: MirrorFrame) {
    if (frame.event === 'sudo.asked') {
      const ask = frame.data
      set((state) => ({
        asks: state.asks.some((existing) => existing.request_id === ask.request_id)
          ? state.asks
          : [...state.asks, ask],
      }))
      return
    }
    if (frame.event === 'sudo.resolved') {
      const requestId = frame.data.request_id
      set((state) => ({
        asks: state.asks.filter((ask) => ask.request_id !== requestId),
      }))
    }
  },
}))

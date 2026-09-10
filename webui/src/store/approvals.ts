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
 */

export interface ApprovalsStore {
  readonly asks: readonly PermissionAsk[]
  applyFrame(frame: MirrorFrame): void
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

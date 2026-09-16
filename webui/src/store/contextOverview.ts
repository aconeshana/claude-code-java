/**
 * The Context Dashboard's data seat: the raw `GET /api/session/context/
 * overview` payload (every live session's timeline head + activity ledger)
 * plus the panel's open flag (dsh-context's `overviewStore`). Fetched on
 * open and re-pulled on a short cadence while open; closed, it costs
 * nothing (the "closed surfaces fetch nothing" contract of the Context
 * tab). `rowsOfOverview` (vendored) re-proves the payload at render.
 */
import { create } from 'zustand'
import { fetchContextOverview } from '../api/client'

export interface ContextOverviewStore {
  readonly open: boolean
  readonly payload: unknown
  readonly loading: boolean
  readonly error: string | null
  setOpen(open: boolean): void
  refresh(): Promise<void>
}

let refreshTicket = 0

export const useContextOverview = create<ContextOverviewStore>((set, get) => ({
  open: false,
  payload: null,
  loading: false,
  error: null,
  setOpen(open) {
    set({ open })
    if (open) void get().refresh()
  },
  async refresh() {
    // Last request wins rather than "skip while loading": a fetch that never
    // settles must not wedge the panel, and a stale response must not
    // overwrite a newer one.
    const ticket = ++refreshTicket
    set({ loading: true })
    try {
      const payload = await fetchContextOverview()
      if (ticket !== refreshTicket) return
      set({ payload, loading: false, error: null })
    } catch (failure: unknown) {
      if (ticket !== refreshTicket) return
      set({ loading: false, error: failure instanceof Error ? failure.message : String(failure) })
    }
  },
}))

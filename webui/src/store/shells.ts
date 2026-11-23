import { create } from 'zustand'
import { fetchShellOutput, fetchShells, stopShell } from '../api/client'
import type { ShellEntry, ShellOutputDetail } from '../api/types'

/**
 * Background shell tasks store (`GET /api/shells`, `GET /api/shells/{id}`,
 * `POST /api/shells/{id}/stop`).
 */
export interface ShellsStore {
  readonly shells: readonly ShellEntry[]
  readonly loaded: boolean
  readonly failed: boolean
  readonly details: Readonly<Record<string, ShellOutputDetail>>
  readonly detailLoading: Readonly<Record<string, boolean>>
  readonly detailErrors: Readonly<Record<string, string | null>>
  refresh(): Promise<void>
  loadDetail(taskId: string, silent?: boolean): Promise<ShellOutputDetail | null>
  stop(taskId: string): Promise<boolean>
  reset(): void
}

export const useShells = create<ShellsStore>((set, get) => ({
  shells: [],
  loaded: false,
  failed: false,
  details: {},
  detailLoading: {},
  detailErrors: {},

  async refresh() {
    try {
      const listing = await fetchShells()
      set({ shells: listing.shells, loaded: true, failed: false })
    } catch {
      set((state) => ({ failed: !state.loaded }))
    }
  },

  async loadDetail(taskId, silent = false) {
    if (!silent) {
      set((state) => ({
        detailLoading: { ...state.detailLoading, [taskId]: true },
        detailErrors: { ...state.detailErrors, [taskId]: null },
      }))
    }
    try {
      const detail = await fetchShellOutput(taskId)
      set((state) => ({
        details: { ...state.details, [taskId]: detail },
        detailLoading: { ...state.detailLoading, [taskId]: false },
        detailErrors: { ...state.detailErrors, [taskId]: null },
      }))
      return detail
    } catch (error) {
      set((state) => ({
        detailLoading: { ...state.detailLoading, [taskId]: false },
        detailErrors: {
          ...state.detailErrors,
          [taskId]: error instanceof Error ? error.message : String(error),
        },
      }))
      return null
    }
  },

  async stop(taskId) {
    try {
      await stopShell(taskId)
      await Promise.all([get().refresh(), get().loadDetail(taskId, true)])
      return true
    } catch {
      return false
    }
  },

  reset() {
    set({
      shells: [],
      loaded: false,
      failed: false,
      details: {},
      detailLoading: {},
      detailErrors: {},
    })
  },
}))

import { create } from 'zustand'
import {
  attachCcConnect,
  cancelCcConnectSetup,
  detachCcConnect,
  fetchCcConnectLogs,
  fetchCcConnectStatus,
  runCcConnectSetup,
} from '../api/client'
import type {
  CcConnectSetupMode,
  CcConnectSetupResult,
  CcConnectSnapshot,
  MirrorFrame,
} from '../api/types'

export interface CcConnectStore {
  readonly snapshot: CcConnectSnapshot | null
  readonly loading: boolean
  readonly busyPids: Readonly<Record<number, boolean>>
  readonly error: string | null
  readonly logsByPid: Readonly<Record<number, readonly string[]>>
  readonly setupRunning: boolean
  readonly setupLogs: readonly string[]
  readonly setupResult: CcConnectSetupResult | null
  refresh(): Promise<void>
  attach(pid: number, sessionId?: string, channel?: string): Promise<void>
  detach(pid: number, sessionId?: string): Promise<void>
  loadLogs(pid: number): Promise<void>
  runSetup(input: {
    readonly mode: CcConnectSetupMode
    readonly appId?: string
    readonly appSecret?: string
    readonly workDir?: string
  }): Promise<CcConnectSetupResult>
  cancelSetup(): Promise<void>
  clearSetupOutput(): void
  applyFrame(frame: MirrorFrame): void
}

function errorMessage(err: unknown): string {
  return err instanceof Error ? err.message : String(err)
}

export const useCcConnect = create<CcConnectStore>((set, get) => ({
  snapshot: null,
  loading: false,
  busyPids: {},
  error: null,
  logsByPid: {},
  setupRunning: false,
  setupLogs: [],
  setupResult: null,

  async refresh() {
    set({ loading: true })
    try {
      const snapshot = await fetchCcConnectStatus()
      set({ snapshot, loading: false, error: null })
    } catch (err) {
      set({ loading: false, error: errorMessage(err) })
    }
  },

  async attach(pid, sessionId, channel) {
    set((state) => ({
      busyPids: { ...state.busyPids, [pid]: true },
      error: null,
    }))
    try {
      const snapshot = await attachCcConnect({
        pid,
        ...(sessionId === undefined ? {} : { sessionId }),
        ...(channel === undefined ? {} : { channel }),
      })
      set((state) => {
        const nextBusy = { ...state.busyPids }
        delete nextBusy[pid]
        return { snapshot, busyPids: nextBusy, error: null }
      })
    } catch (err) {
      set((state) => {
        const nextBusy = { ...state.busyPids }
        delete nextBusy[pid]
        return { busyPids: nextBusy, error: errorMessage(err) }
      })
    }
  },

  async detach(pid, sessionId) {
    set((state) => ({
      busyPids: { ...state.busyPids, [pid]: true },
      error: null,
    }))
    try {
      const snapshot = await detachCcConnect({
        pid,
        ...(sessionId === undefined ? {} : { sessionId }),
      })
      set((state) => {
        const nextBusy = { ...state.busyPids }
        delete nextBusy[pid]
        return { snapshot, busyPids: nextBusy, error: null }
      })
    } catch (err) {
      set((state) => {
        const nextBusy = { ...state.busyPids }
        delete nextBusy[pid]
        return { busyPids: nextBusy, error: errorMessage(err) }
      })
    }
  },

  async loadLogs(pid) {
    try {
      const response = await fetchCcConnectLogs(pid, 200)
      set((state) => ({
        logsByPid: { ...state.logsByPid, [pid]: response.lines },
      }))
    } catch (err) {
      set({ error: errorMessage(err) })
    }
  },

  async runSetup(input) {
    set({ setupRunning: true, setupLogs: [], setupResult: null, error: null })
    try {
      const result = await runCcConnectSetup(input, (line) => {
        set((state) => ({ setupLogs: [...state.setupLogs, line] }))
      })
      set({ setupRunning: false, setupResult: result })
      void get().refresh()
      return result
    } catch (err) {
      const message = errorMessage(err)
      const failed: CcConnectSetupResult = { ok: false, channel: '', message }
      set({ setupRunning: false, setupResult: failed, error: message })
      return failed
    }
  },

  async cancelSetup() {
    try {
      await cancelCcConnectSetup()
    } catch { /* ignore */ }
    set({ setupRunning: false })
  },

  clearSetupOutput() {
    set({ setupLogs: [], setupResult: null })
  },

  applyFrame(frame) {
    if (frame.event === 'cc_connect.changed' || frame.event === 'sessions.changed') {
      void get().refresh()
    }
  },
}))

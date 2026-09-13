import { create } from 'zustand'
import { closeHeadlessSession, fetchCatalog, openHeadlessSession } from '../api/client'
import type { CatalogProject } from '../api/types'
import { useConversations } from './conversations'

/**
 * Session catalog + selection state.
 *
 * The catalog tree (projects → sessions) mirrors the TUI /resume picker;
 * `active` marks the TUI's current session and `headless_open` marks the
 * gateway's parallel headless sessions. Selecting a TUI-history session that
 * is not open re-opens it as a headless session (snapshot-restored), which
 * is the web flow's equivalent of /resume.
 */

export interface SessionsStore {
  readonly projects: readonly CatalogProject[]
  readonly loading: boolean
  readonly error: string | null
  /** The conversation the UI is currently showing. */
  readonly selectedSessionId: string | null
  /** Sessions fetched per project so far (null = the gateway's default page). */
  readonly perPage: number | null
  refresh(): Promise<void>
  /** Refetches with a larger per-project page (the "load more" affordance). */
  growPerPage(next: number): Promise<void>
  select(sessionId: string): Promise<void>
  selectActiveOrFirst(): Promise<void>
  openSession(sessionId: string, projectPath: string | null): Promise<void>
  createSession(projectPath: string | null): Promise<void>
  closeSession(sessionId: string): Promise<void>
}

export const useSessions = create<SessionsStore>((set, get) => ({
  projects: [],
  loading: false,
  error: null,
  selectedSessionId: null,
  perPage: null,

  async refresh() {
    set({ loading: true })
    try {
      const catalog = await fetchCatalog(get().perPage ?? undefined)
      set({ projects: catalog.projects, loading: false, error: null })
    } catch (failure) {
      set({ loading: false, error: messageOf(failure) })
    }
  },

  async growPerPage(next) {
    if (next <= (get().perPage ?? 0)) return
    set({ perPage: next, loading: true })
    try {
      const catalog = await fetchCatalog(next)
      set({ projects: catalog.projects, loading: false, error: null })
    } catch (failure) {
      set({ loading: false, error: messageOf(failure) })
    }
  },

  async select(sessionId: string) {
    if (get().selectedSessionId === sessionId) return
    set({ selectedSessionId: sessionId })
    await useConversations.getState().loadSnapshot(sessionId).catch((failure: unknown) => {
      // Snapshot failure leaves the conversation live-only; mirror frames
      // keep streaming in the meantime.
      console.error('snapshot load failed', failure)
    })
  },

  async selectActiveOrFirst() {
    const { projects } = get()
    const all = projects.flatMap((project) => project.sessions)
    if (all.length === 0) return
    const target = all.find((session) => session.active) ?? all[0]
    await get().select(target.id)
  },

  async openSession(sessionId: string, projectPath: string | null) {
    try {
      await openHeadlessSession(sessionId, projectPath)
      await get().refresh()
      await get().select(sessionId)
    } catch (failure) {
      set({ error: messageOf(failure) })
    }
  },

  async createSession(projectPath: string | null) {
    try {
      const opened = await openHeadlessSession(null, projectPath)
      await get().refresh()
      await get().select(opened.session_id)
    } catch (failure) {
      set({ error: messageOf(failure) })
    }
  },

  async closeSession(sessionId: string) {
    try {
      await closeHeadlessSession(sessionId)
      await get().refresh()
      if (get().selectedSessionId === sessionId) {
        set({ selectedSessionId: null })
        await get().selectActiveOrFirst()
      }
    } catch (failure) {
      set({ error: messageOf(failure) })
    }
  },
}))

function messageOf(failure: unknown): string {
  return failure instanceof Error ? failure.message : String(failure)
}

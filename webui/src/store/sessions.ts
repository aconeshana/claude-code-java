import { create } from 'zustand'
import {
  archiveSession as archiveSessionApi, closeHeadlessSession, deleteSession as deleteSessionApi,
  fetchCatalog, forkSession as forkSessionApi, openHeadlessSession, renameSession as renameSessionApi,
} from '../api/client'
import type { CatalogProject, CatalogSession } from '../api/types'
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
  renameSession(sessionId: string, title: string): Promise<void>
  forkSession(sessionId: string, title?: string): Promise<void>
  archiveSession(sessionId: string): Promise<void>
  deleteSession(sessionId: string): Promise<void>
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
    // The guard exists to swallow redundant snapshot reloads when the same
    // row is clicked again — NOT to swallow a click that would repair a
    // broken state. Keying it on the id alone strands the UI whenever the
    // id was latched without its snapshot landing (a fork whose row had not
    // appeared yet, or a snapshot fetch that failed): the sidebar highlights
    // nothing, the chat pane shows something else, and a re-click is a
    // no-op. Requiring a loaded conversation makes the repair click work.
    const alreadyLoaded = useConversations.getState().conversations[sessionId] != null
    if (get().selectedSessionId === sessionId && alreadyLoaded) return
    set({ selectedSessionId: sessionId })
    await useConversations.getState().loadSnapshot(sessionId).catch((failure: unknown) => {
      // Snapshot failure leaves the conversation live-only; mirror frames
      // keep streaming in the meantime. The selection stays latched, but the
      // guard above lets a later click retry the load.
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

  async renameSession(sessionId: string, title: string) {
    try {
      const renamed = await renameSessionApi(sessionId, title)
      // The endpoint returns the authoritative title, so the one changed
      // cell is patched in place. A full refetch here would re-enrich every
      // stored transcript server-side (a 64 KiB head+tail read each) and
      // blank the sidebar behind `loading` for a single-row edit.
      set((state) => ({
        projects: mapSession(state.projects, sessionId,
          (session) => ({ ...session, custom_title: renamed.custom_title })),
      }))
    } catch (failure) {
      set({ error: messageOf(failure) })
    }
  },

  async forkSession(sessionId: string, title?: string) {
    try {
      const forked = await forkSessionApi(sessionId, title)
      // A fork adds a row the client cannot synthesize faithfully (the
      // gateway owns its summary, counts and mtime), so this is the one
      // mutation that still refetches. Selecting from the fork response
      // rather than from the refreshed tree keeps the chat pane correct
      // even when a refresh races the child .jsonl write and misses it;
      // `select` no longer depends on the row being present.
      await get().refresh()
      await get().select(forked.session_id)
    } catch (failure) {
      set({ error: messageOf(failure) })
    }
  },

  async archiveSession(sessionId: string) {
    try {
      await archiveSessionApi(sessionId)
      // Archiving is a one-way hide from every catalog view, so dropping the
      // row locally is exactly what a refetch would have shown.
      set((state) => ({ projects: removeSession(state.projects, sessionId) }))
      if (get().selectedSessionId === sessionId) {
        set({ selectedSessionId: null })
        await get().selectActiveOrFirst()
      }
    } catch (failure) {
      set({ error: messageOf(failure) })
    }
  },

  async deleteSession(sessionId: string) {
    try {
      const removed = await deleteSessionApi(sessionId)
      // Only drop the row when the gateway confirms the delete; a false
      // `deleted` would otherwise hide a session that still exists.
      if (removed.deleted) {
        set((state) => ({ projects: removeSession(state.projects, sessionId) }))
      }
      if (get().selectedSessionId === sessionId) {
        set({ selectedSessionId: null })
        await get().selectActiveOrFirst()
      }
    } catch (failure) {
      set({ error: messageOf(failure) })
    }
  },
}))

/** Replaces one session row in the catalog tree, leaving every other untouched. */
function mapSession(
  projects: readonly CatalogProject[], sessionId: string,
  edit: (session: CatalogSession) => CatalogSession,
): readonly CatalogProject[] {
  return projects.map((project) => (
    project.sessions.some((session) => session.id === sessionId)
      ? {
        ...project,
        sessions: project.sessions.map(
          (session) => (session.id === sessionId ? edit(session) : session)),
      }
      : project
  ))
}

/** Drops one session row and keeps the owning project's count consistent. */
function removeSession(
  projects: readonly CatalogProject[], sessionId: string,
): readonly CatalogProject[] {
  return projects.map((project) => {
    if (!project.sessions.some((session) => session.id === sessionId)) return project
    const sessions = project.sessions.filter((session) => session.id !== sessionId)
    return {
      ...project,
      sessions,
      // The count is the project's total, which may exceed the fetched page;
      // decrementing keeps it truthful without a refetch.
      session_count: Math.max(project.session_count - 1, sessions.length),
    }
  })
}

function messageOf(failure: unknown): string {
  return failure instanceof Error ? failure.message : String(failure)
}

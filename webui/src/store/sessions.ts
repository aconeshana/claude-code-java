import { create } from 'zustand'
import {
  archiveSession as archiveSessionApi, closeHeadlessSession, deleteSession as deleteSessionApi,
  fetchCatalog, forkSession as forkSessionApi, openHeadlessSession, renameSession as renameSessionApi,
} from '../api/client'
import type { CatalogProject, CatalogSession, MirrorFrame } from '../api/types'
import { useConversations } from './conversations'

/**
 * Session catalog + selection state.
 *
 * The catalog tree (projects → sessions) mirrors the TUI /resume picker;
 * `active` marks the TUI's current session and `headless_open` marks the
 * gateway's parallel headless sessions. Selecting a TUI-history session that
 * is not open re-opens it as a headless session (snapshot-restored), which
 * is the web flow's equivalent of /resume.
 *
 * Until the user picks a session here, the view follows the TUI: a
 * `session.activated` frame with LOCAL origin (a /resume or /clear on the
 * terminal side) moves the selection along with it. The first deliberate
 * choice in this client ends that — see `followActive`.
 */

/**
 * One session's live activity, as the sidebar's status dot needs it.
 *
 * `running` is a turn in flight. `completed` is upstream's `completionUnread`:
 * a session that finished running while you were not looking at it — the green
 * "come see the result" reminder — cleared the moment the session is selected.
 * Both are observed from mirror frames rather than derived from the catalog,
 * because a stateless catalog pull cannot see the running→stopped transition
 * that defines `completed`.
 */
export interface SessionActivity {
  readonly running: boolean
  readonly completed: boolean
}

export interface SessionsStore {
  readonly projects: readonly CatalogProject[]
  readonly loading: boolean
  readonly error: string | null
  /**
   * Live per-session turn state, keyed by session id. Carries ids that are not
   * in the catalog (a session can start running before its row lands).
   */
  readonly activity: Readonly<Record<string, SessionActivity>>
  /** The conversation the UI is currently showing. */
  readonly selectedSessionId: string | null
  /** Sessions fetched per project so far (null = the gateway's default page). */
  readonly perPage: number | null
  /**
   * Whether TUI-side session switches still move this client's selection.
   * True until the user selects a session here; see `selectByUser`.
   */
  readonly followActive: boolean
  refresh(): Promise<void>
  /** Refetches with a larger per-project page (the "load more" affordance). */
  growPerPage(next: number): Promise<void>
  select(sessionId: string): Promise<void>
  /** `select` for a deliberate choice in this client; stops following the TUI. */
  selectByUser(sessionId: string): Promise<void>
  selectActiveOrFirst(): Promise<void>
  applyFrame(frame: MirrorFrame): void
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
  activity: {},
  selectedSessionId: null,
  perPage: null,
  followActive: true,

  async refresh() {
    set({ loading: true })
    try {
      const catalog = await fetchCatalog(get().perPage ?? undefined)
      // The poll replaces `projects` wholesale, so the local activity map must
      // survive it. What the catalog is allowed to change: seed `running` for
      // a session this client has never observed (a page opened mid-turn). It
      // may never lower a running flag the stream reported, nor clear a
      // completed reminder — the poll is a lagging snapshot, and treating its
      // silence as "stopped" would flicker the dot off every 10 seconds.
      set((state) => ({
        projects: catalog.projects,
        activity: seedRunning(state.activity, catalog.projects),
        loading: false,
        error: null,
      }))
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
    // Opening a session is the "I have seen it" gesture, so its completed
    // reminder clears here — BEFORE the redundant-click guard below, which
    // would otherwise swallow the acknowledgement for an already-loaded
    // session (the common case). Upstream's isMain clears it the same way.
    const cleared = clearCompleted(get().activity, sessionId)
    if (get().selectedSessionId === sessionId && alreadyLoaded) {
      if (cleared !== get().activity) set({ activity: cleared })
      return
    }
    set({ selectedSessionId: sessionId, activity: cleared })
    await useConversations.getState().loadSnapshot(sessionId).catch((failure: unknown) => {
      // Snapshot failure leaves the conversation live-only; mirror frames
      // keep streaming in the meantime. The selection stays latched, but the
      // guard above lets a later click retry the load.
      console.error('snapshot load failed', failure)
    })
  },

  async selectByUser(sessionId: string) {
    set({ followActive: false })
    await get().select(sessionId)
  },

  async selectActiveOrFirst() {
    const { projects } = get()
    const all = projects.flatMap((project) => project.sessions)
    if (all.length === 0) return
    const target = all.find((session) => session.active) ?? all[0]
    await get().select(target.id)
  },

  applyFrame(frame: MirrorFrame) {
    // Turn frames move the row's status dot. They arrive for every session the
    // gateway follows (the mirror stream is not session-filtered), which is
    // what lets a row light up while a different session is on screen.
    if (applyTurnActivity(get, set, frame)) return
    if (frame.event !== 'session.activated') return
    // REMOTE is this client's own open/create echoing back; those paths have
    // already selected, and re-selecting would race their in-flight `select`
    // and undo the user's choice right after they made it.
    // Lowercase: the gateway sends `event.origin().name().toLowerCase()`, so
    // the wire value is `local`/`remote`. An uppercase comparison here matched
    // nothing and silently disabled every TUI-side switch (the frames were
    // accepted, reduced, and then dropped on this line).
    if (frame.data.origin !== 'local') return
    if (!get().followActive) return
    const target = frame.data.session_id
    if (target === '' || target === get().selectedSessionId) return
    // A session the TUI just switched to may be brand new, so refresh first to
    // pull in its row; selecting regardless keeps the chat pane correct even
    // if the catalog has not caught up, since it reads the snapshot endpoint.
    void get().refresh().finally(() => { void get().select(target) })
  },

  async openSession(sessionId: string, projectPath: string | null) {
    try {
      // Opening and creating are deliberate choices too, so they take the view
      // off the TUI just as a sidebar click does.
      set({ followActive: false })
      await openHeadlessSession(sessionId, projectPath)
      await get().refresh()
      await get().select(sessionId)
    } catch (failure) {
      set({ error: messageOf(failure) })
    }
  },

  async createSession(projectPath: string | null) {
    try {
      set({ followActive: false })
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

/** The idle activity entry, reused so untouched sessions compare by identity. */
const IDLE: SessionActivity = { running: false, completed: false }

/**
 * Folds one mirror frame into the activity map.
 *
 * A single frame event is the unit here, so this reports whether it consumed
 * the frame rather than leaving the caller to re-test the event name.
 *
 * `turn.started` opens a turn and drops any standing reminder (a new run
 * supersedes "come look at the last one"). The three stop events are treated
 * as one trigger deliberately: `running` must fall on whichever arrives, not
 * only on the one a given terminal path happens to publish.
 *
 * The stop transition sets `completed` only for a session that is NOT the one
 * on screen — upstream's `isMain` rule, "running finished while not
 * selected". Looking at the session you already had open is not news.
 */
function applyTurnActivity(
  get: () => SessionsStore, set: (partial: Partial<SessionsStore>) => void, frame: MirrorFrame,
): boolean {
  const sessionId = frame.data.session_id
  if (typeof sessionId !== 'string' || sessionId === '') return false
  const previous = get().activity[sessionId] ?? IDLE
  let next: SessionActivity
  if (frame.event === 'turn.started') {
    next = { running: true, completed: false }
  } else if (frame.event === 'turn.completed' || frame.event === 'turn.cancelled'
    || frame.event === 'session.idle') {
    if (!previous.running) return true
    next = {
      running: false,
      completed: sessionId !== get().selectedSessionId,
    }
  } else {
    return false
  }
  set({ activity: { ...get().activity, [sessionId]: next } })
  return true
}

/** Clears one session's completed reminder, returning the map unchanged when absent. */
function clearCompleted(
  activity: Readonly<Record<string, SessionActivity>>, sessionId: string,
): Readonly<Record<string, SessionActivity>> {
  const entry = activity[sessionId]
  if (entry == null || !entry.completed) return activity
  return { ...activity, [sessionId]: { running: entry.running, completed: false } }
}

/**
 * Seeds `running` for sessions this client has never observed, from catalog
 * rows. Only an absent entry is filled: a session the stream has already
 * reported must keep the stream's value, so a lagging poll can neither start
 * nor stop a dot. Completed reminders are never touched.
 */
function seedRunning(
  activity: Readonly<Record<string, SessionActivity>>,
  projects: readonly CatalogProject[],
): Readonly<Record<string, SessionActivity>> {
  let next: Record<string, SessionActivity> | null = null
  for (const project of projects) {
    for (const session of project.sessions) {
      if (session.running !== true || activity[session.id] != null) continue
      next ??= { ...activity }
      next[session.id] = { running: true, completed: false }
    }
  }
  return next ?? activity
}

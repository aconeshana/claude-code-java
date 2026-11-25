import { create } from 'zustand'
import type { CatalogProject, CatalogSession } from '../api/types'

export const WORKSPACE_PREFS_STORAGE_KEY = 'poco.sidebar-workspace-prefs.v1'

export type SidebarViewMode = 'grouped' | 'flat'

interface PersistedWorkspacePrefs {
  viewMode: SidebarViewMode
  pinnedSessionIds: string[]
  addedWorkspaces: string[]
  hiddenProjectPaths: string[]
  activeHeroWorkspace: string | null
}

export interface PinnedSessionEntry {
  session: CatalogSession
  projectPath: string
  projectName: string
}

export interface SidebarSearchResult extends PinnedSessionEntry {
  snippet: string | null
}

export interface SidebarFilterResult {
  pinned: PinnedSessionEntry[]
  projects: CatalogProject[]
  searchResults: SidebarSearchResult[]
  totalMatchedSessions: number
}

function readPersisted(): PersistedWorkspacePrefs {
  const empty: PersistedWorkspacePrefs = {
    viewMode: 'grouped',
    pinnedSessionIds: [],
    addedWorkspaces: [],
    hiddenProjectPaths: [],
    activeHeroWorkspace: null,
  }
  if (typeof window === 'undefined') {
    return empty
  }
  try {
    const raw = window.localStorage.getItem(WORKSPACE_PREFS_STORAGE_KEY)
    if (!raw) return empty
    const parsed = JSON.parse(raw) as Partial<PersistedWorkspacePrefs>
    const viewMode: SidebarViewMode = parsed.viewMode === 'flat' ? 'flat' : 'grouped'
    const pinnedSessionIds = Array.isArray(parsed.pinnedSessionIds)
      ? parsed.pinnedSessionIds.filter((id): id is string => typeof id === 'string' && id.trim() !== '')
      : []
    const addedWorkspaces = Array.isArray(parsed.addedWorkspaces)
      ? parsed.addedWorkspaces.filter((p): p is string => typeof p === 'string' && p.trim() !== '')
      : []
    const hiddenProjectPaths = Array.isArray(parsed.hiddenProjectPaths)
      ? parsed.hiddenProjectPaths.filter((p): p is string => typeof p === 'string' && p.trim() !== '')
      : []
    const activeHeroWorkspace = typeof parsed.activeHeroWorkspace === 'string' && parsed.activeHeroWorkspace.trim() !== ''
      ? parsed.activeHeroWorkspace
      : null
    return { viewMode, pinnedSessionIds, addedWorkspaces, hiddenProjectPaths, activeHeroWorkspace }
  } catch {
    return empty
  }
}

function writePersisted(data: PersistedWorkspacePrefs): void {
  if (typeof window === 'undefined') return
  try {
    window.localStorage.setItem(WORKSPACE_PREFS_STORAGE_KEY, JSON.stringify(data))
  } catch {
    // Ignore storage quota or private-mode failures.
  }
}

interface WorkspacePrefsState extends PersistedWorkspacePrefs {
  setViewMode: (mode: SidebarViewMode) => void
  togglePin: (sessionId: string) => boolean
  isPinned: (sessionId: string) => boolean
  addWorkspace: (path: string) => void
  removeWorkspace: (path: string) => void
  hideProject: (path: string) => void
  unhideProject: (path: string) => void
  setActiveHeroWorkspace: (path: string | null) => void
  reset: () => void
}

const initial = readPersisted()

function persistState(state: PersistedWorkspacePrefs): void {
  writePersisted({
    viewMode: state.viewMode,
    pinnedSessionIds: state.pinnedSessionIds,
    addedWorkspaces: state.addedWorkspaces,
    hiddenProjectPaths: state.hiddenProjectPaths,
    activeHeroWorkspace: state.activeHeroWorkspace,
  })
}

export const useWorkspacePrefs = create<WorkspacePrefsState>((set, get) => ({
  viewMode: initial.viewMode,
  pinnedSessionIds: initial.pinnedSessionIds,
  addedWorkspaces: initial.addedWorkspaces,
  hiddenProjectPaths: initial.hiddenProjectPaths,
  activeHeroWorkspace: initial.activeHeroWorkspace,

  setViewMode: (mode) => {
    set((state) => {
      persistState({ ...state, viewMode: mode })
      return { viewMode: mode }
    })
  },

  togglePin: (sessionId) => {
    const trimmed = sessionId.trim()
    if (!trimmed) return false
    const current = get().pinnedSessionIds
    const exists = current.includes(trimmed)
    const nextPinned = exists
      ? current.filter((id) => id !== trimmed)
      : [trimmed, ...current]
    set((state) => {
      persistState({ ...state, pinnedSessionIds: nextPinned })
      return { pinnedSessionIds: nextPinned }
    })
    return !exists
  },

  isPinned: (sessionId) => get().pinnedSessionIds.includes(sessionId),

  addWorkspace: (path) => {
    const trimmed = path.trim()
    if (!trimmed) return
    set((state) => {
      const nextAdded = state.addedWorkspaces.includes(trimmed)
        ? state.addedWorkspaces
        : [trimmed, ...state.addedWorkspaces]
      const nextHidden = state.hiddenProjectPaths.filter((p) => p !== trimmed)
      persistState({
        ...state,
        addedWorkspaces: nextAdded,
        hiddenProjectPaths: nextHidden,
        activeHeroWorkspace: trimmed,
      })
      return { addedWorkspaces: nextAdded, hiddenProjectPaths: nextHidden, activeHeroWorkspace: trimmed }
    })
  },

  removeWorkspace: (path) => {
    const trimmed = path.trim()
    if (!trimmed) return
    set((state) => {
      const nextAdded = state.addedWorkspaces.filter((p) => p !== trimmed)
      const nextActive = state.activeHeroWorkspace === trimmed ? null : state.activeHeroWorkspace
      persistState({
        ...state,
        addedWorkspaces: nextAdded,
        activeHeroWorkspace: nextActive,
      })
      return { addedWorkspaces: nextAdded, activeHeroWorkspace: nextActive }
    })
  },

  hideProject: (path) => {
    const trimmed = path.trim()
    if (!trimmed) return
    set((state) => {
      const nextHidden = state.hiddenProjectPaths.includes(trimmed)
        ? state.hiddenProjectPaths
        : [trimmed, ...state.hiddenProjectPaths]
      const nextAdded = state.addedWorkspaces.filter((p) => p !== trimmed)
      const nextActive = state.activeHeroWorkspace === trimmed ? null : state.activeHeroWorkspace
      persistState({
        ...state,
        addedWorkspaces: nextAdded,
        hiddenProjectPaths: nextHidden,
        activeHeroWorkspace: nextActive,
      })
      return { addedWorkspaces: nextAdded, hiddenProjectPaths: nextHidden, activeHeroWorkspace: nextActive }
    })
  },

  unhideProject: (path) => {
    const trimmed = path.trim()
    if (!trimmed) return
    set((state) => {
      const nextHidden = state.hiddenProjectPaths.filter((p) => p !== trimmed)
      persistState({ ...state, hiddenProjectPaths: nextHidden })
      return { hiddenProjectPaths: nextHidden }
    })
  },

  setActiveHeroWorkspace: (path) => {
    const next = path != null && path.trim() !== '' ? path.trim() : null
    set((state) => {
      persistState({ ...state, activeHeroWorkspace: next })
      return { activeHeroWorkspace: next }
    })
  },

  reset: () => {
    const empty: PersistedWorkspacePrefs = {
      viewMode: 'grouped',
      pinnedSessionIds: [],
      addedWorkspaces: [],
      hiddenProjectPaths: [],
      activeHeroWorkspace: null,
    }
    writePersisted(empty)
    set(empty)
  },
}))

function matchSessionWithSnippet(
  session: CatalogSession,
  project: CatalogProject,
  needle: string,
): { matched: boolean; snippet: string | null } {
  if (needle === '') return { matched: true, snippet: null }

  const snippetCandidates = [
    session.first_prompt,
    session.summary,
    session.git_branch ? `branch: ${session.git_branch}` : null,
    session.custom_title,
    session.id,
  ]

  for (const field of snippetCandidates) {
    if (field && field.toLowerCase().includes(needle)) {
      return { matched: true, snippet: field }
    }
  }

  if (
    project.project_name.toLowerCase().includes(needle)
    || project.project_path.toLowerCase().includes(needle)
  ) {
    const fallback = session.summary ?? session.first_prompt ?? (session.git_branch ? `branch: ${session.git_branch}` : null)
    return { matched: true, snippet: fallback }
  }

  return { matched: false, snippet: null }
}

export function filterSidebarProjects(
  projects: readonly CatalogProject[],
  options: {
    query: string
    pinnedIds: readonly string[]
  },
): SidebarFilterResult {
  const needle = options.query.trim().toLowerCase()

  const filteredProjects: CatalogProject[] = []
  const searchResults: SidebarSearchResult[] = []
  const sessionLookup = new Map<string, PinnedSessionEntry>()
  let totalMatchedSessions = 0

  for (const project of projects) {
    const matchedSessions: CatalogSession[] = []
    for (const session of project.sessions) {
      const { matched, snippet } = matchSessionWithSnippet(session, project, needle)
      if (!matched) continue
      matchedSessions.push(session)
      if (needle !== '') {
        searchResults.push({
          session,
          projectPath: project.project_path,
          projectName: project.project_name,
          snippet,
        })
      }
    }

    if (needle !== '' && matchedSessions.length === 0) {
      continue
    }

    const nextProject: CatalogProject = needle === ''
      ? project
      : {
          ...project,
          sessions: matchedSessions,
          session_count: matchedSessions.length,
        }

    filteredProjects.push(nextProject)
    totalMatchedSessions += matchedSessions.length

    for (const session of matchedSessions) {
      sessionLookup.set(session.id, {
        session,
        projectPath: project.project_path,
        projectName: project.project_name,
      })
    }
  }

  const pinned: PinnedSessionEntry[] = []
  for (const pinnedId of options.pinnedIds) {
    const found = sessionLookup.get(pinnedId)
    if (found) pinned.push(found)
  }

  return {
    pinned,
    projects: filteredProjects,
    searchResults,
    totalMatchedSessions,
  }
}

function sessionModifiedEpoch(session: CatalogSession): number {
  return Date.parse(session.modified_at || '') || 0
}

/**
 * Sorts sidebar projects and their sessions for display:
 * - Any project currently hosting a discovered live TUI (`session.tui_attached`)
 *   is temporarily floated to the top of the sidebar (no localStorage mutation,
 *   so it returns to its normal position as soon as the TUI exits).
 * - Within each project, live `tui_attached` sessions float to the top of the
 *   group so they are never hidden behind the per-project 5-row fold.
 */
export function sortSidebarProjects(
  projects: readonly CatalogProject[],
  selectedProjectPath?: string | null,
): CatalogProject[] {
  const normalized = projects.map((project) => {
    const hasTui = project.sessions.some((session) => session.tui_attached === true)
    if (!hasTui) {
      return { project, hasTui: false, latestTuiEpoch: 0 }
    }
    const tuiSessions: CatalogSession[] = []
    const otherSessions: CatalogSession[] = []
    let latestTuiEpoch = 0
    for (const session of project.sessions) {
      if (session.tui_attached === true) {
        tuiSessions.push(session)
        const epoch = sessionModifiedEpoch(session)
        if (epoch > latestTuiEpoch) latestTuiEpoch = epoch
      } else {
        otherSessions.push(session)
      }
    }
    const sessions = [...tuiSessions, ...otherSessions]
    return {
      project: { ...project, sessions },
      hasTui: true,
      latestTuiEpoch,
    }
  })

  normalized.sort((a, b) => {
    if (a.hasTui !== b.hasTui) return a.hasTui ? -1 : 1
    const aCurrent = selectedProjectPath != null && a.project.project_path === selectedProjectPath
    const bCurrent = selectedProjectPath != null && b.project.project_path === selectedProjectPath
    if (aCurrent !== bCurrent) return aCurrent ? -1 : 1
    if (a.hasTui && b.hasTui && a.latestTuiEpoch !== b.latestTuiEpoch) {
      return b.latestTuiEpoch - a.latestTuiEpoch
    }
    return a.project.project_name.localeCompare(b.project.project_name)
  })

  return normalized.map((entry) => entry.project)
}

/**
 * Sorts flat-view sidebar entries with live `tui_attached` sessions temporarily
 * floated to the top, then by most recently modified descending.
 */
export function sortFlatSidebarEntries<T extends { readonly session: CatalogSession }>(
  entries: readonly T[],
): T[] {
  return [...entries].sort((a, b) => {
    const aTui = a.session.tui_attached === true
    const bTui = b.session.tui_attached === true
    if (aTui !== bTui) return aTui ? -1 : 1
    return sessionModifiedEpoch(b.session) - sessionModifiedEpoch(a.session)
  })
}

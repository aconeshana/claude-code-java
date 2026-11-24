import { create } from 'zustand'
import type { CatalogProject, CatalogSession } from '../api/types'

export const WORKSPACE_PREFS_STORAGE_KEY = 'poco.sidebar-workspace-prefs.v1'

export type SidebarViewMode = 'grouped' | 'flat'

interface PersistedWorkspacePrefs {
  viewMode: SidebarViewMode
  pinnedSessionIds: string[]
  addedWorkspaces: string[]
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
    const activeHeroWorkspace = typeof parsed.activeHeroWorkspace === 'string' && parsed.activeHeroWorkspace.trim() !== ''
      ? parsed.activeHeroWorkspace
      : null
    return { viewMode, pinnedSessionIds, addedWorkspaces, activeHeroWorkspace }
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
  setActiveHeroWorkspace: (path: string | null) => void
  reset: () => void
}

const initial = readPersisted()

export const useWorkspacePrefs = create<WorkspacePrefsState>((set, get) => ({
  viewMode: initial.viewMode,
  pinnedSessionIds: initial.pinnedSessionIds,
  addedWorkspaces: initial.addedWorkspaces,
  activeHeroWorkspace: initial.activeHeroWorkspace,

  setViewMode: (mode) => {
    set((state) => {
      writePersisted({
        viewMode: mode,
        pinnedSessionIds: state.pinnedSessionIds,
        addedWorkspaces: state.addedWorkspaces,
        activeHeroWorkspace: state.activeHeroWorkspace,
      })
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
      writePersisted({
        viewMode: state.viewMode,
        pinnedSessionIds: nextPinned,
        addedWorkspaces: state.addedWorkspaces,
        activeHeroWorkspace: state.activeHeroWorkspace,
      })
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
      writePersisted({
        viewMode: state.viewMode,
        pinnedSessionIds: state.pinnedSessionIds,
        addedWorkspaces: nextAdded,
        activeHeroWorkspace: trimmed,
      })
      return { addedWorkspaces: nextAdded, activeHeroWorkspace: trimmed }
    })
  },

  setActiveHeroWorkspace: (path) => {
    const next = path != null && path.trim() !== '' ? path.trim() : null
    set((state) => {
      writePersisted({
        viewMode: state.viewMode,
        pinnedSessionIds: state.pinnedSessionIds,
        addedWorkspaces: state.addedWorkspaces,
        activeHeroWorkspace: next,
      })
      return { activeHeroWorkspace: next }
    })
  },

  reset: () => {
    writePersisted({
      viewMode: 'grouped',
      pinnedSessionIds: [],
      addedWorkspaces: [],
      activeHeroWorkspace: null,
    })
    set({
      viewMode: 'grouped',
      pinnedSessionIds: [],
      addedWorkspaces: [],
      activeHeroWorkspace: null,
    })
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

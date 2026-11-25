import { beforeEach, describe, expect, it } from 'vitest'
import type { CatalogProject, CatalogSession } from '../api/types'
import {
  filterSidebarProjects,
  sortFlatSidebarEntries,
  sortSidebarProjects,
  useWorkspacePrefs,
} from './workspacePrefs'

function makeSession(overrides: Partial<CatalogSession> & { id: string }): CatalogSession {
  return {
    id: overrides.id,
    summary: overrides.summary ?? null,
    custom_title: overrides.custom_title ?? null,
    first_prompt: overrides.first_prompt ?? null,
    git_branch: overrides.git_branch ?? null,
    cwd: overrides.cwd ?? '/repo/alpha',
    modified_at: overrides.modified_at ?? new Date().toISOString(),
    message_count: overrides.message_count ?? 1,
    active: overrides.active ?? false,
    interactive: overrides.interactive ?? false,
    headless_open: overrides.headless_open ?? false,
  }
}

const PROJECTS: CatalogProject[] = [
  {
    project_path: '/repo/alpha',
    project_name: 'alpha-service',
    session_count: 2,
    sessions: [
      makeSession({
        id: 'sess-a1',
        custom_title: 'Refactor auth middleware',
        git_branch: 'feat/auth-v2',
        cwd: '/repo/alpha',
      }),
      makeSession({
        id: 'sess-a2',
        first_prompt: 'Fix diff viewer scrollbar glitch',
        git_branch: 'main',
        cwd: '/repo/alpha',
      }),
    ],
  },
  {
    project_path: '/repo/beta',
    project_name: 'beta-webui',
    session_count: 2,
    sessions: [
      makeSession({
        id: 'sess-b1',
        summary: 'Add sidebar inline search',
        git_branch: 'feat/sidebar',
        cwd: '/repo/beta',
      }),
      makeSession({
        id: 'sess-b2',
        first_prompt: 'Investigate WebSocket reconnect loop',
        git_branch: 'fix/ws',
        cwd: '/repo/beta',
      }),
    ],
  },
]

describe('workspacePrefs store & filterSidebarProjects', () => {
  beforeEach(() => {
    window.localStorage.clear()
    useWorkspacePrefs.getState().reset()
  })

  it('searches across title, summary, first_prompt, git_branch, session id, and project name with snippets', () => {
    const byBranch = filterSidebarProjects(PROJECTS, {
      query: 'feat/auth',
      pinnedIds: [],
    })
    expect(byBranch.projects).toHaveLength(1)
    expect(byBranch.projects[0].sessions.map((s) => s.id)).toEqual(['sess-a1'])

    const byPrompt = filterSidebarProjects(PROJECTS, {
      query: 'SCROLLBAR',
      pinnedIds: [],
    })
    expect(byPrompt.projects).toHaveLength(1)
    expect(byPrompt.projects[0].sessions.map((s) => s.id)).toEqual(['sess-a2'])
    expect(byPrompt.searchResults).toHaveLength(1)
    expect(byPrompt.searchResults[0].projectName).toBe('alpha-service')
    expect(byPrompt.searchResults[0].snippet).toContain('scrollbar')

    const byProject = filterSidebarProjects(PROJECTS, {
      query: 'beta-webui',
      pinnedIds: [],
    })
    expect(byProject.projects).toHaveLength(1)
    expect(byProject.projects[0].sessions.map((s) => s.id)).toEqual(['sess-b1', 'sess-b2'])

    const noMatch = filterSidebarProjects(PROJECTS, {
      query: 'nonexistent-xyz',
      pinnedIds: [],
    })
    expect(noMatch.projects).toHaveLength(0)
    expect(noMatch.totalMatchedSessions).toBe(0)
  })

  it('pins and unpins sessions in order', () => {
    expect(useWorkspacePrefs.getState().togglePin('sess-b1')).toBe(true)
    expect(useWorkspacePrefs.getState().togglePin('sess-a1')).toBe(true)
    expect(useWorkspacePrefs.getState().isPinned('sess-b1')).toBe(true)
    expect(useWorkspacePrefs.getState().pinnedSessionIds).toEqual(['sess-a1', 'sess-b1'])

    const allPinned = filterSidebarProjects(PROJECTS, {
      query: '',
      pinnedIds: useWorkspacePrefs.getState().pinnedSessionIds,
    })
    expect(allPinned.pinned.map((entry) => entry.session.id)).toEqual(['sess-a1', 'sess-b1'])

    expect(useWorkspacePrefs.getState().togglePin('sess-a1')).toBe(false)
    expect(useWorkspacePrefs.getState().isPinned('sess-a1')).toBe(false)
    expect(useWorkspacePrefs.getState().pinnedSessionIds).toEqual(['sess-b1'])
  })

  it('persists viewMode and pinned state in localStorage', () => {
    useWorkspacePrefs.getState().setViewMode('flat')
    useWorkspacePrefs.getState().togglePin('sess-a2')

    const raw = window.localStorage.getItem('poco.sidebar-workspace-prefs.v1')
    expect(raw).toBeTruthy()
    const parsed = JSON.parse(raw!) as {
      viewMode: 'grouped' | 'flat'
      pinnedSessionIds: string[]
    }
    expect(parsed.viewMode).toBe('flat')
    expect(parsed.pinnedSessionIds).toEqual(['sess-a2'])
  })

  it('temporarily floats discovered TUI projects and sessions to the top and restores order when TUI exits', () => {
    const projectsWithTui: CatalogProject[] = [
      PROJECTS[0], // alpha-service
      {
        ...PROJECTS[1], // beta-webui (alphabetically after alpha-service)
        sessions: [
          PROJECTS[1].sessions[0], // sess-b1 (newer, 2026-10-02T09:00:00Z, not TUI)
          { ...PROJECTS[1].sessions[1], tui_attached: true, tui_pid: 4242 }, // sess-b2 (older, live TUI)
        ],
      },
    ]

    // Even when alpha-service is currently selected, beta-webui with a live TUI floats to the top,
    // and its live TUI session (sess-b2) floats above the newer non-TUI session (sess-b1).
    const sortedWithTui = sortSidebarProjects(projectsWithTui, '/work/alpha-service')
    expect(sortedWithTui.map((p) => p.project_name)).toEqual(['beta-webui', 'alpha-service'])
    expect(sortedWithTui[0].sessions.map((s) => s.id)).toEqual(['sess-b2', 'sess-b1'])

    // Flat view also floats the live TUI session above newer non-TUI sessions.
    const flatWithTui = sortFlatSidebarEntries(
      projectsWithTui.flatMap((p) => p.sessions.map((session) => ({ session, projectName: p.project_name }))),
    )
    expect(flatWithTui[0].session.id).toBe('sess-b2')

    // Once the TUI exits (tui_attached false/omitted), normal order is automatically restored.
    const sortedAfterExit = sortSidebarProjects(PROJECTS, '/work/alpha-service')
    expect(sortedAfterExit.map((p) => p.project_name)).toEqual(['alpha-service', 'beta-webui'])
    expect(sortedAfterExit[1].sessions.map((s) => s.id)).toEqual(['sess-b1', 'sess-b2'])
  })

  it('hides a project from the sidebar list and unhides it when re-added', () => {
    useWorkspacePrefs.getState().addWorkspace('/work/beta-webui')
    useWorkspacePrefs.getState().hideProject('/work/beta-webui')
    expect(useWorkspacePrefs.getState().hiddenProjectPaths).toEqual(['/work/beta-webui'])
    expect(useWorkspacePrefs.getState().addedWorkspaces).not.toContain('/work/beta-webui')

    useWorkspacePrefs.getState().addWorkspace('/work/beta-webui')
    expect(useWorkspacePrefs.getState().hiddenProjectPaths).toEqual([])
    expect(useWorkspacePrefs.getState().addedWorkspaces).toContain('/work/beta-webui')
  })
})

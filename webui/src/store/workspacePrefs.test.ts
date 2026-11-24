import { beforeEach, describe, expect, it } from 'vitest'
import type { CatalogProject, CatalogSession } from '../api/types'
import { filterSidebarProjects, useWorkspacePrefs } from './workspacePrefs'

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
})

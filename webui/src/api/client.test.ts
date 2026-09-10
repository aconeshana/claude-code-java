import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchCatalog } from './client'

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
}

describe('fetchCatalog', () => {
  beforeEach(() => {
    sessionStorage.setItem('gateway-token', 'fake-token')
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    sessionStorage.clear()
  })

  it('passes the two-level projects[] shape through unchanged', async () => {
    const body = {
      projects: [{ project_path: '/repo', project_name: 'repo', session_count: 1, sessions: [] }],
    }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(body)))
    const catalog = await fetchCatalog()
    expect(catalog).toEqual(body)
  })

  it('groups the flat sessions[] fallback by work_dir into synthetic projects', async () => {
    const body = {
      sessions: [
        { id: 's1', work_dir: '/repo', summary: 'a', message_count: 3, modified_at: '2026-09-09', git_branch: 'main', active: true, headless_open: false },
        { id: 's2', work_dir: '/repo', summary: 'b', message_count: 1, modified_at: null, git_branch: null, active: false, headless_open: true },
        { id: 's3', work_dir: null, summary: 'c', message_count: 0, modified_at: '2026-09-08', git_branch: null, active: false, headless_open: false },
      ],
    }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(body)))
    const catalog = await fetchCatalog()
    expect(catalog.projects).toHaveLength(2)

    const repoProject = catalog.projects.find((p) => p.project_path === '/repo')
    expect(repoProject).toMatchObject({ project_name: '/repo', session_count: 2 })
    expect(repoProject?.sessions).toEqual([
      { id: 's1', summary: 'a', message_count: 3, modified_at: '2026-09-09', git_branch: 'main', cwd: '/repo', custom_title: null, first_prompt: null, active: true, headless_open: false },
      { id: 's2', summary: 'b', message_count: 1, modified_at: '', git_branch: null, cwd: '/repo', custom_title: null, first_prompt: null, active: false, headless_open: true },
    ])

    const looseProject = catalog.projects.find((p) => p.project_path === '')
    expect(looseProject).toMatchObject({ project_name: '会话', session_count: 1 })
  })
})

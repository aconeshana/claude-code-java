import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useConversations } from './conversations'
import { useSessions } from './sessions'

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
}

function urlOf(input: RequestInfo | URL): string {
  return typeof input === 'string' ? input : input.toString()
}

const CATALOG = {
  projects: [{
    project_path: '/repo',
    project_name: 'repo',
    session_count: 2,
    sessions: [
      { id: 'active-1', summary: 'a', message_count: 3, modified_at: '2026-09-09', git_branch: 'main', cwd: '/repo', custom_title: null, first_prompt: null, active: true, headless_open: false },
      { id: 'closed-1', summary: 'b', message_count: 1, modified_at: '2026-09-08', git_branch: 'main', cwd: '/repo', custom_title: null, first_prompt: null, active: false, headless_open: false },
    ],
  }],
}

const EMPTY_SNAPSHOT = { session_id: 'x', messages: [] }

describe('sessions store', () => {
  beforeEach(() => {
    sessionStorage.setItem('gateway-token', 'fake-token')
    useSessions.setState({ projects: [], loading: false, error: null, selectedSessionId: null })
    useConversations.setState({ conversations: {} })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    sessionStorage.clear()
  })

  it('refresh populates the catalog tree', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(CATALOG)))
    await useSessions.getState().refresh()
    expect(useSessions.getState().projects).toEqual(CATALOG.projects)
  })

  it('select on an already-open session only loads its snapshot, no open round-trip', async () => {
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = urlOf(input)
      return Promise.resolve(jsonResponse(url.includes('/messages') ? EMPTY_SNAPSHOT : CATALOG))
    })
    vi.stubGlobal('fetch', fetchMock)

    await useSessions.getState().select('active-1')

    expect(useSessions.getState().selectedSessionId).toBe('active-1')
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(urlOf(fetchMock.mock.calls[0][0] as RequestInfo)).toContain('/messages')
  })

  it('openSession opens the headless session first, then refreshes and selects it', async () => {
    const calls: string[] = []
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
      const url = urlOf(input)
      calls.push(`${init?.method ?? 'GET'} ${url}`)
      if (url.includes('/open')) {
        return Promise.resolve(jsonResponse({ session_id: 'closed-1', project_path: '/repo', resumed: true, headless: true }))
      }
      if (url.includes('/messages')) return Promise.resolve(jsonResponse(EMPTY_SNAPSHOT))
      return Promise.resolve(jsonResponse(CATALOG))
    })
    vi.stubGlobal('fetch', fetchMock)

    await useSessions.getState().openSession('closed-1', '/repo')

    expect(calls[0]).toBe('POST /api/sessions/open')
    expect(useSessions.getState().selectedSessionId).toBe('closed-1')
  })

  it('closeSession closes the headless session and falls back to the active session', async () => {
    useSessions.setState({ selectedSessionId: 'closed-1' })
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = urlOf(input)
      if (url.includes('/close')) return Promise.resolve(jsonResponse({ session_id: 'closed-1', closed: true }))
      if (url.includes('/messages')) return Promise.resolve(jsonResponse(EMPTY_SNAPSHOT))
      return Promise.resolve(jsonResponse(CATALOG))
    })
    vi.stubGlobal('fetch', fetchMock)

    await useSessions.getState().closeSession('closed-1')

    expect(useSessions.getState().selectedSessionId).toBe('active-1')
  })

  it('closeSession leaves the selection untouched when closing a different session', async () => {
    useSessions.setState({ selectedSessionId: 'active-1' })
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = urlOf(input)
      if (url.includes('/close')) return Promise.resolve(jsonResponse({ session_id: 'closed-1', closed: true }))
      return Promise.resolve(jsonResponse(CATALOG))
    })
    vi.stubGlobal('fetch', fetchMock)

    await useSessions.getState().closeSession('closed-1')

    expect(useSessions.getState().selectedSessionId).toBe('active-1')
  })

  it('openSession surfaces a failed open as a store error instead of throwing', async () => {
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = urlOf(input)
      if (url.includes('/open')) {
        return Promise.resolve(new Response(
          JSON.stringify({ error: { type: 'api_error', message: 'headless sessions are not configured' } }),
          { status: 500, headers: { 'Content-Type': 'application/json' } },
        ))
      }
      return Promise.resolve(jsonResponse(CATALOG))
    })
    vi.stubGlobal('fetch', fetchMock)

    await expect(useSessions.getState().openSession('closed-1', '/repo')).resolves.toBeUndefined()

    expect(useSessions.getState().error).toBe('headless sessions are not configured')
    expect(useSessions.getState().selectedSessionId).toBeNull()
  })

  it('closeSession surfaces a failed close as a store error instead of throwing', async () => {
    useSessions.setState({ selectedSessionId: 'closed-1' })
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = urlOf(input)
      if (url.includes('/close')) {
        return Promise.resolve(new Response(
          JSON.stringify({ error: { type: 'api_error', message: 'session already closed' } }),
          { status: 409, headers: { 'Content-Type': 'application/json' } },
        ))
      }
      return Promise.resolve(jsonResponse(CATALOG))
    })
    vi.stubGlobal('fetch', fetchMock)

    await expect(useSessions.getState().closeSession('closed-1')).resolves.toBeUndefined()

    expect(useSessions.getState().error).toBe('session already closed')
    expect(useSessions.getState().selectedSessionId).toBe('closed-1')
  })
})

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
    useSessions.setState({ projects: [], loading: false, error: null, selectedSessionId: null, perPage: null })
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

  it('refresh keeps the grown per-project page on later refreshes', async () => {
    // The gateway pages listings per project (?per_project=). A grown page
    // (the "load more" affordance) must survive the 10s catalog refresh,
    // or the sidebar would snap back to the default page mid-browsing.
    const urls: string[] = []
    vi.stubGlobal('fetch', vi.fn().mockImplementation((input: RequestInfo | URL) => {
      urls.push(urlOf(input))
      return Promise.resolve(jsonResponse(CATALOG))
    }))
    await useSessions.getState().growPerPage(10)
    await useSessions.getState().refresh()
    expect(urls).toEqual([
      '/api/sessions?per_project=10',
      '/api/sessions?per_project=10',
    ])
    expect(useSessions.getState().perPage).toBe(10)
  })

  it('growPerPage refetches with the larger page and ignores shrinking requests', async () => {
    const urls: string[] = []
    vi.stubGlobal('fetch', vi.fn().mockImplementation((input: RequestInfo | URL) => {
      urls.push(urlOf(input))
      return Promise.resolve(jsonResponse(CATALOG))
    }))
    await useSessions.getState().growPerPage(5)
    await useSessions.getState().growPerPage(3)
    expect(urls).toEqual(['/api/sessions?per_project=5'])
    expect(useSessions.getState().perPage).toBe(5)
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

  it('createSession opens a session with no session_id, then refreshes and selects the minted id', async () => {
    const bodies: unknown[] = []
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
      const url = urlOf(input)
      if (url.includes('/open')) {
        bodies.push(init?.body == null ? null : JSON.parse(init.body as string))
        return Promise.resolve(jsonResponse({ session_id: 'minted-1', project_path: '/repo', resumed: false, headless: true }))
      }
      if (url.includes('/messages')) return Promise.resolve(jsonResponse(EMPTY_SNAPSHOT))
      return Promise.resolve(jsonResponse(CATALOG))
    })
    vi.stubGlobal('fetch', fetchMock)

    await useSessions.getState().createSession('/repo')

    expect(bodies[0]).toEqual({ project_path: '/repo' })
    expect(useSessions.getState().selectedSessionId).toBe('minted-1')
  })

  it('createSession surfaces a failed open as a store error instead of throwing', async () => {
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

    await expect(useSessions.getState().createSession('/repo')).resolves.toBeUndefined()

    expect(useSessions.getState().error).toBe('headless sessions are not configured')
    expect(useSessions.getState().selectedSessionId).toBeNull()
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

  it('reselecting a session with no loaded snapshot reloads it instead of no-oping', async () => {
    // The stuck-UI repair path: an id can be latched without its snapshot
    // landing (a fork whose row had not appeared yet, or a failed fetch).
    // Guarding on the id alone made the repair click a genuine no-op.
    useSessions.setState({ selectedSessionId: 'active-1' })
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = urlOf(input)
      return Promise.resolve(jsonResponse(url.includes('/messages') ? EMPTY_SNAPSHOT : CATALOG))
    })
    vi.stubGlobal('fetch', fetchMock)

    await useSessions.getState().select('active-1')

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(urlOf(fetchMock.mock.calls[0][0] as RequestInfo)).toContain('/messages')
    expect(useConversations.getState().conversations['active-1']).toBeDefined()
  })

  it('reselecting a session whose snapshot is already loaded stays a no-op', async () => {
    // The guard still earns its keep: repeated clicks on the shown row must
    // not refetch the transcript.
    useSessions.setState({ selectedSessionId: 'active-1' })
    useConversations.setState({
      conversations: { 'active-1': { messages: [], lastFrameId: 0, turnRunning: false, lastError: null } },
    })
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(EMPTY_SNAPSHOT))
    vi.stubGlobal('fetch', fetchMock)

    await useSessions.getState().select('active-1')

    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('forkSession selects the new id even when the refresh has not observed it yet', async () => {
    // The Java side writes the child as a sibling .jsonl; a refresh racing
    // that write returns a catalog without the row. Selecting from the fork
    // response (not the tree) keeps the chat pane correct regardless.
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = urlOf(input)
      if (url.includes('/messages')) return Promise.resolve(jsonResponse(EMPTY_SNAPSHOT))
      if (url.includes('/fork')) return Promise.resolve(jsonResponse({ session_id: 'forked-1' }))
      return Promise.resolve(jsonResponse(CATALOG))
    })
    vi.stubGlobal('fetch', fetchMock)

    await useSessions.getState().forkSession('active-1')

    expect(useSessions.getState().selectedSessionId).toBe('forked-1')
    expect(useConversations.getState().conversations['forked-1']).toBeDefined()
    // A later click, once the row finally appears, must still be able to
    // repair the view rather than silently doing nothing.
    useConversations.setState({ conversations: {} })
    await useSessions.getState().select('forked-1')
    expect(useConversations.getState().conversations['forked-1']).toBeDefined()
  })

  it('renameSession patches the row in place without refetching the catalog', async () => {
    useSessions.setState({ projects: CATALOG.projects })
    const calls: string[] = []
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      calls.push(urlOf(input))
      return Promise.resolve(jsonResponse({ session_id: 'active-1', custom_title: '新标题' }))
    })
    vi.stubGlobal('fetch', fetchMock)

    await useSessions.getState().renameSession('active-1', '新标题')

    expect(calls).toEqual(['/api/sessions/active-1/rename'])
    const sessions = useSessions.getState().projects[0].sessions
    expect(sessions[0].custom_title).toBe('新标题')
    // Only the target row changes; siblings keep their identity.
    expect(sessions[1]).toBe(CATALOG.projects[0].sessions[1])
  })

  it('deleteSession drops the row locally and only when the gateway confirms', async () => {
    useSessions.setState({ projects: CATALOG.projects, selectedSessionId: null })
    const calls: string[] = []
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      calls.push(urlOf(input))
      return Promise.resolve(jsonResponse({ session_id: 'closed-1', deleted: true }))
    })
    vi.stubGlobal('fetch', fetchMock)

    await useSessions.getState().deleteSession('closed-1')

    expect(calls).toEqual(['/api/sessions/closed-1'])
    expect(useSessions.getState().projects[0].sessions.map((s) => s.id)).toEqual(['active-1'])
    expect(useSessions.getState().projects[0].session_count).toBe(1)
  })

  it('deleteSession keeps the row when the gateway reports it was not deleted', async () => {
    useSessions.setState({ projects: CATALOG.projects, selectedSessionId: null })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      jsonResponse({ session_id: 'closed-1', deleted: false })))

    await useSessions.getState().deleteSession('closed-1')

    expect(useSessions.getState().projects[0].sessions.map((s) => s.id))
      .toEqual(['active-1', 'closed-1'])
  })

  it('archiveSession drops the row locally and reselects when it was shown', async () => {
    useSessions.setState({ projects: CATALOG.projects, selectedSessionId: 'closed-1' })
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = urlOf(input)
      if (url.includes('/messages')) return Promise.resolve(jsonResponse(EMPTY_SNAPSHOT))
      return Promise.resolve(jsonResponse({ session_id: 'closed-1', archived: true }))
    })
    vi.stubGlobal('fetch', fetchMock)

    await useSessions.getState().archiveSession('closed-1')

    expect(useSessions.getState().projects[0].sessions.map((s) => s.id)).toEqual(['active-1'])
    expect(useSessions.getState().selectedSessionId).toBe('active-1')
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

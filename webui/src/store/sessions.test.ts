import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { MirrorFrame } from '../api/types'
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
    useSessions.setState({ projects: [], loading: false, error: null, selectedSessionId: null, perPage: null, followActive: true })
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

/**
 * Following the TUI's active session. Until the user picks a session here, a
 * LOCAL `session.activated` frame (a /resume or /clear on the terminal side)
 * moves the selection; the first deliberate choice in this client ends that.
 */
describe('sessions store following the TUI', () => {
  beforeEach(() => {
    sessionStorage.setItem('gateway-token', 'fake-token')
    useSessions.setState({ projects: [], loading: false, error: null, selectedSessionId: null, perPage: null, followActive: true })
    useConversations.setState({ conversations: {} })
    vi.stubGlobal('fetch', vi.fn().mockImplementation((input: RequestInfo | URL) => (
      urlOf(input).includes('/messages')
        ? Promise.resolve(jsonResponse(EMPTY_SNAPSHOT))
        : Promise.resolve(jsonResponse(CATALOG))
    )))
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    sessionStorage.clear()
  })

  function activated(sessionId: string, origin: string): MirrorFrame {
    return {
      event: 'session.activated',
      id: 1,
      data: { session_id: sessionId, summary: '', origin },
    } as MirrorFrame
  }

  it('follows a LOCAL activation while the user has not chosen', async () => {
    useSessions.setState({ selectedSessionId: 'closed-1' })

    useSessions.getState().applyFrame(activated('active-1', 'LOCAL'))

    await vi.waitFor(() => {
      expect(useSessions.getState().selectedSessionId).toBe('active-1')
    })
  })

  it('ignores a REMOTE activation, which is this client\'s own echo', async () => {
    useSessions.setState({ selectedSessionId: 'closed-1' })

    useSessions.getState().applyFrame(activated('active-1', 'REMOTE'))

    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(useSessions.getState().selectedSessionId).toBe('closed-1')
  })

  it('stops following once the user picks a session here', async () => {
    await useSessions.getState().selectByUser('closed-1')
    expect(useSessions.getState().followActive).toBe(false)

    useSessions.getState().applyFrame(activated('active-1', 'LOCAL'))

    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(useSessions.getState().selectedSessionId).toBe('closed-1')
  })

  it('keeps following through the boot selection and a post-delete reselect', async () => {
    await useSessions.getState().refresh()
    await useSessions.getState().selectActiveOrFirst()

    // Neither the boot path nor the programmatic reselect below is a user
    // choice, so the TUI stays authoritative.
    expect(useSessions.getState().followActive).toBe(true)

    useSessions.setState({ selectedSessionId: null })
    await useSessions.getState().selectActiveOrFirst()

    expect(useSessions.getState().followActive).toBe(true)
  })

  it('refreshes before selecting, so a just-created session has a row', async () => {
    const urls: string[] = []
    vi.stubGlobal('fetch', vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = urlOf(input)
      urls.push(url)
      return url.includes('/messages')
        ? Promise.resolve(jsonResponse(EMPTY_SNAPSHOT))
        : Promise.resolve(jsonResponse(CATALOG))
    }))

    useSessions.getState().applyFrame(activated('active-1', 'LOCAL'))

    await vi.waitFor(() => {
      expect(useSessions.getState().selectedSessionId).toBe('active-1')
    })
    expect(urls[0]).toContain('/api/sessions')
  })
})

/**
 * The rows' status dots. `running` is a turn in flight; `completed` is the
 * green "finished while you were elsewhere" reminder, which opening the
 * session clears. Both are folded from mirror frames rather than read from
 * the catalog — a stateless poll cannot see the running→stopped transition
 * that creates the reminder.
 */
describe('sessions store activity', () => {
  beforeEach(() => {
    sessionStorage.setItem('gateway-token', 'fake-token')
    useSessions.setState({ projects: [], loading: false, error: null, selectedSessionId: null, perPage: null, followActive: true, activity: {} })
    useConversations.setState({ conversations: {} })
    vi.stubGlobal('fetch', vi.fn().mockImplementation((input: RequestInfo | URL) => (
      urlOf(input).includes('/messages')
        ? Promise.resolve(jsonResponse(EMPTY_SNAPSHOT))
        : Promise.resolve(jsonResponse(CATALOG))
    )))
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    sessionStorage.clear()
  })

  function frame(event: string, sessionId: string): MirrorFrame {
    return { event, id: 1, data: { session_id: sessionId } } as MirrorFrame
  }

  it('turn.started marks the session running and drops a standing reminder', () => {
    useSessions.setState({ activity: { 'closed-1': { running: false, completed: true } } })

    useSessions.getState().applyFrame(frame('turn.started', 'closed-1'))

    expect(useSessions.getState().activity['closed-1']).toEqual({ running: true, completed: false })
  })

  it('turn.completed marks a NOT-selected session completed', () => {
    useSessions.setState({ selectedSessionId: 'active-1' })
    useSessions.getState().applyFrame(frame('turn.started', 'closed-1'))

    useSessions.getState().applyFrame(frame('turn.completed', 'closed-1'))

    expect(useSessions.getState().activity['closed-1']).toEqual({ running: false, completed: true })
  })

  it('turn.completed on the selected session leaves no reminder', () => {
    // Looking at the session you already had open is not news — upstream's
    // isMain rule.
    useSessions.setState({ selectedSessionId: 'closed-1' })
    useSessions.getState().applyFrame(frame('turn.started', 'closed-1'))

    useSessions.getState().applyFrame(frame('turn.completed', 'closed-1'))

    expect(useSessions.getState().activity['closed-1']).toEqual({ running: false, completed: false })
  })

  it.each(['turn.cancelled', 'session.idle'])(
    '%s also ends the turn, so the dot falls on whichever stop lands first', (event) => {
      useSessions.setState({ selectedSessionId: 'active-1' })
      useSessions.getState().applyFrame(frame('turn.started', 'closed-1'))

      useSessions.getState().applyFrame(frame(event, 'closed-1'))

      expect(useSessions.getState().activity['closed-1']).toEqual({ running: false, completed: true })
    })

  it('a stop frame for a session that was not running changes nothing', () => {
    useSessions.getState().applyFrame(frame('turn.completed', 'closed-1'))

    expect(useSessions.getState().activity['closed-1']).toBeUndefined()
  })

  it('ignores frames that carry no session id', () => {
    useSessions.getState().applyFrame({ event: 'turn.started', id: 1, data: {} } as MirrorFrame)

    expect(useSessions.getState().activity).toEqual({})
  })

  it('select clears the reminder even when the session is already loaded', async () => {
    // The redundant-click guard returns early for an already-loaded session,
    // and a click is exactly the "I have seen it" gesture — so the clear has
    // to happen before that guard, or the dot would stick.
    useSessions.setState({
      activity: { 'closed-1': { running: false, completed: true } },
      selectedSessionId: 'closed-1',
    })
    useConversations.setState({ conversations: { 'closed-1': { sessionId: 'closed-1' } } as never })

    await useSessions.getState().select('closed-1')

    expect(useSessions.getState().activity['closed-1']).toEqual({ running: false, completed: false })
  })

  it('select clears the reminder for a fresh session too', async () => {
    useSessions.setState({ activity: { 'closed-1': { running: false, completed: true } } })

    await useSessions.getState().select('closed-1')

    expect(useSessions.getState().activity['closed-1']).toEqual({ running: false, completed: false })
  })

  it('refresh seeds running for an unobserved session without touching reminders', async () => {
    // A page opened mid-turn sees the turn already in flight: the catalog is
    // the only source for that first reading.
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      projects: [{
        project_path: '/repo',
        project_name: 'repo',
        session_count: 1,
        sessions: [{
          id: 'closed-1', summary: 'b', message_count: 1, modified_at: '2026-09-08',
          git_branch: 'main', cwd: '/repo', custom_title: null, first_prompt: null,
          active: false, headless_open: false, running: true,
        }],
      }],
    })))
    useSessions.setState({ activity: { 'active-1': { running: false, completed: true } } })

    await useSessions.getState().refresh()

    expect(useSessions.getState().activity['closed-1']).toEqual({ running: true, completed: false })
    expect(useSessions.getState().activity['active-1']).toEqual({ running: false, completed: true })
  })

  it('refresh never lowers a running flag the stream reported', async () => {
    // The poll is a lagging snapshot; treating its silence as "stopped" would
    // flicker the dot off every ten seconds.
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(CATALOG)))
    useSessions.getState().applyFrame(frame('turn.started', 'closed-1'))

    await useSessions.getState().refresh()

    expect(useSessions.getState().activity['closed-1']).toEqual({ running: true, completed: false })
  })
})

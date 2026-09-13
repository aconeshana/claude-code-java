import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useSettings } from './settings'

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
}

function errorResponse(status: number, message: string): Response {
  return new Response(
    JSON.stringify({ error: { type: 'api_error', message } }),
    { status, headers: { 'Content-Type': 'application/json' } },
  )
}

function urlOf(input: RequestInfo | URL): string {
  return typeof input === 'string' ? input : input.toString()
}

const SNAPSHOT = {
  effective: { alwaysThinkingEnabled: true },
  sources: [{ source: 'userSettings', settings: { alwaysThinkingEnabled: true } }],
}

describe('settings store', () => {
  beforeEach(() => {
    sessionStorage.setItem('gateway-token', 'fake-token')
    useSettings.setState({ effective: null, sources: [], loading: false, error: null })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    sessionStorage.clear()
  })

  it('refresh populates the effective snapshot and sources', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(SNAPSHOT)))
    await useSettings.getState().refresh()
    expect(useSettings.getState().effective).toEqual(SNAPSHOT.effective)
    expect(useSettings.getState().sources).toEqual(SNAPSHOT.sources)
    expect(useSettings.getState().error).toBeNull()
  })

  it('refresh surfaces a failed fetch as a store error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(errorResponse(500, 'settings unavailable')))
    await useSettings.getState().refresh()
    expect(useSettings.getState().error).toBe('settings unavailable')
    expect(useSettings.getState().effective).toBeNull()
  })

  it('writeUserValue posts the op body and applies the refreshed snapshot', async () => {
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
      expect(urlOf(input)).toBe('/api/settings')
      expect(init?.method).toBe('POST')
      expect(JSON.parse(init?.body as string)).toEqual({
        op: 'userValue',
        key: 'alwaysThinkingEnabled',
        value: true,
      })
      return Promise.resolve(jsonResponse({
        effective: { alwaysThinkingEnabled: true },
        sources: [{ source: 'userSettings', settings: { alwaysThinkingEnabled: true } }],
      }))
    })
    vi.stubGlobal('fetch', fetchMock)

    await useSettings.getState().writeUserValue('alwaysThinkingEnabled', true)

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(useSettings.getState().effective).toEqual({ alwaysThinkingEnabled: true })
    expect(useSettings.getState().error).toBeNull()
  })

  it('writeUserValue with null removes the key', async () => {
    const fetchMock = vi.fn().mockImplementation((_input: RequestInfo | URL, init?: RequestInit) => {
      expect(JSON.parse(init?.body as string)).toEqual({
        op: 'userValue',
        key: 'alwaysThinkingEnabled',
        value: null,
      })
      return Promise.resolve(jsonResponse({ effective: {}, sources: [] }))
    })
    vi.stubGlobal('fetch', fetchMock)

    await useSettings.getState().writeUserValue('alwaysThinkingEnabled', null)
    expect(useSettings.getState().effective).toEqual({})
  })

  it('writePermissionMode posts the mode and tier', async () => {
    const fetchMock = vi.fn().mockImplementation((_input: RequestInfo | URL, init?: RequestInit) => {
      expect(JSON.parse(init?.body as string)).toEqual({
        op: 'permissionMode',
        mode: 'plan',
        tier: 'project',
      })
      return Promise.resolve(jsonResponse(SNAPSHOT))
    })
    vi.stubGlobal('fetch', fetchMock)

    await useSettings.getState().writePermissionMode('plan', 'project')
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('replacePermissionRules posts the behavior, rules, and tier', async () => {
    const fetchMock = vi.fn().mockImplementation((_input: RequestInfo | URL, init?: RequestInit) => {
      expect(JSON.parse(init?.body as string)).toEqual({
        op: 'permissionRules',
        behavior: 'deny',
        rules: ['Bash(rm:*)'],
        tier: 'local',
      })
      return Promise.resolve(jsonResponse(SNAPSHOT))
    })
    vi.stubGlobal('fetch', fetchMock)

    await useSettings.getState().replacePermissionRules('deny', ['Bash(rm:*)'], 'local')
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('addDirectories and removeDirectories post their op bodies', async () => {
    const bodies: unknown[] = []
    const fetchMock = vi.fn().mockImplementation((_input: RequestInfo | URL, init?: RequestInit) => {
      bodies.push(JSON.parse(init?.body as string))
      return Promise.resolve(jsonResponse(SNAPSHOT))
    })
    vi.stubGlobal('fetch', fetchMock)

    await useSettings.getState().addDirectories(['/tmp/extra'], 'project')
    await useSettings.getState().removeDirectories(['/tmp/extra'], 'project')

    expect(bodies[0]).toEqual({ op: 'addDirectories', directories: ['/tmp/extra'], tier: 'project' })
    expect(bodies[1]).toEqual({ op: 'removeDirectories', directories: ['/tmp/extra'], tier: 'project' })
  })

  it('a failed mutation surfaces as a store error without clobbering the snapshot', async () => {
    useSettings.setState({ effective: SNAPSHOT.effective, sources: SNAPSHOT.sources })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(errorResponse(500, 'disk full')))

    await useSettings.getState().writeUserValue('alwaysThinkingEnabled', true)

    expect(useSettings.getState().error).toBe('disk full')
    // The pre-mutation snapshot stays visible.
    expect(useSettings.getState().effective).toEqual(SNAPSHOT.effective)
  })
})

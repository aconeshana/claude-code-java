import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useContextOverview } from './contextOverview'

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
}

describe('contextOverview store', () => {
  beforeEach(() => {
    sessionStorage.setItem('gateway-token', 'fake-token')
    useContextOverview.setState({ open: false, payload: null, loading: false, error: null })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    sessionStorage.clear()
  })

  it('opening pulls the overview once; closing does not', async () => {
    const urls: string[] = []
    vi.stubGlobal('fetch', vi.fn().mockImplementation((input: RequestInfo | URL) => {
      urls.push(typeof input === 'string' ? input : input.toString())
      return Promise.resolve(jsonResponse({ sessions: [], time: 1 }))
    }))
    useContextOverview.getState().setOpen(true)
    await vi.waitFor(() => { expect(useContextOverview.getState().payload).not.toBeNull() })
    expect(urls).toEqual(['/api/session/context/overview'])
    useContextOverview.getState().setOpen(false)
    expect(useContextOverview.getState().open).toBe(false)
    expect(urls).toHaveLength(1)
  })

  it('a failed pull records the error and keeps the last payload', async () => {
    useContextOverview.setState({ payload: { sessions: [], time: 0 } })
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('gateway is gone')))
    await useContextOverview.getState().refresh()
    expect(useContextOverview.getState().error).toContain('gateway is gone')
    expect(useContextOverview.getState().payload).toEqual({ sessions: [], time: 0 })
    expect(useContextOverview.getState().loading).toBe(false)
  })
})

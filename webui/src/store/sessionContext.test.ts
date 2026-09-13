import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useSessionContext } from './sessionContext'

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
}

function errorResponse(status: number, message: string): Response {
  return new Response(
    JSON.stringify({ error: { type: 'invalid_request', message } }),
    { status, headers: { 'Content-Type': 'application/json' } },
  )
}

const SNAPSHOT = {
  selection: {
    current: 'sonnet',
    models: [
      { name: 'default', label: 'Default', default: true },
      { name: 'sonnet', label: 'Sonnet', default: false },
    ],
    effort: { current: 'auto', effective: 'auto', choices: ['auto', 'low', 'high'] },
  },
  context: { model: 'sonnet', context_window: 200000, used_tokens: 31000, used_percentage: 16 },
  metrics: {
    turns: 3, steps: 5, llm_ms: 12_000, tool_ms: 4_000, ttft_ms: 900, ttft_steps: 5,
    decode_ms: 6_000, decode_tokens: 400, uncached_input_tokens: 1_000, output_tokens: 800,
    cache_write_tokens: 200, cache_read_tokens: 9_000,
  },
}

describe('sessionContext store', () => {
  beforeEach(() => {
    sessionStorage.setItem('gateway-token', 'fake-token')
    useSessionContext.setState({ selection: null, usage: null, metrics: null, sessionId: null })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    sessionStorage.clear()
  })

  it('refresh populates selection, usage, and the durable metrics fold', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(SNAPSHOT)))
    await useSessionContext.getState().refresh()
    expect(useSessionContext.getState().selection).toEqual(SNAPSHOT.selection)
    expect(useSessionContext.getState().usage).toEqual(SNAPSHOT.context)
    expect(useSessionContext.getState().metrics).toEqual(SNAPSHOT.metrics)
  })

  it('refresh stores null metrics when the session has none served', async () => {
    useSessionContext.setState({ metrics: SNAPSHOT.metrics })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      selection: SNAPSHOT.selection, context: SNAPSHOT.context, metrics: null,
    })))
    await useSessionContext.getState().refresh()
    // Incomplete coverage serves null (docs/hud-metrics-specification.md §6):
    // never display a partial fold as the session total.
    expect(useSessionContext.getState().metrics).toBeNull()
  })

  it('a failed refresh clears the metrics with the seat', async () => {
    useSessionContext.setState({ metrics: SNAPSHOT.metrics })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(errorResponse(500, 'gateway is gone')))
    await useSessionContext.getState().refresh()
    expect(useSessionContext.getState().selection).toBeNull()
    expect(useSessionContext.getState().usage).toBeNull()
    expect(useSessionContext.getState().metrics).toBeNull()
  })

  it('refresh addresses the selected session through the query', async () => {
    const urls: string[] = []
    vi.stubGlobal('fetch', vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = typeof input === 'string' ? input : input.toString()
      urls.push(url)
      return Promise.resolve(jsonResponse(SNAPSHOT))
    }))
    await useSessionContext.getState().refresh('headless-42')
    expect(urls).toEqual(['/api/session/context?session_id=headless-42'])
    expect(useSessionContext.getState().sessionId).toBe('headless-42')
  })

  it('a stale in-flight answer from a previously selected session is dropped', async () => {
    let releaseSlow: (() => void) | null = null
    const slow = new Promise<Response>(resolve => { releaseSlow = () => resolve(jsonResponse(SNAPSHOT)) })
    const pending: Array<Promise<Response>> = [
      slow,
      Promise.resolve(jsonResponse({ selection: null, context: null })),
    ]
    vi.stubGlobal('fetch', vi.fn().mockImplementation(() => pending.shift()!))
    const first = useSessionContext.getState().refresh('session-a')
    const second = useSessionContext.getState().refresh('session-b')
    await second
    releaseSlow!()
    await first
    // The slow session-a answer arrived after the switch: dropped, the
    // store keeps session-b's (empty) answer.
    expect(useSessionContext.getState().sessionId).toBe('session-b')
    expect(useSessionContext.getState().selection).toBeNull()
  })

  it('refresh with no active session leaves both halves null', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      selection: null,
      context: null,
      metrics: null,
    })))
    await useSessionContext.getState().refresh()
    expect(useSessionContext.getState().selection).toBeNull()
    expect(useSessionContext.getState().usage).toBeNull()
    expect(useSessionContext.getState().metrics).toBeNull()
  })

  it('repeated refreshes of the same session never rewrite the target', async () => {
    // A no-op sessionId write would hand every subscriber a fresh state
    // object before the answer even arrives — and any effect keyed on this
    // store would re-run and refresh again, closing a render loop that
    // storms the gateway with GETs. The pre-request write must happen only
    // on a real target move; the answer's write lands exactly once per
    // refresh.
    const sessionIdWrites: Array<string | null> = []
    let lastSeen: string | null = useSessionContext.getState().sessionId
    const unsubscribe = useSessionContext.subscribe((state) => {
      if (state.sessionId !== lastSeen) {
        lastSeen = state.sessionId
        sessionIdWrites.push(state.sessionId)
      }
    })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(SNAPSHOT)))
    try {
      await useSessionContext.getState().refresh('headless-42')
      await useSessionContext.getState().refresh('headless-42')
      expect(useSessionContext.getState().sessionId).toBe('headless-42')
      // One pre-request write for the first move, then never again.
      expect(sessionIdWrites).toEqual(['headless-42'])
    } finally {
      unsubscribe()
    }
  })

  it('selectModel posts the model and applies the refreshed answer', async () => {
    const calls: string[] = []
    vi.stubGlobal('fetch', vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString()
      calls.push(`${init?.method ?? 'GET'} ${url}`)
      if (init?.method === 'POST') {
        expect(JSON.parse(init.body as string)).toEqual({ model: 'opus' })
        return Promise.resolve(jsonResponse({
          selection: { ...SNAPSHOT.selection, current: 'opus' },
          context: SNAPSHOT.context,
          metrics: SNAPSHOT.metrics,
        }))
      }
      return Promise.resolve(jsonResponse(SNAPSHOT))
    }))
    const error = await useSessionContext.getState().selectModel('opus')
    expect(error).toBeNull()
    expect(calls).toEqual(['POST /api/session/context'])
    expect(useSessionContext.getState().selection?.current).toBe('opus')
    // The POST answer carries the metrics fold too — without it, a model
    // switch would blank the stat pills until the next refresh.
    expect(useSessionContext.getState().metrics).toEqual(SNAPSHOT.metrics)
  })

  it('selectModel posts the addressed session with the model', async () => {
    useSessionContext.setState({ sessionId: 'headless-42' })
    vi.stubGlobal('fetch', vi.fn().mockImplementation((_input: RequestInfo | URL, init?: RequestInit) => {
      expect(JSON.parse(init!.body as string)).toEqual({
        session_id: 'headless-42', model: 'opus',
      })
      return Promise.resolve(jsonResponse({
        selection: { ...SNAPSHOT.selection, current: 'opus' },
        context: SNAPSHOT.context,
        metrics: SNAPSHOT.metrics,
      }))
    }))
    const error = await useSessionContext.getState().selectModel('opus')
    expect(error).toBeNull()
    expect(useSessionContext.getState().selection?.current).toBe('opus')
  })

  it('selectEffort posts the effort and applies the refreshed answer', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation((_input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'POST') {
        expect(JSON.parse(init.body as string)).toEqual({ effort: 'high' })
        return Promise.resolve(jsonResponse({
          selection: {
            ...SNAPSHOT.selection,
            effort: { current: 'high', effective: 'high', choices: ['auto', 'low', 'high'] },
          },
          context: SNAPSHOT.context,
          metrics: SNAPSHOT.metrics,
        }))
      }
      return Promise.resolve(jsonResponse(SNAPSHOT))
    }))
    const error = await useSessionContext.getState().selectEffort('high')
    expect(error).toBeNull()
    expect(useSessionContext.getState().selection?.effort?.current).toBe('high')
  })

  it('a rejected selection surfaces the error string and keeps the prior state', async () => {
    useSessionContext.setState({ selection: SNAPSHOT.selection, usage: SNAPSHOT.context })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      errorResponse(400, 'model is not available for this session')))
    const error = await useSessionContext.getState().selectModel('nope')
    expect(error).toBe('model is not available for this session')
    expect(useSessionContext.getState().selection?.current).toBe('sonnet')
  })
})

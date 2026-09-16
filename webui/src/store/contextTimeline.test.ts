import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { MirrorFrame } from '../api/types'
import {
  forgetContextContent, headerContentOf, makeContentFetcher, nodeOfContent,
  resetContextTimelineViewers, useContextTimeline,
} from './contextTimeline'

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
}

/** A minimal dsh `ContextTimeline` head the sanitizer accepts. */
function head(detailRev: number): Record<string, unknown> {
  return {
    ok: true,
    model: 'claude-sonnet-4-5-20250929',
    provider: 'anthropic',
    contextWindow: 200_000,
    current: { system: 3_000, tools: 12_000, user: 500, inject: 100, skill: 0, assistant: 900, tool: 2_000, total: 18_500 },
    counts: { turns: 1, steps: 2, injects: 1, compactions: 0, prunes: 0 },
    detailRev,
    requests: [],
    events: [],
    nodes: [],
    droppedNodes: 0,
    archive: [],
  }
}

function urlOf(input: RequestInfo | URL): string {
  return typeof input === 'string' ? input : input.toString()
}

describe('contextTimeline store', () => {
  beforeEach(() => {
    sessionStorage.setItem('gateway-token', 'fake-token')
    resetContextTimelineViewers()
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
    sessionStorage.clear()
  })

  it('the first viewer pulls the head; later viewers do not re-pull', async () => {
    const urls: string[] = []
    vi.stubGlobal('fetch', vi.fn().mockImplementation((input: RequestInfo | URL) => {
      urls.push(urlOf(input))
      return Promise.resolve(jsonResponse({ timeline: head(1) }))
    }))
    const releaseA = useContextTimeline.getState().open('s1')
    const releaseB = useContextTimeline.getState().open('s1')
    await vi.runAllTimersAsync()
    expect(urls).toEqual(['/api/session/context/timeline?session_id=s1'])
    const entry = useContextTimeline.getState().sessions.s1
    expect(entry?.head?.detailRev).toBe(1)
    expect(entry?.cold).toBe(false)
    releaseA()
    releaseB()
  })

  it('a null timeline marks the session cold', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ timeline: null })))
    await useContextTimeline.getState().refresh('cold')
    expect(useContextTimeline.getState().sessions.cold).toMatchObject({ head: null, cold: true, failed: false })
  })

  it('a non-object head marks the seat failed, a transport error too', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ timeline: 'nope' })))
    await useContextTimeline.getState().refresh('bad')
    expect(useContextTimeline.getState().sessions.bad).toMatchObject({ head: null, failed: true })
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')))
    await useContextTimeline.getState().refresh('down')
    expect(useContextTimeline.getState().sessions.down).toMatchObject({ failed: true, loading: false })
  })

  it('a slower, older refresh never overwrites a newer head', async () => {
    let releaseFirst: (value: Response) => void = () => {}
    const first = new Promise<Response>((resolve) => { releaseFirst = resolve })
    const fetchMock = vi.fn()
      .mockImplementationOnce(() => first)
      .mockImplementationOnce(() => Promise.resolve(jsonResponse({ timeline: head(7) })))
    vi.stubGlobal('fetch', fetchMock)
    const stale = useContextTimeline.getState().refresh('s1')
    await useContextTimeline.getState().refresh('s1')
    expect(useContextTimeline.getState().sessions.s1?.head?.detailRev).toBe(7)
    releaseFirst(jsonResponse({ timeline: head(3) }))
    await stale
    expect(useContextTimeline.getState().sessions.s1?.head?.detailRev).toBe(7)
    expect(useContextTimeline.getState().sessions.s1?.loading).toBe(false)
  })

  it('mirror frames debounce into one refresh for a viewed session only', async () => {
    const urls: string[] = []
    vi.stubGlobal('fetch', vi.fn().mockImplementation((input: RequestInfo | URL) => {
      urls.push(urlOf(input))
      return Promise.resolve(jsonResponse({ timeline: head(2) }))
    }))
    const release = useContextTimeline.getState().open('s1')
    await vi.runAllTimersAsync()
    urls.length = 0

    const frame = (event: MirrorFrame['event'], sessionId: string): MirrorFrame =>
      ({ event, id: 1, data: { session_id: sessionId } } as unknown as MirrorFrame)
    const store = useContextTimeline.getState()
    store.applyFrame(frame('turn.started', 's1'))
    store.applyFrame(frame('tool.completed', 's1'))
    store.applyFrame(frame('turn.completed', 's1'))
    store.applyFrame(frame('turn.completed', 'unwatched'))
    store.applyFrame(frame('message.delta' as MirrorFrame['event'], 's1'))
    expect(urls).toEqual([])
    await vi.advanceTimersByTimeAsync(300)
    expect(urls).toEqual(['/api/session/context/timeline?session_id=s1'])
    release()

    // Released: a frame schedules nothing.
    store.applyFrame(frame('turn.completed', 's1'))
    await vi.advanceTimersByTimeAsync(1_000)
    expect(urls).toHaveLength(1)
  })

  it('releasing the last viewer cancels a pending debounce', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ timeline: head(1) }))
    vi.stubGlobal('fetch', fetchMock)
    const release = useContextTimeline.getState().open('s1')
    await vi.runAllTimersAsync()
    useContextTimeline.getState().applyFrame(
      { event: 'turn.completed', id: 2, data: { session_id: 's1' } } as unknown as MirrorFrame)
    release()
    await vi.advanceTimersByTimeAsync(1_000)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('the jump relay is one-shot and session-addressed', () => {
    const store = useContextTimeline.getState()
    store.requestJump('s1', { time: 5_000, turn: 2 })
    expect(useContextTimeline.getState().takeJump('other')).toBeNull()
    expect(useContextTimeline.getState().takeJump('s1')).toEqual({ time: 5_000, turn: 2 })
    expect(useContextTimeline.getState().takeJump('s1')).toBeNull()
  })
})

describe('on-demand content', () => {
  beforeEach(() => {
    sessionStorage.setItem('gateway-token', 'fake-token')
    forgetContextContent('s1')
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    sessionStorage.clear()
  })

  it('nodeOfContent lifts a stored node into the browser row shape', () => {
    const node = nodeOfContent({
      seq: 4, cat: 'tool', kind: 'tool_result', text: 'hello', tool: 'Read', truncated: true,
    }, 4)
    expect(node).toMatchObject({ seq: 4 })
    expect(nodeOfContent(null, 4)).toBeNull()
  })

  it('makeContentFetcher caches per seq and re-uses the in-flight promise', async () => {
    const urls: string[] = []
    vi.stubGlobal('fetch', vi.fn().mockImplementation((input: RequestInfo | URL) => {
      urls.push(urlOf(input))
      return Promise.resolve(jsonResponse({ content: { seq: 9, cat: 'user', kind: 'text', text: 'hi' } }))
    }))
    const fetchContent = makeContentFetcher('s1')
    const [a, b] = await Promise.all([fetchContent(9), fetchContent(9)])
    await fetchContent(9)
    expect(a).toBe(b)
    expect(urls).toEqual(['/api/session/context/content?session_id=s1&seq=9'])
  })

  it('forgetContextContent invalidates a fetcher created before the forget', async () => {
    let text = 'before'
    vi.stubGlobal('fetch', vi.fn().mockImplementation(() =>
      Promise.resolve(jsonResponse({ content: { seq: 2, cat: 'user', kind: 'text', text } }))))
    const fetchContent = makeContentFetcher('s1')
    await fetchContent(2)
    text = 'after'
    forgetContextContent('s1')
    const node = await fetchContent(2)
    expect(JSON.stringify(node)).toContain('after')
  })

  it('headerContentOf joins the system prompt and the tool list', () => {
    const joined = headerContentOf(
      { kind: 'system', text: 'You are Claude.' },
      { kind: 'tools', tools: [{ name: 'Read', description: 'read a file', schema: { type: 'object' } }] },
    )
    expect(joined).not.toBeNull()
    expect(JSON.stringify(joined)).toContain('You are Claude.')
    expect(JSON.stringify(joined)).toContain('"Read"')
    expect(headerContentOf(null, null)).toBeNull()
  })
})

/**
 * What the changed-files stores make of an answer, and what they keep.
 *
 * These stores are vendored verbatim from upstream, so the only thing worth
 * pinning here is the part upstream's policy cannot cover: this project's
 * gateway authenticates every API route with the launch token, so the answer
 * to a read can be a 401 rather than a record. A 401 must stay retryable —
 * turning it into the kept `'missing'` state would make "the tab has not picked
 * up this launch's token" look exactly like "this turn changed no files", and
 * the card would stay absent for the rest of the connection.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { changesDiffStore, changesSummaryStore } from './changes'

const SESSION = 'sess-changes'
const TURN = 2
const PATH = 'src/App.tsx'

const SUMMARY = {
  turn: TURN,
  total: 1,
  added: 1,
  deleted: 0,
  files: [{ path: PATH, display: PATH, added: 1, deleted: 0 }],
}

/** The reads the stub answered, in order. */
let reads: string[] = []

/** Answers every read with the responses given, in order; the last one repeats. */
function stubResponses(...responses: Response[]): void {
  let index = 0
  reads = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    reads.push(String(input))
    const response = responses[Math.min(index, responses.length - 1)]
    index += 1
    return response.clone()
  }))
}

beforeEach(() => {
  changesSummaryStore.reset()
  changesDiffStore.reset()
  sessionStorage.setItem('gateway-token', 'test-token')
})

afterEach(() => {
  vi.unstubAllGlobals()
  sessionStorage.removeItem('gateway-token')
})

describe('changes stores', () => {
  it('keeps a landed summary for the connection', async () => {
    stubResponses(new Response(JSON.stringify(SUMMARY), { status: 200 }))
    await changesSummaryStore.load(SESSION, TURN)
    await changesSummaryStore.load(SESSION, TURN)
    expect(changesSummaryStore.state.getSnapshot()[`/api/changes.summary?session_id=${SESSION}&turn=${TURN}`])
      .toEqual(SUMMARY)
    expect(reads.length).toBe(1)
  })

  it('reads a 404 answer as missing and keeps it', async () => {
    stubResponses(new Response(null, { status: 404 }))
    await changesSummaryStore.load(SESSION, TURN)
    await changesSummaryStore.load(SESSION, TURN)
    expect(changesSummaryStore.state.getSnapshot()).toEqual({
      [`/api/changes.summary?session_id=${SESSION}&turn=${TURN}`]: 'missing',
    })
    expect(reads.length).toBe(1)
  })

  it('reads an unauthenticated answer as retryable, not as missing', async () => {
    stubResponses(
      new Response(JSON.stringify({ error: { type: 'authentication_required' } }), { status: 401 }),
      new Response(JSON.stringify(SUMMARY), { status: 200 }),
    )
    await changesSummaryStore.load(SESSION, TURN)
    const url = `/api/changes.summary?session_id=${SESSION}&turn=${TURN}`
    expect(changesSummaryStore.state.getSnapshot()[url]).toBe('error')

    await changesSummaryStore.load(SESSION, TURN)
    expect(changesSummaryStore.state.getSnapshot()[url]).toEqual(SUMMARY)
  })

  it('reads an unauthenticated comparison as retryable as well', async () => {
    stubResponses(new Response(null, { status: 401 }))
    await changesDiffStore.load(SESSION, PATH)
    expect(changesDiffStore.state.getSnapshot()[reads[0]]).toBe('error')
  })
})

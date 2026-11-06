import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { resetAgentTranscripts, useAgentTranscript } from './agentTranscript'

/**
 * Wait for the store to settle out of `loading`.
 *
 * The store's `load` kicks off an async read without returning it (it is a
 * store action, not a promise), so a test has to wait on the state it lands.
 */
async function settle(): Promise<void> {
  for (let attempt = 0; attempt < 20; attempt += 1) {
    const entries = Object.values(useAgentTranscript.getState().transcripts)
    if (entries.length > 0 && entries.every((entry) => entry.status !== 'loading')) return
    await new Promise((resolve) => { setTimeout(resolve, 0) })
  }
  throw new Error('transcript never settled')
}

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
}

const TRANSCRIPT = {
  session_id: 's1',
  agent_id: 'a1',
  messages: [
    {
      id: 'u1',
      role: 'user',
      complete: true,
      text: 'read the router table',
      time: 1_700_000_000_000,
    },
    {
      id: 'a1',
      role: 'assistant',
      complete: true,
      content: [{ type: 'text', text: 'the middleware is not applied' }],
      time: 1_700_000_001_000,
    },
  ],
}

describe('agentTranscript store', () => {
  beforeEach(() => {
    sessionStorage.setItem('gateway-token', 'fake-token')
    resetAgentTranscripts()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    sessionStorage.clear()
  })

  it('requests the addressed agent and reduces the rows through the chat reducer', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(TRANSCRIPT))
    vi.stubGlobal('fetch', fetchMock)

    useAgentTranscript.getState().load('s1', 'a1')
    await settle()

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url] = fetchMock.mock.calls[0] as [string]
    expect(url).toContain('/api/sessions/s1/agents/a1/transcript')

    const entry = useAgentTranscript.getState().transcripts['s1\u0000a1']
    expect(entry?.status).toBe('ready')
    // The two rows are the chat's own shapes, not a transcript-specific one.
    expect(entry?.messages.map((row) => row.kind)).toEqual(['user', 'assistant'])
    expect(entry?.messages[0]?.kind === 'user' && entry.messages[0].text).toBe('read the router table')
  })

  it('escapes both ids so a path segment cannot be read as a second one', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(TRANSCRIPT))
    vi.stubGlobal('fetch', fetchMock)

    useAgentTranscript.getState().load('a/b', 'c d')
    await settle()

    const [url] = fetchMock.mock.calls[0] as [string]
    expect(url).toContain('/api/sessions/a%2Fb/agents/c%20d/transcript')
  })

  it('does not refetch an agent already loading or loaded', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(TRANSCRIPT))
    vi.stubGlobal('fetch', fetchMock)

    useAgentTranscript.getState().load('s1', 'a1')
    useAgentTranscript.getState().load('s1', 'a1')
    await settle()
    useAgentTranscript.getState().load('s1', 'a1')

    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('keys by session as well as agent, so the same id in two sessions is two reads', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(TRANSCRIPT))
    vi.stubGlobal('fetch', fetchMock)

    useAgentTranscript.getState().load('s1', 'a1')
    useAgentTranscript.getState().load('s2', 'a1')
    await settle()

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(Object.keys(useAgentTranscript.getState().transcripts).sort()).toEqual(['s1\u0000a1', 's2\u0000a1'])
  })

  it('keeps a failure on the entry instead of throwing, so the row can offer a retry', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ error: { type: 'not_found', message: 'no such agent' } }), {
        status: 404,
        headers: { 'Content-Type': 'application/json' },
      }),
    ))

    useAgentTranscript.getState().load('s1', 'ghost')
    await settle()

    const entry = useAgentTranscript.getState().transcripts['s1\u0000ghost']
    expect(entry?.status).toBe('failed')
    expect(entry?.messages).toEqual([])
    expect(entry?.message).not.toBeNull()
  })

  it('reload drops the failed entry and reads it again', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(null, { status: 500 }))
      .mockResolvedValueOnce(jsonResponse(TRANSCRIPT))
    vi.stubGlobal('fetch', fetchMock)

    useAgentTranscript.getState().load('s1', 'a1')
    await settle()
    expect(useAgentTranscript.getState().transcripts['s1\u0000a1']?.status).toBe('failed')

    useAgentTranscript.getState().reload('s1', 'a1')
    await settle()
    expect(useAgentTranscript.getState().transcripts['s1\u0000a1']?.status).toBe('ready')
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })
})
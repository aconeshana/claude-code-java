import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { subscribeMirror } from './events'
import type { MirrorFrame } from './types'

/**
 * The mirror stream's frame-id rule, checked without a browser.
 *
 * Every journaled frame carries the `id:` the gateway assigned it; the two
 * streaming slices do not. EventSource therefore reports the PREVIOUS journaled
 * id as their lastEventId, and forwarding that would make the conversation
 * reducer drop them as replays of a frame it already applied. The browser run
 * that caught this only saw it as text that never appeared.
 */

type Listener = (event: { data: string; lastEventId: string }) => void

class FakeEventSource {
  static readonly instances: FakeEventSource[] = []
  readonly listeners = new Map<string, Listener[]>()
  closed = false
  onopen: (() => void) | null = null
  onerror: (() => void) | null = null

  constructor(readonly url: string) { FakeEventSource.instances.push(this) }

  addEventListener(name: string, listener: Listener): void {
    const existing = this.listeners.get(name) ?? []
    existing.push(listener)
    this.listeners.set(name, existing)
  }

  close(): void { this.closed = true }

  deliver(name: string, data: string, lastEventId: string): void {
    for (const listener of this.listeners.get(name) ?? []) listener({ data, lastEventId })
  }
}

let frames: MirrorFrame[]

function subscribe(): { close(): void } {
  return subscribeMirror((frame) => { frames.push(frame) })
}

beforeEach(() => {
  FakeEventSource.instances.length = 0
  frames = []
  ;(globalThis as unknown as { EventSource: unknown }).EventSource = FakeEventSource
})

afterEach(() => {
  delete (globalThis as unknown as { EventSource?: unknown }).EventSource
})

describe('subscribeMirror', () => {
  it('opens the gateway stream under /api/events', () => {
    subscribe().close()

    expect(FakeEventSource.instances[0].url).toBe('/api/events')
    expect(FakeEventSource.instances[0].closed).toBe(true)
  })

  it('keeps the gateway frame id on a journaled frame', () => {
    const connection = subscribe()
    FakeEventSource.instances[0].deliver('turn.started', '{"session_id":"s"}', '431')
    connection.close()

    expect(frames).toHaveLength(1)
    expect(frames[0]).toMatchObject({ event: 'turn.started', id: 431 })
    expect(frames[0].data).toEqual({ session_id: 's' })
  })

  it('reports a streaming text delta as not journaled', () => {
    const connection = subscribe()
    FakeEventSource.instances[0].deliver('output.text.delta', '{"text":"hi"}', '431')
    connection.close()

    expect(frames).toHaveLength(1)
    expect(frames[0]).toMatchObject({ event: 'output.text.delta', id: 431 })
  })

  it('reports a streaming thinking delta as not journaled', () => {
    const connection = subscribe()
    FakeEventSource.instances[0].deliver('output.thinking.delta', '{"text":"hmm"}', '431')
    connection.close()

    expect(frames[0]).toMatchObject({ event: 'output.thinking.delta', id: 0 })
  })

  it('skips a malformed frame instead of failing the stream', () => {
    const connection = subscribe()
    FakeEventSource.instances[0].deliver('turn.completed', '{not json', '432')
    FakeEventSource.instances[0].deliver('turn.completed', '{"done":true}', '433')
    connection.close()

    expect(frames).toHaveLength(1)
    expect(frames[0].id).toBe(433)
  })

  it('subscribes to every frame the gateway emits', () => {
    const connection = subscribe()
    const names = FakeEventSource.instances[0].listeners
    connection.close()

    for (const name of [
      'turn.started', 'output.text', 'output.thinking', 'output.text.delta',
      'output.thinking.delta', 'tool.started', 'tool.progress', 'tool.completed',
      'permission.asked', 'permission.resolved', 'sudo.asked', 'sudo.resolved',
      'permission.mode', 'session.idle', 'turn.completed',
    ]) {
      expect(names.get(name)?.length ?? 0, `${name} must be subscribed`).toBe(1)
    }
  })
})

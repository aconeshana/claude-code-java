import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { currentToken, initTokenSync } from './token'

interface FakeMessage { readonly type: string; readonly token?: string }

/**
 * jsdom (the vitest environment here) has no BroadcastChannel implementation,
 * so the handshake protocol is exercised against this minimal same-name
 * fan-out stand-in instead of the real browser API.
 */
class FakeBroadcastChannel {
  static instances: FakeBroadcastChannel[] = []
  onmessage: ((event: MessageEvent<FakeMessage>) => void) | null = null

  constructor(private readonly name: string) {
    FakeBroadcastChannel.instances.push(this)
  }

  postMessage(data: FakeMessage): void {
    for (const other of FakeBroadcastChannel.instances) {
      if (other !== this && other.name === this.name) {
        other.onmessage?.({ data } as MessageEvent<FakeMessage>)
      }
    }
  }

  close(): void {
    FakeBroadcastChannel.instances = FakeBroadcastChannel.instances.filter((i) => i !== this)
  }
}

describe('initTokenSync', () => {
  beforeEach(() => {
    sessionStorage.clear()
    FakeBroadcastChannel.instances = []
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('does nothing when BroadcastChannel is unavailable', () => {
    expect(() => { initTokenSync(() => {}) }).not.toThrow()
  })

  it('proactively broadcasts its token on init so already-open tabs pick it up immediately', () => {
    vi.stubGlobal('BroadcastChannel', FakeBroadcastChannel)
    sessionStorage.setItem('gateway-token', 'tok-a')
    const listener = new FakeBroadcastChannel('webui-token-sync')
    const seen: FakeMessage[] = []
    listener.onmessage = (event) => { seen.push(event.data) }

    initTokenSync(() => {})

    expect(seen).toContainEqual({ type: 'token', token: 'tok-a' })
  })

  it('answers a token request from another tab when it holds one', () => {
    vi.stubGlobal('BroadcastChannel', FakeBroadcastChannel)
    sessionStorage.setItem('gateway-token', 'tok-a')
    initTokenSync(() => {})

    const requester = new FakeBroadcastChannel('webui-token-sync')
    const replies: FakeMessage[] = []
    requester.onmessage = (event) => { replies.push(event.data) }
    requester.postMessage({ type: 'request-token' })

    expect(replies).toContainEqual({ type: 'token', token: 'tok-a' })
  })

  it('adopts a token broadcast by another tab when it has none locally', () => {
    vi.stubGlobal('BroadcastChannel', FakeBroadcastChannel)
    const received: string[] = []
    initTokenSync((token) => { received.push(token) })

    const other = new FakeBroadcastChannel('webui-token-sync')
    other.postMessage({ type: 'token', token: 'tok-b' })

    expect(received).toEqual(['tok-b'])
    expect(currentToken()).toBe('tok-b')
  })

  it('does not overwrite an existing local token with a broadcast one', () => {
    vi.stubGlobal('BroadcastChannel', FakeBroadcastChannel)
    sessionStorage.setItem('gateway-token', 'tok-a')
    const received: string[] = []
    initTokenSync((token) => { received.push(token) })

    const other = new FakeBroadcastChannel('webui-token-sync')
    other.postMessage({ type: 'token', token: 'tok-b' })

    expect(received).toEqual([])
    expect(currentToken()).toBe('tok-a')
  })
})

import { describe, expect, it } from 'vitest'
import { protocolOf, ResourceRegistry, RESOURCE_SCHEME } from './resources.ts'
import type { ResourceFrame, ResourceProvider } from './contract.ts'

/**
 * The resource model: addressing, the holder refcount, and the frame contract.
 *
 * The registry's cordis `ctx.effect` was cut in favour of direct install plus a
 * returned disposer (see `resources.ts`). These tests pin the behaviour that
 * effect provided — registering a protocol attaches addresses already recorded
 * for it, disposing detaches them — so the cut is provably behaviour-preserving
 * rather than merely plausible.
 */

const ADDRESS = 'dsh-resource://demo/session/s1/a.txt'

/** A provider whose stream is driven by the test rather than by real I/O. */
function scripted(protocol: string): {
  provider: ResourceProvider
  opened: string[]
  pushes: ResourceFrame<unknown>[]
  aborted: number[]
} {
  const opened: string[] = []
  const pushes: ResourceFrame<unknown>[] = []
  const aborted: number[] = []
  const provider: ResourceProvider = {
    protocol,
    async *open(address, ctx) {
      opened.push(address)
      const at = opened.length - 1
      ctx.signal.addEventListener('abort', () => { aborted.push(at) })
      // A stream that ends immediately still leaves the last state in place.
      for (;;) {
        const frame = pushes.shift()
        if (frame === undefined) return
        if (ctx.signal.aborted) return
        yield frame
      }
    },
  }
  return { provider, opened, pushes, aborted }
}

/** Let the registry's consume loop drain the pushed frames. */
async function settle(): Promise<void> {
  for (let i = 0; i < 5; i += 1) await Promise.resolve()
  await new Promise((resolve) => { setTimeout(resolve, 0) })
}

describe('protocolOf', () => {
  it('reads the protocol from the address host, lower-cased', () => {
    expect(protocolOf(ADDRESS)).toBe('demo')
    expect(protocolOf('dsh-resource://DEMO/x')).toBe('demo')
  })

  // Other schemes still parse as URLs; they are navigation addresses, not ours.
  it('names no protocol for another scheme', () => {
    expect(protocolOf('sidebar://guide')).toBeUndefined()
    expect(protocolOf('https://example.com/x')).toBeUndefined()
  })

  it('names no protocol for a string the URL parser rejects', () => {
    expect(protocolOf('/a/b.txt')).toBeUndefined()
    expect(protocolOf('')).toBeUndefined()
  })

  it('names no protocol when the address has no host', () => {
    expect(protocolOf(`${RESOURCE_SCHEME}:///a`)).toBeUndefined()
  })
})

describe('ResourceRegistry', () => {
  it('reports none for an address whose protocol has no provider', () => {
    const registry = new ResourceRegistry()
    expect(registry.source('dsh-resource://nobody/x').getSnapshot().status).toBe('none')
  })

  it('opens the provider stream on the first subscriber and reports live', async () => {
    const { provider, opened, pushes } = scripted('demo')
    pushes.push({ ok: true, value: 'v1' })
    const registry = new ResourceRegistry()
    registry.register(provider)

    const source = registry.source(ADDRESS)
    const unsubscribe = source.subscribe(() => {})
    await settle()

    expect(opened).toEqual([ADDRESS])
    expect(source.getSnapshot()).toEqual({ status: 'live', value: 'v1', failure: undefined })
    unsubscribe()
  })

  // The refcount is the reason a second reader of the same address does not
  // restart the stream.
  it('opens one stream for two subscribers and closes it on the last release', async () => {
    const { provider, opened, aborted } = scripted('demo')
    const registry = new ResourceRegistry()
    registry.register(provider)

    const source = registry.source(ADDRESS)
    const first = source.subscribe(() => {})
    const second = source.subscribe(() => {})
    await settle()
    expect(opened).toHaveLength(1)

    first()
    await settle()
    expect(aborted).toHaveLength(0)

    second()
    await settle()
    expect(aborted).toEqual([0])
  })

  it('returns to idle after the last release, keeping the record', async () => {
    const { provider, opened, pushes } = scripted('demo')
    pushes.push({ ok: true, value: 'v1' })
    const registry = new ResourceRegistry()
    registry.register(provider)

    const source = registry.source(ADDRESS)
    // Reference stability across a remount is what keeps React from
    // resubscribing every render, so the same object must come back.
    expect(registry.source(ADDRESS)).toBe(source)

    const unsubscribe = source.subscribe(() => {})
    await settle()
    unsubscribe()
    expect(source.getSnapshot()).toEqual({ status: 'loading', value: undefined, failure: undefined })

    // A later subscriber reads through the same record and restarts the stream.
    pushes.push({ ok: true, value: 'v2' })
    const again = source.subscribe(() => {})
    await settle()
    expect(opened).toHaveLength(2)
    expect(source.getSnapshot().value).toBe('v2')
    again()
  })

  it('keeps the last value when a later frame fails', async () => {
    const { provider, pushes } = scripted('demo')
    pushes.push({ ok: true, value: 'v1' })
    pushes.push({ ok: false, error: { code: 'ENOENT', message: 'gone' } })
    const registry = new ResourceRegistry()
    registry.register(provider)

    const source = registry.source(ADDRESS)
    const unsubscribe = source.subscribe(() => {})
    await settle()

    expect(source.getSnapshot()).toEqual({
      status: 'failed', value: 'v1', failure: { code: 'ENOENT', message: 'gone' },
    })
    unsubscribe()
  })

  // A failure is always a frame — the provider must not throw through.
  it('refuses a second provider for the same protocol', () => {
    const registry = new ResourceRegistry()
    registry.register(scripted('demo').provider)
    expect(() => registry.register(scripted('demo').provider))
      .toThrowError(/already has a provider/)
  })

  // This is the behaviour upstream's ctx.effect provided: an address recorded
  // before its protocol was registered is attached when the provider arrives.
  it('attaches an address recorded before its provider registered', async () => {
    const registry = new ResourceRegistry()
    const source = registry.source(ADDRESS)
    expect(source.getSnapshot().status).toBe('none')

    const { provider, opened, pushes } = scripted('demo')
    pushes.push({ ok: true, value: 'late' })
    registry.register(provider)
    // Not held yet, so the arriving provider leaves it at loading.
    expect(source.getSnapshot().status).toBe('loading')
    expect(opened).toHaveLength(0)

    const unsubscribe = source.subscribe(() => {})
    await settle()
    expect(opened).toEqual([ADDRESS])
    expect(source.getSnapshot().value).toBe('late')
    unsubscribe()
  })

  it('detaches on dispose, reporting none again', async () => {
    const { provider, aborted } = scripted('demo')
    const registry = new ResourceRegistry()
    const dispose = registry.register(provider)

    const source = registry.source(ADDRESS)
    const unsubscribe = source.subscribe(() => {})
    await settle()

    dispose()
    expect(aborted).toEqual([0])
    expect(source.getSnapshot().status).toBe('none')
    unsubscribe()
  })

  it('holds a resource open through a pin without subscribing', async () => {
    const { provider, opened } = scripted('demo')
    const registry = new ResourceRegistry()
    registry.register(provider)

    const controller = new AbortController()
    registry.pin(ADDRESS, controller.signal)
    await settle()
    expect(opened).toEqual([ADDRESS])

    controller.abort()
    await settle()
    expect(registry.source(ADDRESS).getSnapshot().status).toBe('loading')
  })

  it('pins nothing for an already-aborted signal', () => {
    const { provider, opened } = scripted('demo')
    const registry = new ResourceRegistry()
    registry.register(provider)

    const controller = new AbortController()
    controller.abort()
    registry.pin(ADDRESS, controller.signal)
    expect(opened).toHaveLength(0)
  })
})
/**
 * The resource model's entry point — upstream `packages/client/resources`
 * (`src/client/index.ts`), reduced to the two things this project needs.
 *
 * Upstream's `apply(ctx)` does three things: builds a `ResourceRegistry`, asks
 * cordis to provide it as the `resources` service, and contributes the
 * `resource` keyed hook that reaches every slot component as `useResource`.
 * The first survives as {@link resources}; the other two are the cordis seam
 * and are cut (recorded in `webui/UPSTREAM.md`). What replaces the keyed hook
 * is {@link useResource} below — the same read, expressed as a
 * `useSyncExternalStore` over the very source the hook exposed, so a component
 * written against upstream's `useResource(address)` reads the same value here.
 */
import { useSyncExternalStore } from 'react'
import { ResourceRegistry } from './resources.ts'
import type { ResourceSnapshot } from './contract.ts'

export { ResourceRegistry, RESOURCE_SCHEME, protocolOf } from './resources.ts'
export type {
  ObservableResourceSource,
  ResourceFailure,
  ResourceFrame,
  ResourceOpenContext,
  ResourceProvider,
  ResourceSnapshot,
  ResourceStatus,
  Resources,
} from './contract.ts'

/**
 * The one registry. Upstream's is per client context; this app has one, built
 * at module scope the way `store/`'s singletons are. Every provider registers
 * here.
 */
export const resources = new ResourceRegistry()

/**
 * The current state of one resource address.
 *
 * This is the React binding upstream synthesizes in its renderer for the
 * `resource` root keyed hook. It is deliberately the *only* place React meets
 * the registry: `getSnapshot` reads without holding the resource, while
 * `subscribe` is what takes a hold — so a component that renders an address
 * opens the provider's stream, and unmounting releases it and discards the
 * state back to idle.
 *
 * The `source` reference is stable per address (`ResourceRegistry.create`
 * caches one record per address forever), so the two callbacks are stable too
 * and React does not resubscribe on every render.
 * @param address - the full `dsh-resource://<type>/…` address.
 * @returns the address's live snapshot.
 */
export function useResource(address: string): ResourceSnapshot {
  const source = resources.source(address)
  return useSyncExternalStore(source.subscribe, source.getSnapshot, source.getSnapshot)
}
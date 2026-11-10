/**
 * React binding over the vendored changed-files stores.
 *
 * The three stores (`ChangesSummaryStore`, `ChangesDiffStore`, `HostReadStore`)
 * are vendored verbatim from upstream `ui-deliverables`; what this module adds
 * is the seam they were written against upstream and this app does not have:
 * upstream reads them through slots and `ctx`, so the host instance and the
 * `useSyncExternalStore` subscription both live in the framework. Here they are
 * module singletons with the same read, exactly as `vendor/ui-layout/index.ts`
 * does for the layout store.
 *
 * The stores are `HostReadStore` subclasses whose `state` is the very
 * `SnapshotStore` the engine exposes, so a hook is a direct
 * `useSyncExternalStore` over it — no adapter, and no re-subscription between
 * renders, because `state` is a stable instance field. The snapshot it returns
 * is the whole record map, not the one record; see {@link useHostRead} for why
 * selecting inside `getSnapshot` spins React.
 */
import { useSyncExternalStore } from 'react'
import { ChangesDiffStore, type ChangesDiffState } from '@vendor/ui-deliverables/changes-diff'
import { ChangesSummaryStore, type ChangesSummaryState } from '@vendor/ui-deliverables/changes-summary'
import type { HostReadStore } from '@vendor/ui-deliverables/host-read-store'
import { changesDiffUrl, changesSummaryUrl } from '@vendor/ui-deliverables/changes'

/** The one comparison store. Upstream's is per client context; this app has one host. */
export const changesDiffStore = new ChangesDiffStore()

/** The one summary store. */
export const changesSummaryStore = new ChangesSummaryStore()

/**
 * The read face the vendored `ChangedFiles` consumes: subscribe to a
 * comparison's live state, and load one unless a kept state already stands.
 *
 * Upstream hands the card this face through its slot injection, whose
 * `useChangesDiff(selector)` is a hook over the store. A hook cannot be a plain
 * method, so `useState` carries the hook and the card calls it from inside its
 * preview component — hooks are positional, and the preview is the component
 * that reads. The object identity is stable, because `ChangedFiles`'s
 * preview effect depends on `diff` and a fresh object per render would restart
 * the read.
 */
export const changesDiffAccess = {
  useState: (url: string): ChangesDiffState | undefined => useHostRead(changesDiffStore, url),
  load: (sessionId: string, path: string): Promise<void> => changesDiffStore.load(sessionId, path),
}

/**
 * One state record's value, re-read when the store publishes.
 *
 * The store engine's `subscribe` forwards every write, so reading `[url]`
 * directly would give React a fresh `undefined` for an unread key on each
 * publish — and `useSyncExternalStore` compares snapshots by identity, which
 * makes it re-render forever ("The result of getSnapshot should be cached").
 * The hook therefore returns the whole record and selects outside the
 * snapshot read; identity is stable between publishes because the record is
 * replaced only when a write lands.
 *
 * @param store - the host-read store to subscribe to.
 * @param url - the record URL to read.
 * @returns the record's live state, or undefined before any read.
 */
function useHostRead<T>(store: HostReadStore<T>, url: string): T | undefined {
  const records = useSyncExternalStore(store.state.subscribe, store.state.getSnapshot, store.state.getSnapshot)
  return records[url]
}

/** One comparison's live state, or undefined before any read. */
export function useChangesDiff(sessionId: string, path: string): ChangesDiffState | undefined {
  return useHostRead(changesDiffStore, changesDiffUrl(sessionId, path))
}

/** One summary's live state, or undefined before any read. */
export function useChangesSummary(sessionId: string, turn: number): ChangesSummaryState | undefined {
  return useHostRead(changesSummaryStore, changesSummaryUrl(sessionId, turn))
}

// The URL builders come from the vendored `changes.ts` rather than being
// rebuilt here, so a hook and the store it reads can never disagree on the key
// a state is filed under. Re-exported so a consumer importing this module for
// its hooks does not also have to reach into the vendor tree.
export { changesDiffUrl, changesSummaryUrl }
export type { ChangesDiff, ChangesSummary, ChangesSummaryFile } from '@vendor/ui-deliverables/changes'
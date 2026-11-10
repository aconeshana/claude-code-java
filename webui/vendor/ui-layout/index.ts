/**
 * The frame geometry and layout store — upstream `packages/client/ui-layout`
 * at the pinned commit, reduced to its two reusable pieces.
 *
 * Upstream's package also ships `service.ts` (the `ctx.layout` service face,
 * including the main-panel route table), `AppFrame.tsx` (which pulls in the
 * slot machinery to render its three seats), `DocumentTitle.tsx`,
 * `theme-presenter.ts` and `shortcut-locales.ts`. All are cut: this app's frame
 * is `src/views/AppFrame.tsx`, its panes are plain React children rather than
 * slots, and it has no main-panel routes or document-title participant. See
 * `webui/UPSTREAM.md` for the per-file ledger.
 *
 * What survives is the geometry (`columns.ts`) and the layout state machine
 * (`stores.ts`) — the two pieces the right column actually needs.
 */
import { useSyncExternalStore } from 'react'
import { createLayoutStore } from './stores.ts'
import type { LayoutState } from './stores.ts'

export {
  CENTER_MIN, RIGHTBAR_DEFAULT_RATIO, RIGHTBAR_MAX_RATIO, RIGHTBAR_MIN,
  SIDEBAR_AUTO_COLLAPSE, SIDEBAR_COLLAPSED, SIDEBAR_DEFAULT, SIDEBAR_MAX, SIDEBAR_MIN,
  clampWidth, computeColumns,
} from './columns.ts'
export type { Columns } from './columns.ts'
export { createLayoutStore } from './stores.ts'
export type { LayoutActions, LayoutState } from './stores.ts'

/**
 * The one layout instance. Upstream builds its store per registration and
 * hands it to slots through `ctx.layout`; this app has one frame, so the
 * instance is a module singleton like the rest of `store/`.
 *
 * It is built on first access rather than at module scope because a store
 * instance is where persistence and identity live, and importing this module
 * should not have the side effect of constructing one (the wire harness
 * imports app modules in a bare node context).
 */
/** The one live layout instance. */
const layoutInstance = createLayoutStore().create()

/** The layout store's baked actions. */
export const layoutActions = layoutInstance.actions

/**
 * Read one slice of the layout state. This is the React binding upstream
 * synthesizes in its renderer for the store seat; here it is a plain
 * `useSyncExternalStore` over the engine instance.
 * @param select - the slice to read.
 * @returns the selected slice.
 */
export function useLayout<S>(select: (state: LayoutState) => S): S {
  return useSyncExternalStore(
    layoutInstance.subscribe,
    () => select(layoutInstance.getSnapshot()),
    () => select(layoutInstance.getSnapshot()),
  )
}
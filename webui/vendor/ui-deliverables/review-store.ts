/**
 * The review tab's view state: which listed file is shown, whether hunks are
 * drawn side by side, and whether long lines wrap. One bucket per tab, so two
 * reviews in one session keep their own choices; the bucket ends with the tab
 * record's signal.
 *
 * Upstream `packages/client/ui-deliverables/src/client/review-store.ts` at the
 * pinned commit, with two changes:
 *
 * - The store engine import is repointed at `@vendor/store`.
 * - `TabId` (upstream's branded id from its dockkit package, which this project
 *   does not vendor) becomes plain `string`. The key is only ever used for
 *   record lookup, and the review address already carries the tab's identity,
 *   so a brand would add no checking here.
 *
 * The `navigated` action's seeding rule is kept verbatim and is deliberately
 * asymmetric with the rest: only a *first* navigation creates a bucket, and it
 * creates one already side-by-side and unwrapped. Every other action throws on a
 * missing bucket (`bucket`) rather than creating one, so a write to a tab that
 * was never navigated is a programming error and not silently absorbed.
 */
import { defineStore, type EngineStoreHandle } from '@vendor/store'

/** One review tab's choices. */
export interface ReviewTabState {
  /** Original index of the shown file in the summary's files array. */
  index: number
  /** Whether deletions and additions are drawn in two columns; new tabs start enabled. */
  split: boolean
  /** Whether long lines wrap instead of scrolling. */
  wrap: boolean
  /** The navigation revision whose file index was last applied. */
  navigated: number
}

/** Every review tab's choices, keyed by tab id. */
export interface ReviewState {
  byTab: Record<string, ReviewTabState>
}

function bucket(state: ReviewState, tabId: string): ReviewTabState {
  const tab = state.byTab[tabId]
  if (tab === undefined) throw new Error(`ui-deliverables: no review state for tab "${tabId}"`)
  return tab
}

/** The review store's write set; every action names the tab it writes. */
type ReviewActions = {
  navigated: (draft: ReviewState, tabId: string, revision: number, index: number) => void
  selected: (draft: ReviewState, tabId: string, index: number) => void
  toggledSplit: (draft: ReviewState, tabId: string) => void
  toggledWrap: (draft: ReviewState, tabId: string) => void
  forget: (draft: ReviewState, tabId: string) => void
}

/**
 * Declare the review tab's store. Upstream's registration makes it an exclusive
 * store minted per session; here the instance is created once at module scope in
 * `index.ts`, which is the same shape for a single-frame app.
 * @returns the store handle.
 */
export function createReviewStore(): EngineStoreHandle<ReviewState, ReviewActions> {
  return defineStore({
    init: (): ReviewState => ({ byTab: {} }),
    actions: {
      /**
       * Apply a navigation: seed a side-by-side, unwrapped tab on its first one, then show the navigated file.
       * @param d - draft state.
       * @param tabId - the tab being drawn.
       * @param revision - the navigation revision being applied.
       * @param index - the file index the navigation named, or the current one.
       */
      navigated: (d, tabId: string, revision: number, index: number) => {
        const tab = d.byTab[tabId]
        if (tab === undefined) d.byTab[tabId] = { index, split: true, wrap: false, navigated: revision }
        else { tab.index = index; tab.navigated = revision }
      },
      /**
       * Show another listed file.
       * @param d - draft state.
       * @param tabId - the tab being drawn.
       * @param index - original index in the summary's files array.
       */
      selected: (d, tabId: string, index: number) => { bucket(d, tabId).index = index },
      /**
       * Switch between the unified and the side-by-side view.
       * @param d - draft state.
       * @param tabId - the tab being drawn.
       */
      toggledSplit: (d, tabId: string) => { const tab = bucket(d, tabId); tab.split = !tab.split },
      /**
       * Switch line wrapping.
       * @param d - draft state.
       * @param tabId - the tab being drawn.
       */
      toggledWrap: (d, tabId: string) => { const tab = bucket(d, tabId); tab.wrap = !tab.wrap },
      /**
       * Drop a tab's bucket once its record is gone.
       * @param d - draft state.
       * @param tabId - the tab that ended.
       */
      forget: (d, tabId: string) => {
        d.byTab = Object.fromEntries(Object.entries(d.byTab).filter(([id]) => id !== tabId))
      },
    },
  })
}

export type { ReviewActions }
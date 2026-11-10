/**
 * Which turn's review occupies the right column, and the vendored review
 * store's instance behind it.
 *
 * Upstream has no equivalent module: a review is opened by pushing a
 * `dsh-resource://changes-review/session/<sessionId>/<turn>` address onto the
 * dock, and the right column's occupant is whatever tab the route table
 * resolves. This app's right column hosts exactly one review, so the "which
 * review" fact is this store and the address model is not needed to carry it.
 *
 * The *per-tab view* state (selected file, split, wrap) is still the vendored
 * `review-store.ts`, keyed by tab id — so the vendored seeding rule, its
 * throw-on-missing-bucket rule, and one bucket per review all survive. What
 * lives here is only the seat: which key is current, and the instance the
 * vendored store hands back.
 *
 * The key is the review's coordinates, so reopening the same turn reuses that
 * review's bucket — the same continuity upstream gets from a tab id it does
 * not recreate for an address already open.
 */
import { useSyncExternalStore } from 'react'
import { create } from 'zustand'
import { createReviewStore } from '@vendor/ui-deliverables/review-store'

/** One open review and the file a navigation asked it to show. */
export interface OpenReview {
  readonly sessionId: string
  /** The 1-based turn number the review shows. */
  readonly turn: number
  /**
   * The summary index the opening navigation named, or undefined to keep
   * whatever the tab already showed. Passed through to the vendored store's
   * `navigated` action, which is what applies it exactly once.
   */
  readonly index: number | undefined
  /**
   * Bumped on every open of the same review, so a second click on a different
   * file of an already-open turn still applies its index (the vendored store
   * applies an index once per revision).
   */
  readonly revision: number
}

interface ReviewSeatState {
  readonly open: OpenReview | null
  readonly openReview: (sessionId: string, turn: number, index: number | undefined) => void
  readonly closeReview: () => void
}

/** The review store handle's one instance; its buckets key on the review's coordinates. */
const reviewStoreHandle = createReviewStore()

export const reviewStore = reviewStoreHandle.create()

/** The right column's occupant. */
export const useReviewSeat = create<ReviewSeatState>()((set, get) => ({
  open: null,

  openReview(sessionId, turn, index) {
    const current = get().open
    // Reopening the review already showing is a navigation, not a new seat:
    // the revision is what makes the vendored store re-apply the index.
    set({
      open: current !== null && current.sessionId === sessionId && current.turn === turn
        ? { ...current, index, revision: current.revision + 1 }
        : { sessionId, turn, index, revision: 0 },
    })
  },

  closeReview() {
    set({ open: null })
  },
}))

/**
 * The review the right column shows, or null while it is closed. The column's
 * presence follows this, which is also what the layout store's `rightbarShown`
 * reports.
 * @returns the open review, or null.
 */
export function useOpenReview(): OpenReview | null {
  return useReviewSeat((state) => state.open)
}

/**
 * The review store's key for one review. Coordinates rather than a minted id,
 * so reopening a turn restores that turn's chosen file and view flags.
 * @param sessionId - the viewed Session.
 * @param turn - the 1-based turn number.
 * @returns the store key.
 */
export function reviewTabId(sessionId: string, turn: number): string {
  return `${sessionId}/${turn}`
}

/**
 * One review tab's choices, re-read when the vendored store publishes.
 *
 * This is the React binding upstream synthesizes for the store seat. It reads
 * through `useSyncExternalStore` over the engine instance's own subscribe, so
 * the snapshot identity is stable between publishes and a component does not
 * re-render on an unrelated tab's write.
 * @param tabId - the review's store key.
 * @returns the tab's choices, or undefined before its first navigation.
 */
export function useReviewTabState(tabId: string) {
  return useSyncExternalStore(
    reviewStore.subscribe,
    () => reviewStore.getSnapshot().byTab[tabId],
    () => reviewStore.getSnapshot().byTab[tabId],
  )
}
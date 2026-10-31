import { create } from 'zustand'
import { fetchSpinnerVerbs } from '../api/client'
import type { SpinnerVerbsListing } from '../api/types'

/**
 * The TUI spinner's verb vocabulary (`GET /api/spinner-verbs`), loaded once
 * per page: the list is resolved when the gateway's process starts, so a
 * refetch could not observe a change. A failed load leaves the vocabulary
 * empty and the running status falls back to its localized label; the
 * next {@link SpinnerVerbsStore.load} call retries.
 */

export interface SpinnerVerbsStore {
  readonly vocabulary: SpinnerVerbsListing
  load(): Promise<void>
}

const EMPTY: SpinnerVerbsListing = { active: [], tools: {} }

let inFlight: Promise<void> | null = null

export const useSpinnerVerbs = create<SpinnerVerbsStore>((set, get) => ({
  vocabulary: EMPTY,

  load() {
    if (get().vocabulary.active.length > 0) return Promise.resolve()
    inFlight ??= fetchSpinnerVerbs()
      .then((vocabulary) => { set({ vocabulary }) })
      .catch(() => { /* keep the fallback label; a later load retries */ })
      .finally(() => { inFlight = null })
    return inFlight
  },
}))

import { create } from 'zustand'
import { fetchFileSuggestions } from '../api/client'
import type { FileCandidate } from '../api/types'

/**
 * The composer `@` menu's candidate source (`GET /api/file-suggestions`).
 *
 * One request per query, with the superseded answers dropped: each fetch
 * takes a generation number and only the newest generation may write, so a
 * slow answer for `@src` cannot overwrite the rows for the `@src/comp` the
 * user has since typed. The store is deliberately not a cache — the menu
 * opens fresh per `@` token, and the gateway's own enumeration is cached
 * behind the endpoint.
 */

export interface FileSuggestionsStore {
  /** The rows for the current query; empty while nothing has answered yet. */
  readonly files: readonly FileCandidate[]
  /** True while a fetch for {@link query} is outstanding. */
  readonly pending: boolean
  /** The query the current rows answer. */
  readonly query: string
  /** Fetch candidates for `query`, superseding any in-flight request. */
  search(query: string): Promise<void>
  /** Drop the rows (the menu closed). */
  reset(): void
}

/** Only the newest search may publish; older answers are discarded. */
let generation = 0

export const useFileSuggestions = create<FileSuggestionsStore>((set) => ({
  files: [],
  pending: false,
  query: '',

  async search(query: string) {
    const mine = ++generation
    set({ pending: true, query })
    try {
      const listing = await fetchFileSuggestions(query)
      if (mine !== generation) return
      set({ files: listing.files, pending: false })
    } catch {
      if (mine !== generation) return
      // A failed lookup yields no rows (dsh's quiet failure): the menu shows
      // nothing rather than an error card, and typing retries.
      set({ files: [], pending: false })
    }
  },

  reset() {
    generation += 1
    set({ files: [], pending: false, query: '' })
  },
}))
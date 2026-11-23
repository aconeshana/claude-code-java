import { create } from 'zustand'
import { fetchComposerCommands } from '../api/client'
import type { ComposerCommandEntry } from '../api/types'

/**
 * The composer command catalogue (`GET /api/commands`) shared by both the "+"
 * button menu and the typed `/` trigger.
 *
 * Stale-while-revalidate: the first open shows pending skeleton rows while the
 * listing loads; subsequent opens reuse the cached list immediately for instant
 * client-side fuzzy ranking while refreshing in the background so dynamically
 * loaded skills or MCP prompts appear without a page reload.
 */

export interface ComposerCommandsStore {
  /** Cached command and skill entries from the latest successful fetch. */
  readonly commands: readonly ComposerCommandEntry[]
  /** True once at least one fetch has succeeded. */
  readonly loaded: boolean
  /** True while a fetch is currently in flight. */
  readonly pending: boolean
  /** True when the latest fetch failed and no cached entries are available. */
  readonly failed: boolean
  /** Fetch the latest command listing, superseding any in-flight request. */
  refresh(): Promise<void>
  /** Reset store state (for tests). */
  reset(): void
}

/** Only the newest refresh may publish; older answers are discarded. */
let generation = 0

export const useComposerCommands = create<ComposerCommandsStore>((set, get) => ({
  commands: [],
  loaded: false,
  pending: false,
  failed: false,

  async refresh() {
    const mine = ++generation
    set({ pending: true, failed: false })
    try {
      const listing = await fetchComposerCommands()
      if (mine !== generation) return
      set({ commands: listing.commands, loaded: true, pending: false, failed: false })
    } catch {
      if (mine !== generation) return
      // Keep stale commands if we already loaded them; only mark failed when
      // nothing is cached so an initial failure auto-closes the menu quietly.
      set({ pending: false, failed: !get().loaded })
    }
  },

  reset() {
    generation += 1
    set({ commands: [], loaded: false, pending: false, failed: false })
  },
}))

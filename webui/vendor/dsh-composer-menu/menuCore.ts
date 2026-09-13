/**
 * Menu reduction pure core — vendored from dsh's ui-input-trigger
 * `src/core/menu.ts` with the multi-source generation-gate protocol kept and
 * the source/cordis surface trimmed to this app's single command source.
 *
 * One group per source; empty ready groups auto-close. Zero React / DOM.
 * Stale or no-op events return the same state reference so subscribers skip
 * re-renders. Kept verbatim where it still applies: the stale-while-revalidate
 * refinement (a hit while open keeps the previous items rendered with the
 * highlight parked while the new fetch runs), the shared pointer/keyboard
 * highlight (last input wins), and the wrap-around move cycle.
 */

/** One menu candidate. Pure display data — zero behavior declaration. */
export interface MenuCandidate {
  /** Identity: the pick payload and the exact-match key. */
  readonly name: string
  readonly description?: string
  /** Reference glyph token, or an icon component from the shared icon set. */
  readonly icon?: 'file' | 'folder' | 'session' | MenuIconComponent
  readonly hint?: string
  /** Optional visual heading shared by adjacent candidates. */
  readonly section?: string
  /** The command name as a trailing alias when a localized label differs. */
  readonly label?: string
  /** Opaque pick payload distinguishing the row kinds ('file' | 'command'). */
  readonly value?: string
}

/** An icon component from the vendored shared icon set (IconProps shape). */
export interface MenuIconComponent {
  (props: { size?: number }): React.ReactNode
}

/** One source group's state in the open menu. */
export interface MenuGroup {
  readonly source: string
  readonly showGroupTitle?: false
  readonly status: 'pending' | 'ready'
  readonly items: readonly MenuCandidate[]
}

/** The whole menu state; `highlight` names one ready (source, index) cell. */
export interface MenuState {
  readonly open: boolean
  readonly generation: number
  readonly groups: readonly MenuGroup[]
  readonly highlight: { readonly source: string; readonly index: number } | null
}

/** Reducer events. */
export type MenuEvent =
  | { readonly type: 'hit' }
  | { readonly type: 'source-settled'; readonly generation: number; readonly source: string; readonly items: readonly MenuCandidate[] }
  | { readonly type: 'source-failed'; readonly generation: number; readonly source: string }
  | { readonly type: 'move'; readonly dir: 1 | -1 }
  | { readonly type: 'hover'; readonly source: string; readonly index: number }
  | { readonly type: 'close' }

/** Closed rest state with generation 0. */
export const MENU_CLOSED: MenuState = { open: false, generation: 0, groups: [], highlight: null }

/** Close, preserving the generation so in-flight settlements stay droppable. */
const closed = (state: MenuState): MenuState =>
  state.open || state.groups.length > 0 || state.highlight !== null
    ? { open: false, generation: state.generation, groups: [], highlight: null }
    : state

/** First item of the first non-empty ready group, or null. */
function firstHighlight(groups: MenuState['groups']): MenuState['highlight'] {
  for (const g of groups) {
    if (g.status === 'ready' && g.items.length > 0) return { source: g.source, index: 0 }
  }
  return null
}

/** The highlight itself when it still points at a ready item, else null. */
function validHighlight(highlight: MenuState['highlight'], groups: MenuState['groups']): MenuState['highlight'] {
  if (highlight == null) return null
  const g = groups.find(x => x.source === highlight.source)
  return g != null && g.status === 'ready' && highlight.index < g.items.length ? highlight : null
}

/** Flatten ready items into (source, index) positions in group order. */
function positions(groups: MenuState['groups']): { source: string; index: number }[] {
  const out: { source: string; index: number }[] = []
  for (const g of groups) {
    if (g.status !== 'ready') continue
    for (let i = 0; i < g.items.length; i++) out.push({ source: g.source, index: i })
  }
  return out
}

/** True when every group is ready with zero items (the auto-close condition). */
const allReadyEmpty = (groups: MenuState['groups']): boolean =>
  groups.every(g => g.status === 'ready' && g.items.length === 0)

/**
 * Pure menu reducer. `hit` opens a new generation (pending groups, items and
 * highlight survive a refinement — stale-while-revalidate); `source-settled`
 * outside the current generation or the open menu is dropped; a settlement
 * leaving every group ready-and-empty auto-closes; `source-failed` silently
 * removes the group; `move` cycles the highlight across ready items; `hover`
 * parks it on one ready item (pointer and keyboard share the single
 * highlight — last input wins).
 */
export const menuReduce = (state: MenuState, ev: MenuEvent): MenuState => {
  switch (ev.type) {
    case 'hit': {
      return {
        open: true,
        generation: state.generation + 1,
        // Items and highlight survive the refinement (stale-while-revalidate):
        // the previous query's candidates stay rendered with the highlight
        // parked where it was while the new fetch runs; pending status still
        // fences picks off the stale rows.
        groups: state.groups.map(g => ({ ...g, status: 'pending' as const })),
        highlight: state.highlight,
      }
    }
    case 'source-settled': {
      if (!state.open || ev.generation !== state.generation) return state
      const idx = state.groups.findIndex(g => g.source === ev.source)
      if (idx < 0) return state
      const items: readonly MenuCandidate[] = ev.items ?? []
      const groups = state.groups.map((g, i) =>
        i === idx ? { ...g, status: 'ready' as const, items } : g)
      if (allReadyEmpty(groups)) return closed(state)
      const highlight = validHighlight(state.highlight, groups) ?? firstHighlight(groups)
      return { ...state, groups, highlight }
    }
    case 'source-failed': {
      if (!state.open || ev.generation !== state.generation) return state
      if (!state.groups.some(g => g.source === ev.source)) return state
      const groups = state.groups.filter(g => g.source !== ev.source)
      if (groups.length === 0 || allReadyEmpty(groups)) return closed(state)
      const highlight = validHighlight(state.highlight, groups) ?? firstHighlight(groups)
      return { ...state, groups, highlight }
    }
    case 'move': {
      if (!state.open) return state
      const pos = positions(state.groups)
      if (pos.length === 0) return state
      const hl = state.highlight
      const at = hl != null ? pos.findIndex(p => p.source === hl.source && p.index === hl.index) : -1
      const next = pos[at < 0
        ? (ev.dir === 1 ? 0 : pos.length - 1)
        : (at + ev.dir + pos.length) % pos.length]
      if (next === undefined) return state
      if (hl != null && next.source === hl.source && next.index === hl.index) return state
      return { ...state, highlight: next }
    }
    case 'hover': {
      if (!state.open) return state
      const target = validHighlight({ source: ev.source, index: ev.index }, state.groups)
      if (target === null) return state
      const hl = state.highlight
      if (hl != null && hl.source === target.source && hl.index === target.index) return state
      return { ...state, highlight: target }
    }
    case 'close':
      return closed(state)
  }
}

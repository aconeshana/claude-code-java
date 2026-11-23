import { create } from 'zustand'
import {
  applyOp,
  createIdMinter,
  createInitialState,
  dockPaneCount,
  dockPaneIds,
  findTabPane,
  getPane,
  getTab,
  planAddTab,
  planDropTab,
  planDuplicateTab,
  planFloatTab,
  planOpenContent,
  planPlaceTab,
  planResizeSplit,
  planSetExpanded,
  planSettle,
  planSplitPane,
  planUnfloatPane,
  type DockIntents,
  type DockZone,
  type FloatRect,
  type LayoutOp,
  type LayoutState,
  type Mint,
  type PaneId,
  type SplitId,
  type TabFactory,
  type TabId,
} from '@vendor/ui-dockkit'

/** Right sidebar presentation mode (matches upstream `push` vs `fullscreen`). */
export type RightSidebarPresentation = 'push' | 'fullscreen'

/** Supported tab kinds in this product's right dock. */
export type RightTabKind = 'guide' | 'review' | 'files' | 'file' | 'subagent' | 'shell'

export interface SessionDockSurface {
  readonly state: LayoutState
  readonly seed: number
  readonly presentation: RightSidebarPresentation
}

export interface OpenTabInput {
  readonly kind: RightTabKind
  readonly contentId: string
  readonly title: string
  readonly paneId?: PaneId
  readonly replaceTabId?: TabId
  readonly revealIfOpened?: boolean
}

const STORAGE_PREFIX = 'poco.sidebar-right.v1.'
/** Upstream product policy: at most 2 horizontal docked panes, 20%–80% split range. */
export const RIGHT_DOCK_MAX_PANES = 2
export const RIGHT_DOCK_MIN_FRACTION = 0.2

const makeGuideTab: TabFactory = (id) => ({
  id,
  kind: 'guide',
  contentId: 'guide',
  title: '新标签页',
})

function createTrackedMinter(seed: number): { readonly next: Mint; count: () => number } {
  let current = seed
  const base = createIdMinter(seed)
  const next = ((prefix: string) => {
    current += 1
    return base.next(prefix as 'tab')
  }) as Mint
  return { next, count: () => current }
}

function emptySurface(): SessionDockSurface {
  const minter = createTrackedMinter(0)
  const initial = createInitialState(minter, undefined, 'push')
  return { state: initial, seed: minter.count(), presentation: 'push' }
}

function isValidPersistedSurface(raw: unknown): raw is SessionDockSurface {
  if (typeof raw !== 'object' || raw === null) return false
  const rec = raw as Record<string, unknown>
  if (typeof rec.seed !== 'number' || !Number.isFinite(rec.seed) || rec.seed < 0) return false
  if (rec.presentation !== 'push' && rec.presentation !== 'fullscreen') return false
  const st = rec.state as Record<string, unknown> | null | undefined
  if (typeof st !== 'object' || st === null) return false
  if (typeof st.rootId !== 'string' || typeof st.activePaneId !== 'string') return false
  if (typeof st.expanded !== 'boolean') return false
  if (typeof st.nodes !== 'object' || st.nodes === null) return false
  if (typeof st.tabs !== 'object' || st.tabs === null) return false
  if (!Array.isArray(st.floats)) return false
  return Object.prototype.hasOwnProperty.call(st.nodes, st.rootId)
    && Object.prototype.hasOwnProperty.call(st.nodes, st.activePaneId)
}

function loadPersistedSurface(sessionId: string): SessionDockSurface {
  if (typeof window === 'undefined') return emptySurface()
  try {
    const raw = window.localStorage.getItem(`${STORAGE_PREFIX}${sessionId}`)
    if (raw === null) return emptySurface()
    const parsed: unknown = JSON.parse(raw)
    if (isValidPersistedSurface(parsed)) return parsed
    window.localStorage.removeItem(`${STORAGE_PREFIX}${sessionId}`)
  } catch {
    // Ignore storage quota / security / parse failures and fall back to memory.
  }
  return emptySurface()
}

function savePersistedSurface(sessionId: string, surface: SessionDockSurface): void {
  if (typeof window === 'undefined') return
  try {
    window.localStorage.setItem(`${STORAGE_PREFIX}${sessionId}`, JSON.stringify(surface))
  } catch {
    // Storage failures leave the in-memory layout usable.
  }
}

function applyOps(
  surface: SessionDockSurface,
  run: (state: LayoutState, mint: Mint) => readonly LayoutOp[],
  settleWhenExpanded = true,
  nextPresentation?: RightSidebarPresentation,
): SessionDockSurface {
  const minter = createTrackedMinter(surface.seed)
  const ops = run(surface.state, minter.next)
  let current = surface.state
  for (const op of ops) {
    const result = applyOp(current, op)
    current = result.state
  }
  if (settleWhenExpanded && current.expanded) {
    const settleOps = planSettle(current, minter.next, makeGuideTab)
    for (const op of settleOps) {
      current = applyOp(current, op).state
    }
  }
  const presentation = nextPresentation ?? surface.presentation
  const nextSeed = minter.count()
  if (current === surface.state && nextSeed === surface.seed && presentation === surface.presentation) {
    return surface
  }
  return { state: current, seed: nextSeed, presentation }
}

/** Count all tabs currently held in docked (non-floating) panes. */
function dockedTabCount(state: LayoutState): number {
  let total = 0
  for (const paneId of dockPaneIds(state)) {
    total += getPane(state, paneId).tabs.length
  }
  return total
}

interface RightSidebarStoreState {
  surfaces: Readonly<Record<string, SessionDockSurface>>
  ensure: (sessionId: string) => SessionDockSurface
  expand: (sessionId: string, presentation?: RightSidebarPresentation) => void
  collapse: (sessionId: string) => void
  setPresentation: (sessionId: string, presentation: RightSidebarPresentation) => void
  togglePresentation: (sessionId: string) => void
  openTab: (sessionId: string, input: OpenTabInput) => TabId | null
  focusTab: (sessionId: string, tabId: TabId) => void
  focusPane: (sessionId: string, paneId: PaneId) => void
  splitPane: (sessionId: string, paneId: PaneId) => void
  addGuideTab: (sessionId: string, paneId: PaneId) => void
  closeTab: (sessionId: string, tabId: TabId) => void
  closeOtherTabs: (sessionId: string, tabId: TabId) => void
  closeTabsToRight: (sessionId: string, tabId: TabId) => void
  duplicateTab: (sessionId: string, tabId: TabId) => void
  floatTab: (sessionId: string, tabId: TabId, rect?: FloatRect) => void
  unfloatPane: (sessionId: string, paneId: PaneId) => void
  placeTab: (sessionId: string, tabId: TabId, toPaneId: PaneId, index: number) => void
  dropTab: (sessionId: string, tabId: TabId, paneId: PaneId, zone: DockZone) => void
  moveFloat: (sessionId: string, paneId: PaneId, x: number, y: number) => void
  resizeFloat: (sessionId: string, paneId: PaneId, rect: FloatRect) => void
  resizeSplit: (sessionId: string, splitId: SplitId, sizes: readonly number[]) => void
}

export const useRightSidebar = create<RightSidebarStoreState>((set, get) => {
  const mutate = (
    sessionId: string,
    updater: (surface: SessionDockSurface) => SessionDockSurface,
  ) => {
    const current = get().surfaces[sessionId] ?? loadPersistedSurface(sessionId)
    const next = updater(current)
    if (next === current && get().surfaces[sessionId] !== undefined) return
    savePersistedSurface(sessionId, next)
    set((s) => ({ surfaces: { ...s.surfaces, [sessionId]: next } }))
  }

  return {
    surfaces: {},

    ensure: (sessionId) => {
      const existing = get().surfaces[sessionId]
      if (existing !== undefined) return existing
      const loaded = loadPersistedSurface(sessionId)
      set((s) => ({ surfaces: { ...s.surfaces, [sessionId]: loaded } }))
      return loaded
    },

    expand: (sessionId, presentation) => {
      mutate(sessionId, (surface) =>
        applyOps(
          surface,
          (state) => planSetExpanded(state, true),
          true,
          presentation ?? surface.presentation,
        ),
      )
    },

    collapse: (sessionId) => {
      mutate(sessionId, (surface) =>
        applyOps(surface, (state) => planSetExpanded(state, false), false, 'push'),
      )
    },

    setPresentation: (sessionId, presentation) => {
      mutate(sessionId, (surface) =>
        surface.presentation === presentation ? surface : { ...surface, presentation },
      )
    },

    togglePresentation: (sessionId) => {
      mutate(sessionId, (surface) => ({
        ...surface,
        presentation: surface.presentation === 'fullscreen' ? 'push' : 'fullscreen',
      }))
    },

    openTab: (sessionId, input) => {
      let openedId: TabId | null = null
      mutate(sessionId, (surface) =>
        applyOps(surface, (state, minter) => {
          const ops: LayoutOp[] = [...planSetExpanded(state, true)]
          let working = state
          for (const op of ops) working = applyOp(working, op).state

          const planned = planOpenContent(working, minter, {
            kind: input.kind,
            contentId: input.contentId,
            title: input.title,
            ...(input.paneId !== undefined ? { paneId: input.paneId } : {}),
            ...(input.revealIfOpened !== undefined ? { revealIfOpened: input.revealIfOpened } : {}),
          })
          openedId = planned.tabId
          for (const op of planned.ops) {
            ops.push(op)
            working = applyOp(working, op).state
          }

          if (input.replaceTabId !== undefined && input.replaceTabId !== planned.tabId) {
            if (working.tabs[input.replaceTabId] !== undefined) {
              ops.push({ type: 'closeTab', tabId: input.replaceTabId })
            }
          }
          return ops
        }),
      )
      return openedId
    },

    focusTab: (sessionId, tabId) => {
      mutate(sessionId, (surface) =>
        applyOps(surface, (state) =>
          state.tabs[tabId] === undefined ? [] : [{ type: 'focusTab', tabId }],
        ),
      )
    },

    focusPane: (sessionId, paneId) => {
      mutate(sessionId, (surface) =>
        applyOps(surface, (state) =>
          state.nodes[paneId] === undefined ? [] : [{ type: 'focusPane', paneId }],
        ),
      )
    },

    splitPane: (sessionId, paneId) => {
      mutate(sessionId, (surface) =>
        applyOps(surface, (state, minter) => {
          if (dockPaneCount(state) >= RIGHT_DOCK_MAX_PANES) return []
          return planSplitPane(state, minter, paneId, makeGuideTab)
        }),
      )
    },

    addGuideTab: (sessionId, paneId) => {
      mutate(sessionId, (surface) =>
        applyOps(surface, (state, minter) => {
          const pane = getPane(state, paneId)
          const existingGuide = pane.tabs.find((id) => state.tabs[id]?.kind === 'guide')
          if (existingGuide !== undefined) {
            return [{ type: 'focusTab', tabId: existingGuide }]
          }
          return planAddTab(state, minter, paneId, makeGuideTab)
        }),
      )
    },

    closeTab: (sessionId, tabId) => {
      mutate(sessionId, (surface) => {
        const tab = surface.state.tabs[tabId]
        if (tab === undefined) return surface
        const pane = findTabPane(surface.state, tabId)
        if (pane.host === 'dock' && dockedTabCount(surface.state) === 1) {
          // Sole docked tab rule: a lone guide cannot close; any other sole tab
          // closes together with the right column in one step.
          if (tab.kind === 'guide') return surface
          return applyOps(
            surface,
            () => [{ type: 'closeTab', tabId }, { type: 'setExpanded', expanded: false }],
            false,
            'push',
          )
        }
        return applyOps(surface, () => [{ type: 'closeTab', tabId }], true)
      })
    },

    closeOtherTabs: (sessionId, tabId) => {
      mutate(sessionId, (surface) => {
        if (surface.state.tabs[tabId] === undefined) return surface
        const pane = findTabPane(surface.state, tabId)
        const others = pane.tabs.filter((id) => id !== tabId)
        if (others.length === 0) return surface
        return applyOps(
          surface,
          () => [
            ...others.map((id): LayoutOp => ({ type: 'closeTab', tabId: id })),
            { type: 'focusTab', tabId },
          ],
          true,
        )
      })
    },

    closeTabsToRight: (sessionId, tabId) => {
      mutate(sessionId, (surface) => {
        if (surface.state.tabs[tabId] === undefined) return surface
        const pane = findTabPane(surface.state, tabId)
        const idx = pane.tabs.indexOf(tabId)
        if (idx < 0 || idx === pane.tabs.length - 1) return surface
        const toClose = pane.tabs.slice(idx + 1)
        return applyOps(
          surface,
          () => toClose.map((id): LayoutOp => ({ type: 'closeTab', tabId: id })),
          true,
        )
      })
    },

    duplicateTab: (sessionId, tabId) => {
      mutate(sessionId, (surface) => {
        const tab = surface.state.tabs[tabId]
        if (tab === undefined || tab.kind === 'guide') return surface
        return applyOps(surface, (state, mint) => planDuplicateTab(state, mint, tabId).ops, true)
      })
    },

    floatTab: (sessionId, tabId, rect) => {
      mutate(sessionId, (surface) =>
        applyOps(surface, (state, mint) => planFloatTab(state, mint, tabId, rect).ops, true),
      )
    },

    unfloatPane: (sessionId, paneId) => {
      mutate(sessionId, (surface) =>
        applyOps(surface, (state) => planUnfloatPane(state, paneId), true),
      )
    },

    placeTab: (sessionId, tabId, toPaneId, index) => {
      mutate(sessionId, (surface) =>
        applyOps(surface, (state) => planPlaceTab(state, tabId, toPaneId, index), true),
      )
    },

    dropTab: (sessionId, tabId, paneId, zone) => {
      mutate(sessionId, (surface) =>
        applyOps(surface, (state, minter) => planDropTab(state, minter, tabId, paneId, zone, makeGuideTab), true),
      )
    },

    moveFloat: (sessionId, paneId, x, y) => {
      mutate(sessionId, (surface) =>
        applyOps(surface, () => [{ type: 'moveFloat', paneId, x, y }], false),
      )
    },

    resizeFloat: (sessionId, paneId, rect) => {
      mutate(sessionId, (surface) =>
        applyOps(surface, () => [{ type: 'resizeFloat', paneId, rect }], false),
      )
    },

    resizeSplit: (sessionId, splitId, sizes) => {
      mutate(sessionId, (surface) =>
        applyOps(surface, () => planResizeSplit(splitId, sizes, RIGHT_DOCK_MIN_FRACTION), false),
      )
    },
  }
})

/** Whether a pane may show the `+` add-guide button (false if it already holds a guide). */
export function canAddTabInPane(state: LayoutState, paneId: PaneId): boolean {
  const pane = getPane(state, paneId)
  return !pane.tabs.some((id) => state.tabs[id]?.kind === 'guide')
}

/** Whether a tab may be closed (false for a lone docked guide). */
export function canCloseTabInSurface(state: LayoutState, tabId: TabId): boolean {
  const tab = getTab(state, tabId)
  const pane = findTabPane(state, tabId)
  if (pane.host === 'dock' && dockedTabCount(state) === 1 && tab.kind === 'guide') {
    return false
  }
  return true
}

/** Build a `DockIntents` adapter bound to one session id. */
export function createDockIntents(sessionId: string): DockIntents {
  const actions = useRightSidebar.getState()
  return {
    focusTab: (tabId) => { actions.focusTab(sessionId, tabId) },
    focusPane: (paneId) => { actions.focusPane(sessionId, paneId) },
    splitPane: (paneId) => { actions.splitPane(sessionId, paneId) },
    addTab: (paneId) => { actions.addGuideTab(sessionId, paneId) },
    closeTab: (tabId) => { actions.closeTab(sessionId, tabId) },
    duplicateTab: (tabId) => { actions.duplicateTab(sessionId, tabId) },
    floatTab: (tabId, rect) => { actions.floatTab(sessionId, tabId, rect) },
    unfloatPane: (paneId) => { actions.unfloatPane(sessionId, paneId) },
    placeTab: (tabId, toPaneId, index) => { actions.placeTab(sessionId, tabId, toPaneId, index) },
    dropTab: (tabId, paneId, zone) => { actions.dropTab(sessionId, tabId, paneId, zone) },
    moveFloat: (paneId, x, y) => { actions.moveFloat(sessionId, paneId, x, y) },
    resizeFloat: (paneId, rect) => { actions.resizeFloat(sessionId, paneId, rect) },
    resizeSplit: (splitId, sizes) => { actions.resizeSplit(sessionId, splitId, sizes) },
  }
}

/** Close any open tabs matching `contentId` in the given session's dock surface. */
export function closeContentInRightDock(sessionId: string, contentId: string): void {
  const store = useRightSidebar.getState()
  const surface = store.surfaces[sessionId]
  if (surface === undefined) return
  for (const tab of Object.values(surface.state.tabs)) {
    if (tab.contentId === contentId) {
      store.closeTab(sessionId, tab.id)
    }
  }
}

/** Convenience openers used by conversation cards, menus, and the guide tab. */
export function openReviewInRightDock(sessionId: string, turn: number, resetDock = false): void {
  if (resetDock) {
    const fresh = emptySurface()
    useRightSidebar.setState((s) => ({
      surfaces: { ...s.surfaces, [sessionId]: fresh },
    }))
    savePersistedSurface(sessionId, fresh)
  }
  useRightSidebar.getState().openTab(sessionId, {
    kind: 'review',
    contentId: `review:${sessionId}:${turn}`,
    title: '改动',
  })
}

export function openFileInRightDock(sessionId: string, path: string, paneId?: PaneId, replaceTabId?: TabId): void {
  const name = path.split('/').filter(Boolean).at(-1) ?? path
  const current = useRightSidebar.getState().surfaces[sessionId]
  // If the session's dock is collapsed and only contains stale tabs (no live open review),
  // start from a clean surface.
  if (current !== undefined && !current.state.expanded) {
    const fresh = emptySurface()
    useRightSidebar.setState((s) => ({
      surfaces: { ...s.surfaces, [sessionId]: fresh },
    }))
    savePersistedSurface(sessionId, fresh)
  }
  useRightSidebar.getState().openTab(sessionId, {
    kind: 'file',
    contentId: `file:${sessionId}:${path}`,
    title: name,
    ...(paneId !== undefined ? { paneId } : {}),
    ...(replaceTabId !== undefined ? { replaceTabId } : {}),
  })
}

export function openFilesTreeInRightDock(sessionId: string, paneId?: PaneId, replaceTabId?: TabId): void {
  useRightSidebar.getState().openTab(sessionId, {
    kind: 'files',
    contentId: `files:${sessionId}`,
    title: '文件',
    ...(paneId !== undefined ? { paneId } : {}),
    ...(replaceTabId !== undefined ? { replaceTabId } : {}),
  })
}

export function openSubagentInRightDock(
  sessionId: string,
  agentId: string,
  title: string,
  paneId?: PaneId,
  replaceTabId?: TabId,
): void {
  useRightSidebar.getState().openTab(sessionId, {
    kind: 'subagent',
    contentId: `subagent:${sessionId}:${agentId}`,
    title: title || agentId,
    ...(paneId !== undefined ? { paneId } : {}),
    ...(replaceTabId !== undefined ? { replaceTabId } : {}),
  })
}

export function openShellInRightDock(
  sessionId: string,
  taskId: string,
  title: string,
  paneId?: PaneId,
  replaceTabId?: TabId,
): void {
  useRightSidebar.getState().openTab(sessionId, {
    kind: 'shell',
    contentId: `shell:${taskId}`,
    title: title || taskId,
    ...(paneId !== undefined ? { paneId } : {}),
    ...(replaceTabId !== undefined ? { replaceTabId } : {}),
  })
}

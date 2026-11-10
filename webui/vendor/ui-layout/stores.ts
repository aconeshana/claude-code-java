/**
 * Frame measurement, panel preferences, and presentation reports — upstream
 * `packages/client/ui-layout/src/client/stores.ts` at the pinned commit
 * (`5dda764`), reduced.
 *
 * Everything upstream keeps here is kept: the whole `LayoutState` with its
 * unchanged field docs, `clampWidth` bounds, the sidebar-preference-is-the-width
 * rule, the narrow-expansion override and the breakpoint-crossing rule that
 * drops it, the first-open 45% right panel, and the `rightbarInstant`
 * suppression logic around a fullscreen exit. Those are the behaviours the
 * right column is built on.
 *
 * Cut from upstream's file (recorded in `webui/UPSTREAM.md`):
 *
 * - The `@deepseek-ai/dsh-client-store` import — repointed at `@vendor/store`,
 *   the vendored engine.
 * - `window.innerWidth` is read through {@link viewportWidth}. Upstream reads
 *   the global directly; this project's vitest runs in jsdom where `window`
 *   exists, but the store must also construct in a plain node context (the
 *   wire harness imports app modules), so the read is guarded and falls back to
 *   the sidebar-autocollapse width. Upstream never needed the guard because its
 *   store is only ever built by the browser half.
 *
 * Upstream master has since grown a `panelInfo.activePanelId` / `selectPanel` /
 * `retainMainPanels` group (the global-panel switcher, typed by `MainPanelId`
 * from `service.ts`). The pinned revision has none of it, and this app has no
 * global main panels to switch between, so nothing is dropped by staying on the
 * pin — see the base note in `columns.ts`.
 */
import { defineStore, type EngineStoreHandle } from '@vendor/store'
import {
  clampWidth, RIGHTBAR_DEFAULT_RATIO, RIGHTBAR_MAX_RATIO, RIGHTBAR_MIN,
  SIDEBAR_AUTO_COLLAPSE, SIDEBAR_DEFAULT, SIDEBAR_MAX, SIDEBAR_MIN,
} from './columns.ts'

/**
 * The last positive frame measurement; window width bootstraps the first render.
 * @returns the measured viewport width, or the auto-collapse breakpoint when there is no window.
 */
function viewportWidth(): number {
  return typeof window === 'undefined' ? SIDEBAR_AUTO_COLLAPSE : window.innerWidth
}

/**
 * Transient layout preferences. Responsive concessions never rewrite widths;
 * the right panel's expanded state belongs to its occupant.
 */
type LayoutState = {
  sidebar: number
  /** Last positive frame measurement; window width bootstraps the first render. */
  viewportWidth: number
  narrowExpanded: boolean
  /**
   * Saved right panel width in px, or null before its first opening. Resizing
   * the frame and closing the panel preserve this preference.
   */
  rightbar: number | null
  /**
   * Whether the right panel is drawn at all, in either presentation.
   *
   * Derived chrome, not a source of truth: whether the right surface is
   * expanded is a recorded fact owned by that surface, reported here so the
   * frame can place the panel's resize handle. The occupant reports it; nothing
   * else writes it.
   */
  rightbarShown: boolean
  /**
   * Whether the normal panel width reserves a grid track, including beneath
   * fullscreen. Reported by the occupant; always false while hidden.
   */
  rightbarTrack: boolean
  /** Reported fullscreen presentation; hides the outer resize handle. */
  rightbarFullscreen: boolean
  /** Suppress transitions for a fullscreen exit until another geometry action. */
  rightbarInstant: boolean
}

/**
 * Annotation twin of the actions literal below (the export needs a declared
 * return type); drift fails assignability at the defineStore call.
 */
type LayoutActions = {
  setSidebar: (draft: LayoutState, px: number) => void
  toggleSidebar: (draft: LayoutState) => void
  setViewportWidth: (draft: LayoutState, width: number) => void
  setRightbar: (draft: LayoutState, px: number) => void
  openRightbar: (draft: LayoutState, track: boolean, fullscreen: boolean) => void
  closeRightbar: (draft: LayoutState) => void
}

/**
 * Create the layout panel store handle. For the sidebar the preference IS the
 * width, so closing it forgets its drag width — reopening restores the contract
 * default. The right panel initializes at 45% of the frame on first opening
 * and keeps that px preference across resizes and close. Drag writes clamp to
 * the current frame's range. Narrow sidebar toggles change only the expansion
 * override; opening the right panel clears that override.
 * @returns the store handle (spec + type + identity + factory in one).
 */
export function createLayoutStore(): EngineStoreHandle<LayoutState, LayoutActions>  {
  const handle = defineStore({
    init: (): LayoutState => ({
      sidebar: SIDEBAR_DEFAULT,
      viewportWidth: viewportWidth(),
      narrowExpanded: false,
      rightbar: null,
      rightbarShown: false,
      rightbarTrack: false,
      rightbarFullscreen: false,
      rightbarInstant: false,
    }),
    actions: {
      setSidebar: (d, px: number) => {
        d.rightbarInstant = false
        d.sidebar = clampWidth(px, SIDEBAR_MIN, SIDEBAR_MAX)
      },
      // Narrow toggles flip only the override: the width preference survives
      // untouched, so re-widening restores the pre-squeeze layout.
      toggleSidebar: (d) => {
        d.rightbarInstant = false
        if (d.viewportWidth < SIDEBAR_AUTO_COLLAPSE) d.narrowExpanded = !d.narrowExpanded
        else d.sidebar = d.sidebar === 0 ? SIDEBAR_DEFAULT : 0
      },
      // Crossing the breakpoint in either direction drops the override: the
      // narrow default is auto-collapsed, the wide state is the preference.
      setViewportWidth: (d, width: number) => {
        if (d.viewportWidth === width) return
        d.rightbarInstant = false
        if ((d.viewportWidth < SIDEBAR_AUTO_COLLAPSE) !== (width < SIDEBAR_AUTO_COLLAPSE)) {
          d.narrowExpanded = false
        }
        d.viewportWidth = width
      },
      setRightbar: (d, px: number) => {
        d.rightbarInstant = false
        d.rightbar = clampWidth(px, RIGHTBAR_MIN, Math.max(RIGHTBAR_MIN, d.viewportWidth * RIGHTBAR_MAX_RATIO))
      },
      openRightbar: (d, track: boolean, fullscreen: boolean) => {
        if (!d.rightbarShown || d.rightbarTrack !== track || d.rightbarFullscreen !== fullscreen) {
          d.rightbarInstant = d.rightbarFullscreen && !fullscreen
        }
        if (!d.rightbarShown && d.viewportWidth < SIDEBAR_AUTO_COLLAPSE) d.narrowExpanded = false
        d.rightbar ??= Math.max(RIGHTBAR_MIN, Math.round(d.viewportWidth * RIGHTBAR_DEFAULT_RATIO))
        d.rightbarShown = true
        d.rightbarTrack = track
        d.rightbarFullscreen = fullscreen
      },
      closeRightbar: (d) => {
        if (d.rightbarShown) d.rightbarInstant = d.rightbarFullscreen
        d.rightbarShown = false
        d.rightbarTrack = false
        d.rightbarFullscreen = false
      },
    },
  })
  return handle
}

export type { LayoutState, LayoutActions }
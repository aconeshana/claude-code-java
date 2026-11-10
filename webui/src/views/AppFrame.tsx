import { useEffect, type ReactNode } from 'react'
import {
  CENTER_MIN, RIGHTBAR_DEFAULT_RATIO, RIGHTBAR_MAX_RATIO, RIGHTBAR_MIN, clampWidth, computeColumns,
  layoutActions, useLayout,
} from '@vendor/ui-layout'
import css from '@chat-styles/AppFrame.module.css'

/** Default wide-sidebar track width; Sidebar.tsx freezes its content to this width while collapsing. */
export const SIDEBAR_WIDTH = 260

/**
 * The rail track width is not restated here: `computeColumns` substitutes
 * `SIDEBAR_COLLAPSED` (56) for a zero sidebar preference, and that constant
 * matches `SidebarRoot.module.css`'s collapsed geometry.
 */

/** What the frame hands its right column occupant (upstream's `RightbarOwnerProps`). */
export interface RightbarOwnerProps {
  /** Resolved normal panel width in px, not the saved preference; zero if it cannot fit. */
  width: number
  /** Current frame width in px. */
  viewportWidth: number
  /**
   * Whether a normal right panel can retain 300px beside a 400px center. The
   * occupant shows only when this holds; the track is our own opening report.
   */
  canShow: boolean
}

/**
 * Three-column application frame: the session sidebar, the conversation
 * column, and the review column, over the vendored dsh ui-layout frame grid.
 *
 * The track widths are solved by the vendored `computeColumns` rather than
 * restated here, so the right column concedes in upstream's order — it
 * shrinks, then loses its track entirely, before the centre drops below its
 * own minimum. That ordering is the whole reason the geometry is vendored
 * instead of a third literal width.
 *
 * Two of upstream's frame behaviours are deliberately not implemented, because
 * this app's shell has no equivalent surface (recorded in `webui/UPSTREAM.md`):
 * the drag handles (this app's sidebar collapses but is not draggable) and the
 * fullscreen presentation (there is no fullscreen route). Their CSS survives in
 * the vendored stylesheet unused, which is upstream's own shape.
 *
 * The layout store supplies the sidebar width as measured at the last
 * breakpoint crossing. The `collapsed` prop still wins for the rail, because
 * the sidebar's collapse is this app's own control and the store's preference
 * is what that control writes.
 *
 * The frame solves twice, as upstream does: `normal` is the eligibility an
 * occupant is shown on (with a right panel that a too-narrow frame could not
 * hold), `cols` is what gets rendered from the store's own opening report.
 *
 * @param props.sidebar - the sidebar column's content.
 * @param props.sidebarWidth - wide-sidebar track width before any collapse.
 * @param props.collapsed - whether the sidebar shows its rail instead.
 * @param props.right - the right column occupant, given what it may show.
 * @param props.children - the centre column's content.
 * @returns the frame.
 */
export function AppFrame({ sidebar, sidebarWidth = SIDEBAR_WIDTH, collapsed = false, right = null, children }: {
  sidebar: ReactNode
  sidebarWidth?: number
  collapsed?: boolean
  right?: ((owner: RightbarOwnerProps) => ReactNode) | null
  children: ReactNode
}) {
  const viewportWidth = useLayout((state) => state.viewportWidth)
  const rightbar = useLayout((state) => state.rightbar)
  const rightbarTrack = useLayout((state) => state.rightbarTrack)

  // The frame solves in px, so the store needs a measurement. Upstream reports
  // it from its own resize observation; this is the same report through the
  // one effect that owns the listener.
  useEffect(() => {
    if (typeof window === 'undefined') return
    const measure = () => { layoutActions.setViewportWidth(window.innerWidth) }
    measure()
    window.addEventListener('resize', measure)
    return () => { window.removeEventListener('resize', measure) }
  }, [])

  // A collapsed sidebar is `0` to the solver, which substitutes the rail track
  // for it — so the two forms are one call, and the rail is never solved twice.
  const narrowExpanded = useLayout((state) => state.narrowExpanded)
  const sidebarPx = collapsed && !narrowExpanded ? 0 : sidebarWidth
  // Upstream's first-open preference is a ratio; the store starts with none.
  const rightbarPreference = rightbar ?? viewportWidth * RIGHTBAR_DEFAULT_RATIO
  // Eligibility is solved as if the panel were open, so an occupant asked to
  // report itself knows whether the frame would keep it.
  const normal = computeColumns(viewportWidth, sidebarPx, rightbarPreference)
  const { sidebar: sidebarTrack, rightbar: rightbarTrackPx } = computeColumns(
    viewportWidth,
    sidebarPx,
    rightbarTrack ? rightbarPreference : 0,
  )
  // The grid resolves the squeeze itself: the centre declares its floor and
  // the right track bids up to the clamped preference. Pricing it in JS would
  // make the centre absorb each resize whole and correct two frames later.
  const rightbarMax = rightbarTrackPx === 0
    ? 0
    : clampWidth(rightbarPreference, RIGHTBAR_MIN, viewportWidth * RIGHTBAR_MAX_RATIO)

  return (
    <div
      className={css.frame}
      style={{
        gridTemplateColumns:
          `${sidebarTrack}px minmax(${rightbarTrackPx === 0 ? 0 : CENTER_MIN}px, 1fr) minmax(0px, ${rightbarMax}px)`,
      }}
      data-rightbar-collapsed={rightbarTrackPx === 0 || undefined}
    >
      <div className={css.sidebarCol}>{sidebar}</div>
      <div className={css.centerCol}>{children}</div>
      {/* The column stays mounted while the panel is trackless, so a panel that
          hangs over the centre keeps its state; the occupant, not the column,
          decides whether it has room to show. */}
      <div className={css.rightbarCol}>
        {right?.({ width: normal.rightbar, viewportWidth, canShow: normal.rightbar > 0 })}
      </div>
    </div>
  )
}
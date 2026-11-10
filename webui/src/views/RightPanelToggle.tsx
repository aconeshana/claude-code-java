import type { ReactNode } from 'react'
import { useLayout } from '@vendor/ui-layout'
import { IconPanelLeftOutline16 } from '@vendor/ui-primitives/icons'
import { useReviewSeat } from '../store/review'
import css from './RightPanelToggle.module.css'

/**
 * The conversation header's right-column control.
 *
 * Upstream's dock is a tab surface with its own close affordance *inside* each
 * pane, so the review carries no close button of its own (`ReviewTab` renders
 * four tools and none of them ends the tab). This app's right column hosts one
 * review and has no dock chrome, which left the review with no way back to the
 * bare conversation. The control that ends a browser dock — a panel toggle in
 * the surface's own header — is the replacement, and it is this app's addition
 * rather than a vendored piece.
 *
 * It reports through the same two store facts the panel itself drives:
 * pressing it closes the review (the seat) and withdraws the track (the
 * layout), and while a review is showing it offers to end it. Because it
 * reads the track rather than the seat, a frame too narrow to keep the panel
 * still shows the control as on, matching the panel's own "opening survives a
 * narrow frame" rule.
 *
 * @returns the toggle, or nothing while no review has ever been opened.
 */
export function RightPanelToggle(): ReactNode {
  const open = useReviewSeat((state) => state.open)
  const closeReview = useReviewSeat((state) => state.closeReview)
  const shown = useLayout((state) => state.rightbarShown)

  if (open === null && !shown) return null
  return (
    <button
      type="button"
      className={css.toggle}
      aria-label="关闭改动面板"
      aria-pressed={shown}
      data-right-panel-toggle
      onClick={() => { closeReview() }}
    >
      <IconPanelLeftOutline16 className={css.icon} />
    </button>
  )
}

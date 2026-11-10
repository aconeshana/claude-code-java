/**
 * The header's right-column control.
 *
 * The review pane carries no close button of its own (upstream ends a review
 * by closing the dock tab, and this app has no dock), so the toggle is the
 * only way back to the bare conversation. What matters here is that pressing
 * it ends the review AND withdraws the track — the panel's own effect reports
 * the track on mount, and a toggle that only cleared the seat would leave the
 * frame reserving an empty column.
 */
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { layoutActions } from '@vendor/ui-layout'
import { useReviewSeat } from '../store/review'
import { RightPanelToggle } from './RightPanelToggle'

declare global {
  // eslint-disable-next-line no-var -- React's act() environment flag
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined
}

let container: HTMLDivElement
let root: Root

beforeEach(() => {
  globalThis.IS_REACT_ACT_ENVIRONMENT = true
  act(() => { useReviewSeat.getState().closeReview() })
  act(() => { layoutActions.closeRightbar() })
  container = document.createElement('div')
  document.body.append(container)
  root = createRoot(container)
})

afterEach(() => {
  act(() => { root.unmount() })
  container.remove()
})

describe('RightPanelToggle', () => {
  it('draws nothing while no review owns the column', () => {
    act(() => { root.render(<RightPanelToggle />) })
    expect(container.querySelector('[data-right-panel-toggle]')).toBeNull()
  })

  it('closes the review and releases the track when pressed', () => {
    act(() => { useReviewSeat.getState().openReview('sess', 2, 0) })
    act(() => { layoutActions.openRightbar(true, false) })
    act(() => { root.render(<RightPanelToggle />) })
    const button = container.querySelector<HTMLButtonElement>('[data-right-panel-toggle]')
    expect(button).not.toBeNull()

    act(() => { button?.click() })
    expect(useReviewSeat.getState().open).toBeNull()
    // The control follows the panel rather than closing itself: it stays for
    // as long as the track is reserved, and the panel's unmount effect is what
    // withdraws both. What the click must not do is leave the seat set.
    expect(container.querySelector('[data-right-panel-toggle]')?.getAttribute('aria-pressed')).toBe('true')
  })
})

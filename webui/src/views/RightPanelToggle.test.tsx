import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { useRightSidebar } from '../store/rightSidebar'
import { useSessions } from '../store/sessions'
import { RightPanelToggle } from './RightPanelToggle'

declare global {
  // eslint-disable-next-line no-var -- React's act() environment flag
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined
}

let container: HTMLDivElement
let root: Root

beforeEach(() => {
  globalThis.IS_REACT_ACT_ENVIRONMENT = true
  window.localStorage.clear()
  act(() => {
    useSessions.setState({ selectedSessionId: null })
    useRightSidebar.setState({ surfaces: {} })
  })
  container = document.createElement('div')
  document.body.append(container)
  root = createRoot(container)
})

afterEach(() => {
  act(() => { root.unmount() })
  container.remove()
})

describe('RightPanelToggle', () => {
  it('draws nothing when no session is selected', () => {
    act(() => { root.render(<RightPanelToggle />) })
    expect(container.querySelector('[data-right-panel-toggle]')).toBeNull()
  })

  it('shows expand button when a session is selected and collapsed, and hides once expanded', () => {
    act(() => { useSessions.setState({ selectedSessionId: 'sess-1' }) })
    act(() => { root.render(<RightPanelToggle />) })
    const button = container.querySelector<HTMLButtonElement>('[data-right-panel-toggle]')
    expect(button).not.toBeNull()

    act(() => { button?.click() })
    expect(useRightSidebar.getState().surfaces['sess-1']?.state.expanded).toBe(true)
    expect(container.querySelector('[data-right-panel-toggle]')).toBeNull()
  })
})

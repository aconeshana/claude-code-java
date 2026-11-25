import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  PermissionModeSelect,
  buildStaticPermissionModeState,
  normalizePermissionMode,
} from './PermissionModeSelect'

declare global {
  // eslint-disable-next-line no-var -- React's act() environment flag
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined
}

let container: HTMLDivElement
let root: Root

beforeEach(() => {
  globalThis.IS_REACT_ACT_ENVIRONMENT = true
  container = document.createElement('div')
  document.body.append(container)
  root = createRoot(container)
})

afterEach(() => {
  act(() => { root.unmount() })
  container.remove()
})

async function settled(): Promise<void> {
  await act(async () => { await Promise.resolve() })
}

describe('buildStaticPermissionModeState', () => {
  it('normalizes empty or unknown values to default', () => {
    expect(normalizePermissionMode('')).toBe('default')
    expect(normalizePermissionMode(null)).toBe('default')
    expect(normalizePermissionMode('auto')).toBe('default')
    expect(normalizePermissionMode('plan')).toBe('plan')
  })

  it('builds the five canonical modes and respects policy disablement', () => {
    const state = buildStaticPermissionModeState('acceptEdits', { bypassDisabledByPolicy: true })
    expect(state.current).toBe('acceptEdits')
    expect(state.modes.map((m) => m.value)).toEqual([
      'default', 'plan', 'acceptEdits', 'bypassPermissions', 'dontAsk',
    ])
    const bypass = state.modes.find((m) => m.value === 'bypassPermissions')
    expect(bypass?.available).toBe(false)
    expect(state.bypass_permissions_disabled_by_policy).toBe(true)
  })
})

describe('PermissionModeSelect', () => {
  it('renders the five modes and supports explicit default selection when isUnset is true', async () => {
    const select = vi.fn(async () => null)
    const state = buildStaticPermissionModeState('')

    act(() => {
      root.render(
        <PermissionModeSelect
          state={state}
          busy={false}
          side="bottom"
          align="end"
          isUnset
          select={select}
        />,
      )
    })

    const trigger = container.querySelector('button[aria-haspopup="menu"]')
    expect(trigger).not.toBeNull()
    act(() => { trigger!.dispatchEvent(new MouseEvent('click', { bubbles: true })) })

    const items = [...document.body.querySelectorAll('[role="menuitemradio"], [role="menuitem"]')]
    expect(items.length).toBe(5)

    // Clicking 'default' when isUnset=true still invokes select('default')
    act(() => { items[0]!.dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    await settled()
    expect(select).toHaveBeenCalledWith('default')
  })

  it('renders and invokes the optional unset action', async () => {
    const select = vi.fn(async () => null)
    const onUnset = vi.fn(async () => null)
    const state = buildStaticPermissionModeState('plan')

    act(() => {
      root.render(
        <PermissionModeSelect
          state={state}
          busy={false}
          side="bottom"
          align="end"
          onUnset={onUnset}
          select={select}
        />,
      )
    })

    const trigger = container.querySelector('button[aria-haspopup="menu"]')
    act(() => { trigger!.dispatchEvent(new MouseEvent('click', { bubbles: true })) })

    const items = [...document.body.querySelectorAll('[role="menuitemradio"], [role="menuitem"]')]
    expect(items.length).toBe(6)

    act(() => { items[5]!.dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    await settled()
    expect(onUnset).toHaveBeenCalledTimes(1)
    expect(select).not.toHaveBeenCalled()
  })

  it('gates risky mode selection behind RiskConfirmation', async () => {
    const select = vi.fn(async () => null)
    const state = buildStaticPermissionModeState('default')

    act(() => {
      root.render(
        <PermissionModeSelect
          state={state}
          busy={false}
          side="bottom"
          align="end"
          select={select}
        />,
      )
    })

    const trigger = container.querySelector('button[aria-haspopup="menu"]')
    act(() => { trigger!.dispatchEvent(new MouseEvent('click', { bubbles: true })) })

    const items = [...document.body.querySelectorAll('[role="menuitemradio"], [role="menuitem"]')]
    // Index 4 is dontAsk
    act(() => { items[4]!.dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    expect(select).not.toHaveBeenCalled()

    const checkbox = document.body.querySelector('input[type="checkbox"]')
    expect(checkbox).not.toBeNull()
    act(() => { checkbox!.dispatchEvent(new MouseEvent('click', { bubbles: true })) })

    const dialog = document.body.querySelector('[role="dialog"]')
    expect(dialog).not.toBeNull()
    const buttons = [...dialog!.querySelectorAll('button')]
    const confirmButton = buttons[buttons.length - 1]
    act(() => { confirmButton!.dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    await settled()
    expect(select).toHaveBeenCalledWith('dontAsk')
  })
})

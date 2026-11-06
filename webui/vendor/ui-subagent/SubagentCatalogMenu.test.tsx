import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AgentRecord } from '@dsh-context/shared/types'
import { useLocale } from '../../src/store/locale'
import { SubagentCatalogMenu, catalogEntriesOf } from './SubagentCatalogMenu'

declare global {
  // eslint-disable-next-line no-var -- React's act() environment flag
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined
}

let container: HTMLDivElement
let root: Root

beforeEach(() => {
  globalThis.IS_REACT_ACT_ENVIRONMENT = true
  // Pin the locale: the assertions below read the Chinese copy.
  useLocale.setState({ locale: 'zh' })
  container = document.createElement('div')
  document.body.append(container)
  root = createRoot(container)
})

afterEach(() => {
  act(() => { root.unmount() })
  container.remove()
  vi.useRealTimers()
})

/** A minimal settled `AgentRecord`; every field beyond id/seq/status is opt-in. */
function agent(overrides: Partial<AgentRecord> = {}): AgentRecord {
  return { id: 'tu-1', seq: 1, status: 'done', ...overrides }
}

async function render(props: Parameters<typeof SubagentCatalogMenu>[0]): Promise<void> {
  await act(async () => { root.render(<SubagentCatalogMenu {...props} />) })
}

function trigger(): HTMLButtonElement | null {
  return container.querySelector<HTMLButtonElement>('[data-subagent-action] button')
}

function panel(): HTMLElement | null {
  return document.querySelector<HTMLElement>('[data-subagent-panel]')
}

function rows(): HTMLElement[] {
  return Array.from(document.querySelectorAll<HTMLElement>('[role="treeitem"]'))
}

function click(element: Element | null): void {
  if (element === null) throw new Error('nothing to click')
  act(() => { (element as HTMLElement).click() })
}

describe('catalogEntriesOf', () => {
  it('orders running agents first, then by recency', () => {
    const entries = catalogEntriesOf([
      agent({ id: 'a', seq: 1, status: 'done', time: 100 }),
      agent({ id: 'b', seq: 2, status: 'running', time: 200 }),
      agent({ id: 'c', seq: 3, status: 'done', time: 300 }),
    ])
    expect(entries.map((entry) => entry.agentId)).toEqual(['b', 'c', 'a'])
  })

  it('prefers the description as the label and demotes the type beneath it', () => {
    const [entry] = catalogEntriesOf([
      agent({ description: 'Review the router', type: 'code-reviewer' }),
    ])
    expect(entry?.primaryLabel).toBe('Review the router')
    expect(entry?.secondaryLabel).toBe('code-reviewer')
  })

  it('falls back to the type, then the id, when there is no description', () => {
    expect(catalogEntriesOf([agent({ type: 'code-reviewer' })])[0]?.primaryLabel).toBe('code-reviewer')
    expect(catalogEntriesOf([agent({})])[0]?.primaryLabel).toBe('tu-1')
  })

  it('addresses a row by its agent id, falling back to the opening call', () => {
    // The row key must be what the transcript store is keyed by, or the
    // catalog would address an agent the gateway has no file for.
    expect(catalogEntriesOf([agent({ agentId: 'agent-7' })])[0]?.agentId).toBe('agent-7')
    expect(catalogEntriesOf([agent({})])[0]?.agentId).toBe('tu-1')
  })

  it('treats a blank description or type as absent', () => {
    const [entry] = catalogEntriesOf([agent({ description: '   ', type: '' })])
    expect(entry?.primaryLabel).toBe('tu-1')
    expect(entry?.secondaryLabel).toBeUndefined()
  })
})

describe('SubagentCatalogMenu', () => {
  it('hides the trigger when the catalog is ready and empty', async () => {
    await render({ agents: [], onOpen: () => {} })
    expect(trigger()).toBeNull()
  })

  it('keeps the trigger while the read is outstanding', async () => {
    await render({ agents: undefined, onOpen: () => {} })
    expect(trigger()).not.toBeNull()
    click(trigger())
    expect(panel()?.textContent).toContain('正在加载子智能体')
  })

  it('keeps the trigger on a failed read so the retry stays reachable', async () => {
    const onRetry = vi.fn()
    await render({ agents: [], failed: true, onRetry, onOpen: () => {} })
    click(trigger())
    expect(panel()?.textContent).toContain('无法加载子智能体')
    const retry = Array.from(panel()?.querySelectorAll('button') ?? [])
      .find((button) => button.textContent?.includes('重试'))
    click(retry ?? null)
    expect(onRetry).toHaveBeenCalledTimes(1)
  })

  it('counts the catalog on the trigger and reports activity in the header', async () => {
    await render({
      agents: [
        agent({ id: 'a', status: 'running' }),
        agent({ id: 'b', status: 'done' }),
        agent({ id: 'c', status: 'failed' }),
      ],
      onOpen: () => {},
    })
    expect(trigger()?.textContent).toContain('3 个子智能体')
    click(trigger())
    // completed / active / inactive, the three disjoint buckets upstream sums.
    expect(panel()?.textContent).toContain('1 已完成')
    expect(panel()?.textContent).toContain('1 进行中')
    expect(panel()?.textContent).toContain('1 未运行')
  })

  it('opts the addressed subagent into the transcript', async () => {
    const onOpen = vi.fn()
    await render({ agents: [agent({ agentId: 'agent-7' })], onOpen })
    click(trigger())
    expect(rows()).toHaveLength(1)
    click(rows()[0] ?? null)
    expect(onOpen).toHaveBeenCalledWith('agent-7')
  })

  it('marks the row whose transcript is on screen', async () => {
    await render({
      agents: [
        agent({ agentId: 'agent-7', description: 'Review the router' }),
        agent({ id: 'tu-2', agentId: 'agent-9', description: 'Audit the cache' }),
      ],
      currentAgentId: 'agent-9',
      onOpen: () => {},
    })
    click(trigger())
    // Only the row the chat is showing carries the check; the other keeps the
    // reserved empty slot that holds the metric column aligned.
    const current = rows().filter((row) => row.hasAttribute('aria-current'))
    expect(current).toHaveLength(1)
    expect(current[0]?.textContent).toContain('Audit the cache')
    expect(document.querySelectorAll('[data-subagent-current]')).toHaveLength(1)
  })

  it('closes the menu once a row is opened', async () => {
    await render({ agents: [agent({ agentId: 'agent-7' })], onOpen: () => {} })
    click(trigger())
    expect(panel()).not.toBeNull()
    click(rows()[0] ?? null)
    expect(panel()).toBeNull()
  })

  it('dismisses with Escape', async () => {
    await render({ agents: [agent()], onOpen: () => {} })
    click(trigger())
    expect(panel()).not.toBeNull()
    act(() => {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    })
    expect(panel()).toBeNull()
  })

  it('pins on click so a pointer leaving the trigger does not close it', async () => {
    vi.useFakeTimers()
    await render({ agents: [agent()], onOpen: () => {} })
    click(trigger())
    const root = container.querySelector<HTMLElement>('[data-subagent-action]')
    act(() => {
      root?.dispatchEvent(new PointerEvent('pointerleave', { bubbles: true }))
    })
    act(() => { vi.advanceTimersByTime(200) })
    expect(panel()).not.toBeNull()
  })

  it('opens on hover after upstream’s 150ms dwell', async () => {
    vi.useFakeTimers()
    await render({ agents: [agent()], onOpen: () => {} })
    expect(panel()).toBeNull()
    // React drives `onPointerEnter` from the bubbling `pointerover` event
    // (pointerenter itself does not bubble), so that is what a hover is here.
    act(() => {
      trigger()?.dispatchEvent(new PointerEvent('pointerover', { bubbles: true }))
    })
    // Not yet: the hover has to dwell before it counts.
    act(() => { vi.advanceTimersByTime(100) })
    expect(panel()).toBeNull()
    act(() => { vi.advanceTimersByTime(100) })
    expect(panel()).not.toBeNull()
  })

  it('labels every row with its metrics for assistive technology', async () => {
    await render({
      agents: [agent({
        agentId: 'agent-7',
        description: 'Review the router',
        type: 'code-reviewer',
        tokens: 1_500,
        durationMs: 65_000,
      })],
      onOpen: () => {},
    })
    click(trigger())
    const label = rows()[0]?.getAttribute('aria-label') ?? ''
    expect(label).toContain('Review the router')
    expect(label).toContain('已完成')
    expect(label).toContain('1.5K tok')
  })

  it('shows the exact duration in the metric cell title', async () => {
    await render({
      agents: [agent({ durationMs: 3_725_000 })],
      onOpen: () => {},
    })
    click(trigger())
    const cell = document.querySelector<HTMLElement>('[title]')
    expect(cell?.getAttribute('title')).toContain('总活跃耗时')
  })

  it('enters the focused row with Enter', async () => {
    const onOpen = vi.fn()
    await render({ agents: [agent({ agentId: 'agent-7' })], onOpen })
    click(trigger())
    act(() => {
      rows()[0]?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))
    })
    expect(onOpen).toHaveBeenCalledWith('agent-7')
  })

  /** Dispatch a key at the trigger, where upstream binds its list handler. */
  function pressOnTrigger(key: string): void {
    act(() => {
      trigger()?.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true }))
    })
  }

  it('opens onto the first row on ArrowDown from the trigger', async () => {
    // The keyboard route into the catalog, and upstream's only reachable list
    // key: the menu is portalled outside the wrapper that owns the handler, so
    // a key pressed *on the trigger* is what can open the menu and land on the
    // first row. From there the rows are ordinary tab stops.
    await render({ agents: [agent({ agentId: 'a1' }), agent({ agentId: 'a2' })], onOpen: () => {} })
    expect(panel()).toBeNull()
    pressOnTrigger('ArrowDown')
    expect(panel()).not.toBeNull()
    await act(async () => { await Promise.resolve() })
    expect(document.activeElement).toBe(rows()[0])
  })

  it('walks the rows from the trigger handler and wraps at both ends', async () => {
    // Upstream binds `navigate` on the wrapper, but portals the menu to the
    // body — so once focus is on a row, a key pressed *there* never reaches the
    // handler. Rather than invent a menu-level handler upstream does not have,
    // this asserts what upstream's code does: the handler walks correctly from
    // whatever focus state it is given, wrapping at both ends.
    await render({ agents: [agent({ agentId: 'a1' }), agent({ agentId: 'a2' })], onOpen: () => {} })
    click(trigger())
    await act(async () => { await Promise.resolve() })
    expect(document.activeElement).toBe(panel())

    // Focus on the surface, not a row: ArrowDown enters the list at the top.
    pressOnTrigger('ArrowDown')
    expect(document.activeElement).toBe(rows()[0])

    // From the last row, ArrowDown wraps to the first.
    rows()[1]?.focus()
    pressOnTrigger('ArrowDown')
    expect(document.activeElement).toBe(rows()[0])
    // From the first row, ArrowUp wraps to the last.
    pressOnTrigger('ArrowUp')
    expect(document.activeElement).toBe(rows()[1])
  })

  it('jumps to the ends with Home and End', async () => {
    await render({
      agents: [agent({ agentId: 'a1' }), agent({ agentId: 'a2' }), agent({ agentId: 'a3' })],
      onOpen: () => {},
    })
    click(trigger())
    await act(async () => { await Promise.resolve() })

    pressOnTrigger('End')
    expect(document.activeElement).toBe(rows()[2])
    pressOnTrigger('Home')
    expect(document.activeElement).toBe(rows()[0])
  })

  it('leaves the list keys alone when the event came from outside it', async () => {
    // The guard from upstream: a keystroke that reaches the wrapper without
    // being in the tree, on the menu, or on the trigger is somebody else's.
    await render({ agents: [agent({ agentId: 'a1' })], onOpen: () => {} })
    click(trigger())
    await act(async () => { await Promise.resolve() })
    const outside = container.querySelector<HTMLElement>('[data-subagent-action] > span')
    act(() => {
      outside?.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }))
    })
    // Focus is untouched: the menu still holds it, and no row stole it.
    expect(document.activeElement).toBe(panel())
  })

  it('gives the menu focus on a pinning click so the arrows work', async () => {
    // A pointer-opened menu is still keyboard-navigable: the click hands the
    // menu focus rather than leaving it on the trigger, or the list keys
    // would have nothing to move from.
    await render({ agents: [agent({ agentId: 'a1' })], onOpen: () => {} })
    click(trigger())
    await act(async () => { await Promise.resolve() })
    expect(document.activeElement).toBe(panel())
  })

  it('returns focus to the trigger on Escape, but only when the menu held it', async () => {
    await render({ agents: [agent({ agentId: 'a1' })], onOpen: () => {} })
    click(trigger())
    await act(async () => { await Promise.resolve() })
    expect(document.activeElement).toBe(panel())

    act(() => {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    })
    await act(async () => { await Promise.resolve() })
    expect(panel()).toBeNull()
    expect(document.activeElement).toBe(trigger())
  })
})
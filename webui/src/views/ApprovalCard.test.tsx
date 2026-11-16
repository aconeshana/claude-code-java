import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApprovalCard } from './ApprovalCard'
import type { PermissionAsk } from '../api/types'

/**
 * The card is the web half of the permission round trip that used to be checked
 * by driving a real browser over CDP: the terminal ask reaches the web client,
 * the client answers, and a failure to deliver must not look like an answer.
 */

declare global {
  // eslint-disable-next-line no-var -- React's act() environment flag
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined
}

function ask(overrides: Partial<PermissionAsk> = {}): PermissionAsk {
  return {
    request_id: 'req-1',
    session_id: 'sess-1',
    tool: 'Bash',
    input: { command: 'touch /tmp/alpha.txt' },
    ...overrides,
  }
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

function mount(props: Parameters<typeof ApprovalCard>[0]): void {
  act(() => { root.render(<ApprovalCard {...props} />) })
}

function button(label: string): HTMLButtonElement {
  const found = [...container.querySelectorAll('button')].find((each) => each.textContent === label)
  if (!(found instanceof HTMLButtonElement)) throw new Error(`no button ${label}`)
  return found
}

async function settled(): Promise<void> {
  await act(async () => { await Promise.resolve() })
}

describe('ApprovalCard', () => {
  it('shows the tool command and the two decisions', () => {
    mount({ ask: ask(), onDecision: vi.fn() })

    expect(container.textContent).toContain('touch /tmp/alpha.txt')
    expect(container.textContent).toContain('等待授权')
    expect(button('允许一次')).toBeTruthy()
    expect(button('拒绝')).toBeTruthy()
  })

  it('answers with the allow decision the user clicked', async () => {
    const onDecision = vi.fn((_ask: PermissionAsk, _allowed: boolean) => Promise.resolve())
    mount({ ask: ask(), onDecision })

    act(() => { button('允许一次').click() })
    await settled()

    expect(onDecision).toHaveBeenCalledTimes(1)
    expect(onDecision.mock.calls[0][1]).toBe(true)
  })

  it('answers with the reject decision the user clicked', async () => {
    const onDecision = vi.fn((_ask: PermissionAsk, _allowed: boolean) => Promise.resolve())
    mount({ ask: ask(), onDecision })

    act(() => { button('拒绝').click() })
    await settled()

    expect(onDecision).toHaveBeenCalledTimes(1)
    expect(onDecision.mock.calls[0][1]).toBe(false)
  })

  it('locks both decisions while the answer is in flight', async () => {
    let release: () => void = () => {}
    const onDecision = vi.fn(() => new Promise<void>((resolve) => { release = resolve }))
    mount({ ask: ask(), onDecision })

    act(() => { button('允许一次').click() })
    await settled()

    expect(button('允许一次').disabled).toBe(true)
    expect(button('拒绝').disabled).toBe(true)
    // A second click while locked must not send a second answer for the same ask.
    act(() => { button('拒绝').click() })
    await settled()
    expect(onDecision).toHaveBeenCalledTimes(1)
    release()
  })

  it('reports a failed delivery and unlocks so the user can try again', async () => {
    const onDecision = vi.fn(async () => { throw new Error('offline') })
    mount({ ask: ask(), onDecision })

    act(() => { button('允许一次').click() })
    await settled()

    expect(container.textContent).toContain('应答未送达，请重试')
    expect(button('允许一次').disabled).toBe(false)
  })
})

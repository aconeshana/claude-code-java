import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SudoCard } from './SudoCard'
import type { SudoAsk } from '../api/types'

/**
 * The password prompt's two promises are the ones a browser run used to check:
 * the typed secret travels only on the answer, and cancelling sends none.
 */

declare global {
  // eslint-disable-next-line no-var -- React's act() environment flag
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined
}

function ask(overrides: Partial<SudoAsk> = {}): SudoAsk {
  return {
    session_id: 'sess-1',
    request_id: 'req-sudo-1',
    executable: '/usr/bin/sudo',
    command: 'sudo whoami',
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

function mount(props: Parameters<typeof SudoCard>[0]): void {
  act(() => { root.render(<SudoCard {...props} />) })
}

function passwordInput(): HTMLInputElement {
  const input = container.querySelector('input[aria-label="sudo 密码"]')
  if (!(input instanceof HTMLInputElement)) throw new Error('password field not rendered')
  return input
}

function button(label: string): HTMLButtonElement {
  const found = [...container.querySelectorAll('button')].find((each) => each.textContent === label)
  if (!(found instanceof HTMLButtonElement)) throw new Error(`no button ${label}`)
  return found
}

function type(value: string): void {
  const input = passwordInput()
  act(() => {
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set
    setter?.call(input, value)
    input.dispatchEvent(new Event('input', { bubbles: true }))
  })
}

function press(key: string): void {
  act(() => { passwordInput().dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true })) })
}

async function settled(): Promise<void> {
  await act(async () => { await Promise.resolve() })
}

describe('SudoCard', () => {
  it('names the sudo that is asking and shows the masked field', () => {
    mount({ ask: ask(), onRespond: vi.fn() })

    expect(container.textContent).toContain('/usr/bin/sudo 正在请求授权')
    expect(container.textContent).toContain('sudo whoami')
    expect(container.textContent).toContain('需要 sudo 密码')
    expect(passwordInput().type).toBe('password')
  })

  it('submits the typed password and nothing else', async () => {
    const onRespond = vi.fn(async () => {})
    mount({ ask: ask(), onRespond })

    type('hunter2')
    act(() => { button('确认').click() })
    await settled()

    expect(onRespond).toHaveBeenCalledTimes(1)
    const [sentAsk, sentPassword] = onRespond.mock.calls[0] as unknown as [SudoAsk, string | null]
    expect(sentPassword).toBe('hunter2')
    expect(sentAsk).toEqual(ask())
  })

  it('cancels with no password at all', async () => {
    const onRespond = vi.fn((_ask: SudoAsk, _password: string | null) => Promise.resolve())
    mount({ ask: ask(), onRespond })

    type('typed-but-abandoned')
    act(() => { button('取消').click() })
    await settled()

    expect(onRespond).toHaveBeenCalledTimes(1)
    expect(onRespond.mock.calls[0][1]).toBeNull()
  })

  it('submits on Enter', async () => {
    const onRespond = vi.fn((_ask: SudoAsk, _password: string | null) => Promise.resolve())
    mount({ ask: ask(), onRespond })

    type('enterpass')
    press('Enter')
    await settled()

    expect(onRespond).toHaveBeenCalledTimes(1)
    expect(onRespond.mock.calls[0][1]).toBe('enterpass')
  })

  it('cancels on Escape even before anything is typed', async () => {
    const onRespond = vi.fn((_ask: SudoAsk, _password: string | null) => Promise.resolve())
    mount({ ask: ask(), onRespond })

    // An empty field cannot be answered, so Enter does nothing; Escape is the
    // only key that gets the user out of a prompt they cannot answer.
    press('Enter')
    await settled()
    expect(onRespond).not.toHaveBeenCalled()

    press('Escape')
    await settled()

    expect(onRespond).toHaveBeenCalledTimes(1)
    expect(onRespond.mock.calls[0][1]).toBeNull()
  })

  it('cannot submit an empty password', () => {
    mount({ ask: ask(), onRespond: vi.fn() })

    expect(button('确认').disabled).toBe(true)
  })

  it('hides the password again as soon as an answer is sent', async () => {
    let release: () => void = () => {}
    const onRespond = vi.fn(() => new Promise<void>((resolve) => { release = resolve }))
    mount({ ask: ask(), onRespond })

    type('secret')
    act(() => { button('显示').click() })
    expect(passwordInput().type).toBe('text')

    act(() => { button('确认').click() })
    await settled()

    expect(passwordInput().value).toBe('')
    expect(passwordInput().type).toBe('password')
    expect(button('确认').disabled).toBe(true)
    release()
  })

  it('spends both decisions on the answer that is in flight', async () => {
    const onRespond = vi.fn(() => new Promise<void>(() => {}))
    mount({ ask: ask(), onRespond })

    type('secret')
    act(() => { button('确认').click() })
    await settled()

    expect(button('取消').disabled).toBe(true)
    act(() => { button('取消').click() })
    await settled()
    expect(onRespond).toHaveBeenCalledTimes(1)
  })
})

import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { RunningStatus } from '@vendor/dsh-running-status/RunningStatus.tsx'
import { formatRunDuration } from '@vendor/dsh-running-status/run-duration.ts'
import { STATS_NS, statsDicts } from '../i18n/dictionaries/stats'
import { BASE_REGISTRY, translate, type NamespaceRegistry } from '../i18n/runtime'

// Ported from upstream ui-chat/tests/running-status.client.spec.tsx onto
// react-dom/client (this app carries no @testing-library), against the app's
// own chat dictionary so the vendored copy and its keys are checked together.

const registry: NamespaceRegistry = new Map([...BASE_REGISTRY, [STATS_NS, statsDicts]])
const t = (key: string, params?: Readonly<Record<string, string | number>>) =>
  translate(registry, STATS_NS, 'zh', key, params)

declare global {
  // eslint-disable-next-line no-var -- React's act() environment flag
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined
}

let container: HTMLDivElement
let root: Root

beforeEach(() => {
  globalThis.IS_REACT_ACT_ENVIRONMENT = true
  vi.useFakeTimers()
  vi.setSystemTime(5_000)
  container = document.createElement('div')
  document.body.append(container)
  root = createRoot(container)
})

afterEach(() => {
  act(() => { root.unmount() })
  container.remove()
  vi.useRealTimers()
})

function render(startTime?: number, paused = false, verb?: string): void {
  act(() => { root.render(<RunningStatus startTime={startTime} paused={paused} verb={verb} t={t} />) })
}

const content = () => container.querySelector('[data-chat-running] > :last-child')

describe('RunningStatus', () => {
  it('waits for an open Turn start before allocating its clock', () => {
    render()
    expect(content()?.textContent).toBe('运行中…')
    expect(vi.getTimerCount()).toBe(0)
    render(1_000)
    expect(content()?.textContent).toBe('运行中… (4秒)')
    expect(content()?.querySelectorAll('[data-shimmer="true"]')).toHaveLength(1)
    expect(vi.getTimerCount()).toBe(1)
    render()
    expect(content()?.textContent).toBe('运行中…')
    expect(vi.getTimerCount()).toBe(0)
  })

  it('ticks once a second and announces only the static label', () => {
    render(1_000)
    const status = container.querySelector('[role="status"]')
    act(() => { vi.advanceTimersByTime(2_000) })
    expect(content()?.textContent).toBe('运行中… (6秒)')
    expect(status?.textContent).toBe('运行中')
    act(() => { root.unmount() })
    root = createRoot(container)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('keeps the duration nonnegative when the start is ahead of the local clock', () => {
    render(6_000)
    expect(content()?.textContent).toBe('运行中… (1秒)')
  })

  it('freezes the duration while paused and resumes from the Turn start', () => {
    render(1_000)
    expect(content()?.textContent).toBe('运行中… (4秒)')
    render(1_000, true)
    expect(vi.getTimerCount()).toBe(0)
    act(() => { vi.advanceTimersByTime(5_000) })
    expect(content()?.textContent).toBe('运行中… (4秒)')
    render(1_000)
    expect(content()?.textContent).toBe('运行中… (9秒)')
    expect(vi.getTimerCount()).toBe(1)
  })
})

describe('RunningStatus verb', () => {
  it('words the row with the spinner verb and keeps the announcement static', () => {
    render(1_000, false, 'Brewing')
    expect(content()?.textContent).toBe('Brewing… (4秒)')
    expect(container.querySelector('[role="status"]')?.textContent).toBe('运行中')
    render(undefined, false, 'Reading')
    expect(content()?.textContent).toBe('Reading…')
  })

  it('carries no icon beside the shimmer text', () => {
    render(1_000, false, 'Brewing')
    expect(content()?.querySelector('img, svg')).toBeNull()
  })
})

describe('formatRunDuration', () => {
  const text = (ms: number) => formatRunDuration(ms, t).map((part) => part.text).join('')

  it('starts minutes at 60 seconds and hours at 60 minutes, without leading zeros', () => {
    expect(text(59_999)).toBe('59秒')
    expect(text(60_000)).toBe('1分0秒')
    expect(text(3_599_000)).toBe('59分59秒')
    expect(text(3_600_000)).toBe('1小时0分0秒')
  })

  it('clamps negatives to zero', () => {
    expect(text(-5_000)).toBe('0秒')
  })
})

import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type { SessionTaskBoardItem, TaskStatus } from '../../api/types'
import { useLocale } from '../../store/locale'
import { TodoPanel } from './TodoPanel'

// Ported from upstream ui-conversation's todo-panel spec onto
// react-dom/client (this app carries no @testing-library). The panel reads
// the app's locale store, whose initial value is browser-detected — pinned to
// zh here so the asserted copy does not depend on the host's language.

declare global {
  // eslint-disable-next-line no-var -- React's act() environment flag
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined
}

let container: HTMLDivElement
let root: Root

beforeEach(() => {
  globalThis.IS_REACT_ACT_ENVIRONMENT = true
  useLocale.setState({ locale: 'zh' })
  container = document.createElement('div')
  document.body.append(container)
  root = createRoot(container)
})

afterEach(() => {
  act(() => { root.unmount() })
  container.remove()
})

function task(id: string, status: TaskStatus, subject = `task ${id}`): SessionTaskBoardItem {
  return { id, subject, description: '', active_form: '', owner: '', status, blocks: [], blocked_by: [] }
}

function render(tasks: readonly SessionTaskBoardItem[]): void {
  act(() => { root.render(<TodoPanel tasks={tasks} />) })
}

const panel = () => container.querySelector('[data-testid="todo-panel"]')
const header = () => container.querySelector('button')
const progress = () => container.querySelector<HTMLElement>('[class*="progress"]')
const items = () => container.querySelectorAll('li')

describe('TodoPanel', () => {
  it('renders nothing for an empty plan', () => {
    render([])
    expect(panel()).toBeNull()
  })

  it('is collapsed by default and expands on click', () => {
    render([task('a', 'pending')])
    expect(header()?.getAttribute('aria-expanded')).toBe('false')
    expect(items()).toHaveLength(0)

    act(() => { header()?.click() })
    expect(header()?.getAttribute('aria-expanded')).toBe('true')
    expect(items()).toHaveLength(1)
  })

  it('lists only the non-zero per-status counts', () => {
    render([task('a', 'completed'), task('b', 'completed'), task('c', 'pending')])
    const text = progress()?.textContent ?? ''
    expect(text).toContain('2 已完成')
    expect(text).toContain('1 待处理')
    // No in-progress task: that segment must not appear at all.
    expect(text).not.toContain('进行中')
  })

  it('joins the non-zero segments with an en-space separator', () => {
    render([task('a', 'completed'), task('b', 'in_progress')])
    expect(progress()?.textContent).toBe('1 已完成\u2002·\u20021 进行中')
  })

  it('produces no separator when only one segment is non-zero', () => {
    render([task('a', 'pending'), task('b', 'pending')])
    expect(progress()?.textContent).toBe('2 待处理')
  })

  it('maps each status onto its status dot state', () => {
    render([task('a', 'completed'), task('b', 'in_progress'), task('c', 'pending')])
    act(() => { header()?.click() })
    const dots = container.querySelectorAll('[role="img"] [data-state]')
    expect([...dots].map(dot => dot.getAttribute('data-state'))).toEqual(['done', 'ongoing', 'idle'])
  })

  it('announces each status for assistive tech', () => {
    render([task('a', 'completed'), task('b', 'in_progress'), task('c', 'pending')])
    act(() => { header()?.click() })
    const labels = [...container.querySelectorAll('[role="img"]')].map(node => node.getAttribute('aria-label'))
    expect(labels).toEqual(['已完成', '进行中', '待处理'])
  })
})
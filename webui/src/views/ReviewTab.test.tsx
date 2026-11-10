/**
 * The right column's review, driven through the real vendored stores.
 *
 * This suite carries the ground `ChangesModal.test.tsx` used to hold — the
 * host's loading / missing / binary / oversized states and the diff drawing —
 * now that the modal is gone and `ReviewTab` is the only surface that mounts a
 * comparison.
 *
 * It stubs `fetch` rather than a client function on purpose. The two vendored
 * stores (and `host-read-store` under them) decide what a response *means*, and
 * their decode guards are exactly what the white-screen regression arrived
 * through: a response the guard accepts but the renderer cannot draw throws
 * inside render and takes the tree with it. Going around the stores would test
 * a path the app never uses.
 *
 * The stores read through `readAuthorized`, so the stub also stands in for the
 * launch-token lookup: without a captured token that helper refuses to issue the
 * request at all, and every case below would read an unauthenticated transport
 * failure instead of the routed answer.
 */
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useLocale } from '../store/locale'
import { changesDiffStore, changesSummaryStore } from '../store/changes'
import { reviewStore, reviewTabId, useReviewSeat } from '../store/review'
import { useFilePreviewSeat } from '../store/filePreview'
import { RightPanel } from './RightPanel'

declare global {
  // eslint-disable-next-line no-var -- React's act() environment flag
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined
}

const SESSION = 'sess-review'
const TURN = 3
const PATH = 'src/App.tsx'

const SUMMARY = {
  turn: TURN,
  total: 1,
  added: 1,
  deleted: 1,
  files: [{ path: PATH, display: PATH, added: 1, deleted: 1 }],
}

const TEXT_DIFF = {
  kind: 'text',
  path: PATH,
  display: PATH,
  before: true,
  after: true,
  coarse: false,
  hunks: [{
    oldStart: 1, oldLines: 3, newStart: 1, newLines: 3,
    lines: [' import x', '-const a = 1', '+const a = 2', ' export y'],
  }],
}

let container: HTMLDivElement
let root: Root

beforeEach(() => {
  globalThis.IS_REACT_ACT_ENVIRONMENT = true
  // The assertions read the copy, so the locale is pinned rather than left to
  // the environment's `navigator.languages`.
  useLocale.setState({ locale: 'zh' })
  // `reset()` is the store's own isolation lever: it abandons in-flight reads
  // and clears every cached state, so no case inherits another's answer.
  changesSummaryStore.reset()
  changesDiffStore.reset()
  reviewStore.actions.forget(reviewTabId(SESSION, TURN))
  // The launch token a real tab captures from its landing URL; the shared
  // `readAuthorized` helper refuses to read without one.
  sessionStorage.setItem('gateway-token', 'test-token')
  container = document.createElement('div')
  document.body.append(container)
  root = createRoot(container)
})

afterEach(() => {
  vi.unstubAllGlobals()
  sessionStorage.removeItem('gateway-token')
  act(() => { root.unmount() })
  container.remove()
})

/**
 * Answer reads from a routing table keyed on the endpoint's extension segment.
 * A route that is absent answers 404, which is what an endpoint that no longer
 * serves the record does.
 */
function stubFetch(routes: Record<string, { status?: number; body?: unknown }>): void {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    const endpoint = url.match(/\.(summary|diff|open)\b/)?.[1] ?? ''
    const route = routes[`.${endpoint}`] ?? { status: 404 }
    const status = route.status ?? 200
    return {
      ok: status >= 200 && status < 300,
      status,
      json: async () => route.body,
    } as Response
  }))
}

/** Mount the panel, then open this turn's review on it and settle both reads. */
async function openReview(index = 0): Promise<void> {
  await act(async () => { root.render(<RightPanel canShow viewportWidth={1440} width={432} />) })
  await act(async () => { useReviewSeat.getState().openReview(SESSION, TURN, index) })
}

/** Every drawn diff line, in document order. */
function diffLines(): HTMLElement[] {
  return [...container.querySelectorAll<HTMLElement>('[data-diff-line]')]
}

describe('RightPanel', () => {
  it('renders no panel while no review is open', async () => {
    await act(async () => { root.render(<RightPanel canShow viewportWidth={1440} width={432} />) })
    expect(container.querySelector('[data-right-panel]')).toBeNull()
  })

  // The frame solves eligibility and the track separately: a frame too narrow
  // to keep a 300px panel reports the opening but not the room, and the panel
  // stays out until the frame widens rather than render at zero width. The
  // opening itself survives, so widening brings the review back on its own.
  it('stays out while the frame has no room, keeping the opening', async () => {
    stubFetch({ '.summary': { body: SUMMARY }, '.diff': { body: TEXT_DIFF } })
    await act(async () => {
      root.render(<RightPanel canShow={false} viewportWidth={700} width={0} />)
    })
    await act(async () => { useReviewSeat.getState().openReview(SESSION, TURN, 0) })
    expect(container.querySelector('[data-right-panel]')).toBeNull()
    expect(useReviewSeat.getState().open?.turn).toBe(TURN)
  })

  it('draws the removed and added lines of each hunk', async () => {
    stubFetch({ '.summary': { body: SUMMARY }, '.diff': { body: TEXT_DIFF } })
    await openReview()
    const drawn = diffLines()
    expect(drawn.length).toBeGreaterThan(0)
    expect(container.textContent).toContain('const a = 1')
    expect(container.textContent).toContain('const a = 2')
  })

  it('heads each hunk with its @@ header', async () => {
    stubFetch({ '.summary': { body: SUMMARY }, '.diff': { body: TEXT_DIFF } })
    await openReview()
    expect(container.querySelector('[data-diff-hunk-header]')?.textContent).toContain('@@')
  })

  it('states that a hunkless text diff has identical sides', async () => {
    stubFetch({
      '.summary': { body: SUMMARY },
      '.diff': { body: { ...TEXT_DIFF, hunks: [] } },
    })
    await openReview()
    expect(container.querySelector('[data-diff-note]')?.textContent).toContain('两侧内容相同')
  })

  it('names a created file from its before flag', async () => {
    stubFetch({ '.summary': { body: SUMMARY }, '.diff': { body: { ...TEXT_DIFF, before: false } } })
    await openReview()
    expect(container.querySelector('[data-diff-note]')?.textContent).toContain('本轮新建的文件')
  })

  it('names a deleted file from its after flag', async () => {
    stubFetch({ '.summary': { body: SUMMARY }, '.diff': { body: { ...TEXT_DIFF, after: false } } })
    await openReview()
    expect(container.querySelector('[data-diff-note]')?.textContent).toContain('本轮删除的文件')
  })

  it('says a coarse comparison is a whole-file replacement', async () => {
    stubFetch({ '.summary': { body: SUMMARY }, '.diff': { body: { ...TEXT_DIFF, coarse: true } } })
    await openReview()
    expect(container.querySelector('[data-diff-coarse]')).not.toBeNull()
  })

  it('reports a binary file as a notice instead of an empty diff', async () => {
    stubFetch({
      '.summary': { body: { ...SUMMARY, files: [{ ...SUMMARY.files[0], binary: true }] } },
      '.diff': { body: { kind: 'binary', path: PATH, display: PATH } },
    })
    await openReview()
    expect(container.textContent).toContain('二进制文件，无法显示改动')
    expect(diffLines().length).toBe(0)
  })

  it('reports an oversized file as a notice instead of an empty diff', async () => {
    stubFetch({
      '.summary': { body: { ...SUMMARY, files: [{ ...SUMMARY.files[0], oversized: true }] } },
      '.diff': { body: { kind: 'oversized', path: PATH, display: PATH } },
    })
    await openReview()
    expect(container.textContent).toContain('文件过大，无法显示改动')
    expect(diffLines().length).toBe(0)
  })

  it('reports a failed comparison as an error it offers to retry', async () => {
    // The comparison endpoint fails while the summary serves. 500 is not
    // "gone": the diff store publishes `error`, the one state a later request
    // replaces, so the viewer keeps the file list and offers the retry.
    stubFetch({ '.summary': { body: SUMMARY }, '.diff': { status: 500 } })
    await openReview()
    expect(container.textContent).toContain('无法读取改动')
    expect(container.querySelector('[data-review-file]')).not.toBeNull()
  })

  it('says the turn is gone when the summary itself is unavailable', async () => {
    // The summary store maps every failure, 404 and 500 alike, onto `missing`:
    // without a file list there is nothing to compare, so no read is attempted
    // and the tab reports the turn as unavailable rather than an empty viewer.
    stubFetch({ '.summary': { status: 404 }, '.diff': { body: TEXT_DIFF } })
    await openReview()
    expect(container.textContent).toContain('这轮改动的内容已不可用')
    expect(container.querySelector('[data-review-file]')).toBeNull()
  })

  it('degrades rather than throwing on a diff the guards reject', async () => {
    // The white-screen shape: a body that parses but is not drawable. The
    // guard must turn it into `error` inside the store, so the renderer never
    // reaches for a `hunks` field that is not there.
    stubFetch({
      '.summary': { body: SUMMARY },
      '.diff': { body: { kind: 'text', path: PATH, display: PATH, hunks: [{ lines: ['+x'] }] } },
    })
    await openReview()
    expect(container.textContent).toContain('无法读取改动')
    expect(diffLines().length).toBe(0)
  })

  it('shows the turn selector even before any file has a comparison', async () => {
    stubFetch({ '.summary': { body: SUMMARY }, '.diff': { status: 404 } })
    await openReview()
    expect(container.querySelector('[data-review-file]')?.getAttribute('title')).toBe(PATH)
  })
})

describe('RightPanel review and preview', () => {
  beforeEach(() => {
    useFilePreviewSeat.setState({ preview: null })
  })

  it('opens a file preview over the review and keeps the review mounted beneath it', async () => {
    stubFetch({ '.summary': { body: SUMMARY }, '.diff': { body: TEXT_DIFF }, '.file': { status: 404 } })
    await openReview()
    expect(diffLines().length).toBeGreaterThan(0)
    await act(async () => { useFilePreviewSeat.getState().openPreview(SESSION, PATH) })
    expect(container.querySelector('[data-file-preview]')).not.toBeNull()
    const reviewWrap = container.querySelector<HTMLElement>('[data-right-panel] > div[hidden]')
    expect(reviewWrap).not.toBeNull()
    expect(useReviewSeat.getState().open?.turn).toBe(TURN)
  })

  it('returns to the same review when the preview closes', async () => {
    stubFetch({ '.summary': { body: SUMMARY }, '.diff': { body: TEXT_DIFF }, '.file': { status: 404 } })
    await openReview()
    await act(async () => { useFilePreviewSeat.getState().openPreview(SESSION, PATH) })
    await act(async () => { useFilePreviewSeat.getState().closePreview() })
    expect(container.querySelector('[data-file-preview]')).toBeNull()
    expect(useReviewSeat.getState().open?.turn).toBe(TURN)
    expect(diffLines().length).toBeGreaterThan(0)
  })
})

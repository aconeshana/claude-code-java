/**
 * The review tab: one turn's changed files behind a file selector, with the
 * selected file drawn unified or side by side, wrapped or scrolling, and a
 * control to open the file itself.
 *
 * Vendored from upstream `packages/client/ui-deliverables/src/client/ReviewTab.tsx`
 * at the pinned commit. The structure is upstream's: the selector button
 * carrying a `PathLabel` and a chevron, the per-row counts, the two pressed
 * toggles whose icons swap on state, the open-file control, and the three
 * summary states (`loading` / `missing` / the diff itself) as the body.
 *
 * The bindings replaced (recorded in `webui/UPSTREAM.md`):
 *
 * - `useTabInfo` and its `tab.{id,contentId,navigation,signal,actions}` face —
 *   the dock's tab record. This app's right column hosts one review at a time
 *   and the review it shows is app state, not a docktab, so the tab's identity
 *   and the file index a navigation names arrive as plain props. The
 *   `navigated` seeding rule survives intact: the effect applies an index once
 *   per `navigationRevision`, exactly as upstream's compares against
 *   `navigation.revision`.
 * - `useStore`/`actions` (the store seat) — the store instance is passed in
 *   whole, and its baked actions are read off it, so no `useStore` binding is
 *   needed for a single-frame app.
 * - `useSessions(sessions => sessions.byId[sessionId]?.cwd)` — read for
 *   `fileAddressFor`. The app has no sessions service seat here, so `cwd`
 *   arrives as a prop.
 * - `usePresentedOpen`/`usePresentedHost`/`reloadPresentedHost`/`openChanged`
 *   and the `renderSlot('deliverables.review.file.actions', …)` seat — these
 *   drive upstream's *presented* file actions (open-on-host, reveal-in-file-
 *   manager, per-application dispatch), whose whole backend surface this app
 *   lacks. The open-file control instead opens a whole-file preview tab in
 *   this app's own sidebar (`/api/workspace-file`), so the slot and its
 *   controller are cut rather than stubbed.
 * - `parseChangesReviewAddress(tab.contentId)` and the `seq` coordinates —
 *   this app addresses a comparison and a summary by `(sessionId, turn)` (see
 *   the header note in `changes.ts`), which the props already carry, so no
 *   address is parsed back out of a tab id.
 * - `WorkspaceChangedFile` — upstream's type from its workspace-changes
 *   package, which this project does not vendor. The card and the store
 *   already agree on `ChangesSummaryFile`, which carries the same fields this
 *   component reads (`path`, `display`, `added`, `deleted`, `binary`,
 *   `oversized`).
 */
import { useEffect, useState, type ReactNode } from 'react'
import {
  IconChevronDownOutlineRegular, IconCompareSplitOutlineRegular, IconInspectOutlineRegular,
  IconNowrapFillRegular, IconWrapFillRegular, Menu, PathLabel, Tooltip,
} from '@primitives'
import { changesDiffUrl, changesSummaryUrl } from './changes.ts'
import type { ChangesDiffState } from './changes-diff.ts'
import type { ChangesSummaryState } from './changes-summary.ts'
import type { ChangesSummaryFile } from './changes.ts'
import type { ReviewTabState, createReviewStore } from './review-store.ts'
import { FileDiff } from './FileDiff.tsx'
import diffCss from './FileDiff.module.css'
import css from './ReviewTab.module.css'

const GROUPED = new Intl.NumberFormat('en-US')

/** The copy the tab consumes; the app passes its own translator. */
export type ReviewTabLabels =
  (key: string, params?: Readonly<Record<string, string | number>>) => string

/**
 * The review tab's store instance, as the app builds it. Derived from the
 * factory's own return type rather than restated, so the instance type cannot
 * drift from `createReviewStore` (the actions table is not exported).
 */
export type ReviewStoreInstance = ReturnType<ReturnType<typeof createReviewStore>['create']>

/** Added and deleted line counts in the card's colors. */
function Counts({ file, t }: { file: ChangesSummaryFile; t: ReviewTabLabels }): ReactNode {
  if (file.binary === true) return <span className={css.label}>{t('changes.binary')}</span>
  if (file.oversized === true) return <span className={css.label}>{t('changes.oversized')}</span>
  return <>
    <span className={css.added}>{t('changes.added', { count: GROUPED.format(file.added) })}</span>
    <span className={css.deleted}>{t('changes.deleted', { count: GROUPED.format(file.deleted) })}</span>
  </>
}

/**
 * The file index a review shows: the tab's own, unless the summary does not
 * list it, in which case the first file. Exported because the caller resolves
 * the comparison for the same index and the two must not disagree.
 * @param state - the tab's choices, or undefined before its first navigation.
 * @param summary - the turn summary, or undefined before its read.
 * @returns the index to show.
 */
export function resolveIndex(state: ReviewTabState | undefined, summary: ChangesSummaryState | undefined): number {
  const files = typeof summary === 'object' ? summary.files : []
  return state !== undefined && files[state.index] !== undefined ? state.index : 0
}

/**
 * Draw one turn's review: the file selector, the counts, the view toggles, and
 * the selected file's comparison.
 *
 * @param props.store - the review store instance whose `byTab[tabId]` holds this tab's choices.
 * @param props.tabId - the review's store key; one per open review.
 * @param props.sessionId - the viewed Session.
 * @param props.turn - the 1-based turn number.
 * @param props.fileIndex - the file to show; a navigation names one, else the tab's own.
 * @param props.navigationRevision - bumped when a navigation wants `fileIndex` applied.
 * @param props.summary - the turn summary, or undefined before its read.
 * @param props.diff - the selected comparison, or undefined before its read.
 * @param props.loadSummary - read the summary unless one stands.
 * @param props.loadDiff - read a comparison unless one stands.
 * @param props.openFile - open the whole file on the host.
 * @param props.onClose - called once when the review ends.
 * @param props.t - the copy.
 * @returns the review pane.
 */
export function ReviewTab({
  store, state, tabId, sessionId, turn, fileIndex, navigationRevision,
  summary, diff, index, loadSummary, loadDiff, openFile, onClose, t,
}: {
  store: ReviewStoreInstance
  /** This tab's choices, re-read by the caller; undefined before its first navigation. */
  state: ReviewTabState | undefined
  tabId: string
  sessionId: string
  turn: number
  fileIndex: number | undefined
  navigationRevision: number
  summary: ChangesSummaryState | undefined
  /**
   * The selected file's comparison, already resolved by the caller for
   * {@link resolveIndex}'s answer. Upstream reads it inline through its store
   * hook; a hook cannot be called per index here, so the caller — which owns
   * the same `state` this component reads — resolves one path and passes the
   * single result.
   */
  diff: ChangesDiffState | undefined
  /**
   * The file index to show. Undefined while no bucket exists yet, in which
   * case nothing is drawn for one render — the seeding effect below creates the
   * bucket and the index arrives on the next. The caller must resolve the
   * shown file from the same answer, so it is a prop rather than derived twice.
   */
  index: number
  loadSummary: (sessionId: string, turn: number) => Promise<void>
  loadDiff: (sessionId: string, path: string) => Promise<void>
  openFile: (sessionId: string, path: string) => void
  onClose: () => void
  t: ReviewTabLabels
}): ReactNode {
  const actions = store.actions
  // Every navigation applies its file index once; the first one seeds the tab's choices.
  useEffect(() => {
    if (state?.navigated === navigationRevision) return
    actions.navigated(tabId, navigationRevision, fileIndex ?? state?.index ?? 0)
  }, [state, navigationRevision, fileIndex, actions, tabId])
  useEffect(() => () => { onClose() }, [onClose])
  useEffect(() => {
    // `error` reads again, like the diff store's failed state: an unauthenticated
    // answer (a tab still holding an earlier launch's token) is not a final
    // absence, so this app must not sit on the loading text forever.
    if (summary == null || summary === 'error') void loadSummary(sessionId, turn)
  }, [summary, sessionId, turn, loadSummary])
  const files = typeof summary === 'object' ? summary.files : []
  const file = files[index]
  useEffect(() => {
    if (file !== undefined && diff === undefined) void loadDiff(sessionId, file.path)
  }, [file, diff, sessionId, loadDiff])
  const [menuOpen, setMenuOpen] = useState(false)
  const split = state?.split === true
  const wrap = state?.wrap === true
  const summaryState = summary == null || summary === 'loading' ? 'loading'
    : summary === 'missing' ? 'missing' : summary === 'error' ? 'error' : 'ready'
  return (
    <div className={`${diffCss.root} ${css.root}`} data-changes-review data-review-state={summaryState}>
      <div className={diffCss.header}>
        {file === undefined
          ? <span className={css.selectorLabel}>{t('review.title', { turn: String(turn) })}</span>
          // `autoFocus` (upstream's prop) is cut: this app's `Menu` has no such
          // option and focuses its list on open by its own rule.
          : <Menu className={css.selector} open={menuOpen} portal align="start" dense onClose={() => { setMenuOpen(false) }}
            anchor={<button type="button" className={css.selectorButton} aria-haspopup="menu" aria-expanded={menuOpen}
              aria-label={t('review.selectFile')} title={file.display} data-review-file={file.path}
              onClick={() => { setMenuOpen(value => !value) }}>
              <PathLabel path={file.display} />
              <IconChevronDownOutlineRegular size={12} />
            </button>}
            items={files.map((entry, at) => ({ id: String(at), label: <span className={css.item}>
              <span className={css.itemPath}>{entry.display}</span>
              <span className={css.itemCounts}><Counts file={entry} t={t} /></span>
            </span> }))}
            selectedId={String(index)}
            onSelect={(id) => { actions.selected(tabId, Number(id)); setMenuOpen(false) }} />}
        {file !== undefined && <span className={css.counts}><Counts file={file} t={t} /></span>}
        <span className={css.tools}>
          <Tooltip label={t(split ? 'review.unified' : 'review.split')} side="bottom" delayMs={500}>
            <button type="button" className={css.tool} aria-pressed={split} aria-label={t('review.splitAria')} data-review-tool="split"
              onClick={() => { actions.toggledSplit(tabId) }}>
              <IconCompareSplitOutlineRegular className={css.compareIcon} />
            </button>
          </Tooltip>
          <Tooltip label={t(wrap ? 'review.nowrap' : 'review.wrap')} side="bottom" delayMs={500}>
            <button type="button" className={css.tool} aria-pressed={wrap} aria-label={t('review.wrapAria')} data-review-tool="wrap"
              onClick={() => { actions.toggledWrap(tabId) }}>
              {wrap ? <IconNowrapFillRegular /> : <IconWrapFillRegular />}
            </button>
          </Tooltip>
          {file !== undefined && <Tooltip label={t('review.openFile')} side="bottom" delayMs={500}>
            <button type="button" className={css.tool} aria-label={t('review.openFileAria', { name: file.display })} data-review-tool="open-file"
              onClick={() => { openFile(sessionId, file.path) }}><IconInspectOutlineRegular /></button>
          </Tooltip>}
        </span>
      </div>
      {summaryState === 'loading' && <p className={diffCss.status} role="status">{t('diff.loading')}</p>}
      {summaryState === 'missing' && <p className={diffCss.status}>{t('diff.missing')}</p>}
      {summaryState === 'error' && <p className={diffCss.status}>{t('diff.error')}</p>}
      {file !== undefined && <FileDiff state={diff} split={split} wrap={wrap} t={t}
        retry={() => { void loadDiff(sessionId, file.path) }} />}
    </div>
  )
}

// Re-exported so a consumer can build the URLs the tab reads without reaching
// into the vendor tree for a second import.
export { changesDiffUrl, changesSummaryUrl }
import { useCallback, useEffect } from 'react'
import type { ReactNode } from 'react'
import { ReviewTab, resolveIndex } from '@vendor/ui-deliverables/ReviewTab'
import { layoutActions } from '@vendor/ui-layout'
import { changesDiffStore, changesSummaryStore, useChangesDiff, useChangesSummary } from '../store/changes'
import { reviewStore, reviewTabId, useOpenReview, useReviewSeat, useReviewTabState } from '../store/review'
import { useFilePreviewSeat } from '../store/filePreview'
import { useTranslate } from '../i18n/useTranslate'
import { DELIVERABLES_NS, deliverablesDicts } from '../i18n/dictionaries/deliverables'
import { FilePreview } from './FilePreview'
import type { RightbarOwnerProps } from './AppFrame'
import css from './RightPanel.module.css'

/**
 * The frame's right column occupant: the changed-files review, or nothing.
 *
 * Upstream's right column is a dock whose occupant is whatever tab the address
 * in the route resolves to, and the review is one registered tab type among
 * many. This app's column hosts exactly one surface, so the seat is a plain
 * `if` over the review store rather than a tab registry — the same reduction
 * `vendor/ui-deliverables` records for `ReviewTab`'s props.
 *
 * The panel reports its own presence to the layout store, which is upstream's
 * arrangement: whether the right surface is expanded is a fact owned by the
 * surface, and the frame only reads it to place the track. The frame's two
 * facts stay separate — the track is this panel's report, `canShow` is whether
 * the frame could keep it at all. A frame too narrow to hold the panel keeps
 * the opening reported, so widening brings the review back without the user
 * having to ask again.
 *
 * @param owner - what the frame solved for this column.
 * @returns the panel, or null while no review is open or none can show.
 */
export function RightPanel({ canShow }: RightbarOwnerProps): ReactNode {
  const review = useOpenReview()
  const closeReview = useReviewSeat((state) => state.closeReview)
  const preview = useFilePreviewSeat((state) => state.preview)
  const openPreview = useFilePreviewSeat((state) => state.openPreview)
  const closePreview = useFilePreviewSeat((state) => state.closePreview)
  const t = useTranslate(DELIVERABLES_NS, deliverablesDicts)
  const occupied = review !== null || preview !== null

  // Report presence while a review or preview shows, and withdraw it on unmount.
  // The track is reserved; this app has no fullscreen presentation to report.
  useEffect(() => {
    if (!occupied) return
    layoutActions.openRightbar(true, false)
    return () => { layoutActions.closeRightbar() }
  }, [occupied])

  const openFile = useCallback((sessionId: string, path: string) => {
    openPreview(sessionId, path)
  }, [openPreview])

  if (!occupied || !canShow) return null
  const tabId = review === null ? null : reviewTabId(review.sessionId, review.turn)
  // The review stays mounted under the preview: its unmount closes the review,
  // so hiding it is what lets the preview's close return to the same review.
  return (
    <div className={css.panel} data-right-panel>
      {review !== null && tabId !== null && (
        <div hidden={preview !== null}>
          <ReviewLoader
            // Keyed on the coordinates so the per-review reads below start and stop
            // with the review rather than persisting across two reviews.
            key={tabId}
            tabId={tabId}
            sessionId={review.sessionId}
            turn={review.turn}
            fileIndex={review.index}
            navigationRevision={review.revision}
            onClose={closeReview}
            openFile={openFile}
            t={t}
          />
        </div>
      )}
      {preview !== null && (
        <FilePreview key={`${preview.sessionId}\u0000${preview.path}`}
          sessionId={preview.sessionId} path={preview.path} onClose={closePreview} />
      )}
    </div>
  )
}

/**
 * The review for one turn, bound to the stores its props read from.
 *
 * The index is resolved here, not inside `ReviewTab`, because the comparison
 * read is a hook and a hook cannot be called per index: this component owns the
 * one `state` both the index and the diff key off, so it can resolve the shown
 * file once and hand down both.
 */
function ReviewLoader({ tabId, sessionId, turn, fileIndex, navigationRevision, onClose, openFile, t }: {
  tabId: string
  sessionId: string
  turn: number
  fileIndex: number | undefined
  navigationRevision: number
  onClose: () => void
  openFile: (sessionId: string, path: string) => void
  t: (key: string, params?: Readonly<Record<string, string | number>>) => string
}) {
  const state = useReviewTabState(tabId)
  const summary = useChangesSummary(sessionId, turn)
  const index = resolveIndex(state, summary)
  // `typeof` rather than a comparison chain: the state union carries three
  // strings (`loading` / `missing` / `error`) and only a summary is an object,
  // so widening the union can never silently read a string as a summary.
  const path = typeof summary === 'object' ? summary.files[index]?.path : undefined
  // An empty path is the no-file case; `useChangesDiff` is a hook and must be
  // called unconditionally, so the read is issued for it and never resolves.
  const diff = useChangesDiff(sessionId, path ?? '')
  const loadSummary = useCallback(
    (id: string, at: number) => changesSummaryStore.load(id, at), [])
  const loadDiff = useCallback(
    (id: string, file: string) => changesDiffStore.load(id, file), [])
  return (
    <ReviewTab
      store={reviewStore}
      state={state}
      tabId={tabId}
      sessionId={sessionId}
      turn={turn}
      fileIndex={fileIndex}
      navigationRevision={navigationRevision}
      summary={summary}
      diff={path === undefined ? undefined : diff}
      index={index}
      loadSummary={loadSummary}
      loadDiff={loadDiff}
      openFile={openFile}
      onClose={onClose}
      t={t}
    />
  )
}
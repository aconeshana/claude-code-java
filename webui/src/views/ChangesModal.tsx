import { useEffect, useMemo, type ReactElement } from 'react'
import { createPortal } from 'react-dom'
import { DiffBlock, type DiffHunk } from '@primitives'
import { useChangesViewer } from '../store/changesViewer'
import { useTranslate } from '../i18n/useTranslate'
import { DELIVERABLES_NS, deliverablesDicts } from '../i18n/dictionaries/deliverables'
import { diffLabels } from './labels'
import css from './ChangesModal.module.css'

/**
 * The changed-files viewer — one file's diff at a time, opened from a
 * produced-files chip.
 *
 * Structural deviation from upstream. Upstream opens a file as a tab in the
 * right Sidebar's resource registry (`dsh-resource://file/...`, routed to the
 * `textpreview` tab type) and shows a paginated text reader, not a diff; its
 * only diff surface is the inline `DiffBlock` a tool row already renders. This
 * app has no sidebar tab registry and no resource system, so the viewer is a
 * modal over the conversation column — the same host pattern `/context`
 * established — and reuses the vendored `DiffBlock` verbatim as its body.
 *
 * The control that hands a file to the desktop host renders but stays
 * disabled: the web client runs in a browser, so `/api/changes.open` answers
 * an explicit unavailable error. Upstream omits the native control when no
 * desktop host is present; rendering it disabled with the reason is this
 * project's deliberate difference (recorded in `webui/UPSTREAM.md`), so a
 * later alignment pass does not "fix" it back.
 */
export function ChangesModal(): ReactElement | null {
  const t = useTranslate(DELIVERABLES_NS, deliverablesDicts)
  const sessionId = useChangesViewer((state) => state.sessionId)
  const path = useChangesViewer((state) => state.path)
  const status = useChangesViewer((state) => state.status)
  const diff = useChangesViewer((state) => state.diff)
  const message = useChangesViewer((state) => state.message)
  const close = useChangesViewer((state) => state.close)
  const reload = useChangesViewer((state) => state.reload)
  const openInHost = useChangesViewer((state) => state.openInHost)
  const hostMessage = useChangesViewer((state) => state.hostMessage)
  const hostPending = useChangesViewer((state) => state.hostPending)

  // Escape closes, matching every other modal in this shell.
  useEffect(() => {
    if (sessionId === null) return
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') close()
    }
    window.addEventListener('keydown', onKey)
    return () => { window.removeEventListener('keydown', onKey) }
  }, [close, sessionId])

  const hunks = useMemo<DiffHunk[]>(() => {
    if (diff === null || diff.kind !== 'text') return []
    // `DiffBlock` takes one entry per applied hunk, in file order; a second
    // hunk in the same file renders as a `⋯` gap rather than repeating the
    // path, which is that component's own convention.
    return diff.hunks.map((hunk) => ({
      path: diff.display,
      // The hunk's lines each carry their own `+`/`-`/space prefix, which
      // DiffBlock would draw a second time. Split the hunk back into its
      // removed and added sides, dropping the prefix it re-adds.
      oldText: joinSide(hunk.lines, '-'),
      // The added side is never null: `DiffBlock` requires a string, and a
      // hunk with no added lines legitimately renders an empty added side.
      newText: joinSide(hunk.lines, '+', ' ') ?? '',
    }))
  }, [diff])

  if (sessionId === null || path === null) return null

  const canOpenInHost = hostMessage === null

  return createPortal(
    <div className="lc-modal-backdrop" onClick={close} data-testid="changes-modal">
      <div
        className="lc-modal-card"
        role="dialog"
        aria-modal="true"
        aria-label={t('diff.title')}
        onClick={(event) => { event.stopPropagation() }}
      >
        <div className="lc-modal-head">
          <span className="lc-modal-title" title={path}>{t('diff.title')}</span>
          <button
            type="button"
            className="lc-modal-close"
            aria-label={t('diff.close')}
            onClick={close}
          >×</button>
        </div>
        <div className={css.toolbar}>
          <button
            type="button"
            className={css.reload}
            onClick={reload}
            disabled={status === 'loading'}
          >{t('diff.loading').replace('…', '')}</button>
          <button
            type="button"
            className={css.host}
            // Disabled while the outcome is unknown and after the host has
            // refused it; the reason stays on screen until the next attempt.
            disabled={hostPending || !canOpenInHost}
            title={hostMessage ?? t('openFile.label')}
            onClick={() => { void openInHost(sessionId, path) }}
          >{t('openFile.label')}</button>
          {hostMessage !== null && (
            <span className={css.hostNote} data-testid="changes-open-unavailable">
              {t('openFile.unavailable')}
            </span>
          )}
        </div>

        {status === 'loading' && <div className="lc-empty">{t('diff.loading')}</div>}
        {status === 'failed' && (
          <div className="lc-empty" data-testid="changes-diff-error">{t('diff.failed')}</div>
        )}
        {status === 'ready' && diff !== null && (
          <DiffBody diff={diff} hunks={hunks} t={t} message={message} />
        )}
      </div>
    </div>,
    document.body,
  )
}

function DiffBody({ diff, hunks, t, message }: {
  diff: NonNullable<ReturnType<typeof useChangesViewer.getState>['diff']>
  hunks: readonly DiffHunk[]
  t: (key: string, params?: Readonly<Record<string, string | number>>) => string
  message: string | null
}): ReactElement {
  if (diff.kind === 'binary') {
    return <div className="lc-empty" data-testid="changes-diff-binary">{t('diff.binary')}</div>
  }
  if (diff.kind === 'oversized') {
    return <div className="lc-empty" data-testid="changes-diff-oversized">{t('diff.oversized')}</div>
  }
  if (hunks.length === 0) {
    return <div className="lc-empty">{message ?? t('diff.empty')}</div>
  }
  return (
    <div className={css.body}>
      {diff.truncated && (
        <div className={css.truncated}>{t('diff.truncated', { lines: String(hunks.length) })}</div>
      )}
      <DiffBlock diffs={hunks as DiffHunk[]} labels={diffLabels} maxLines={80} />
    </div>
  )
}

/**
 * One side of a hunk, as `DiffBlock` expects it: the removed lines for `-`,
 * the added and context lines for the added side. A hunk line's first column
 * is its marker, so it is dropped here — the component draws its own.
 */
function joinSide(lines: readonly string[], ...markers: readonly string[]): string | null {
  const kept = lines
    .filter((line) => markers.some((marker) => line.startsWith(marker)))
    .map((line) => line.slice(1))
  return kept.length === 0 ? null : `${kept.join('\n')}\n`
}
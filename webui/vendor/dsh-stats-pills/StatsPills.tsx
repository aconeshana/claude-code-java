// Session stats under the composer, split into two icon pills: a gauge pill
// (turn/step counts + output speed) opening the time-and-speed dialog, and a
// database pill (total tokens + cache hit) opening the token-usage dialog.
// Settled metrics identity prevents stream-delta updates from rerendering the
// row. Adapted from ui-chat's StatsPills.tsx: the cordis projection seats
// (useChat / useProjection over the sessionStats and tokenUsage projections)
// collapse into one plain metrics prop fed by the gateway's durable fold
// (/api/session/context), and the client window fold (deriveStats) is not
// ported — this app always serves the durable projection.

import { memo, useState } from 'react'
import { createPortal } from 'react-dom'
import { IconDatabaseOutline16, IconGaugeOutline16 } from '@primitives'
import { MEASURE_STYLE, useStatDialog } from './stat-dialog.ts'
import {
  statsPillsViewModel,
  type SessionStatsMetrics,
  type StatsTranslate,
} from './statsPillsModel.ts'
import css from '@chat-styles/StatsPills.module.css'
import dialogCss from '@chat-styles/stat-dialog.module.css'

/** Props: the durable metrics fold plus the locale seat. */
export interface StatsPillsProps {
  /** The durable whole-session fold; null while unavailable or unmeasured. */
  readonly metrics: SessionStatsMetrics | null
  /** The locale seat (token-format + stats + message keys). */
  readonly t: StatsTranslate
}

/** External open state one pill's dialog reads and writes (the row's exclusive slot). */
type PillDialog = Pick<ReturnType<typeof useStatDialog>, 'open' | 'setOpen'>

function TimePill({ metrics, t, dialog }: {
  metrics: SessionStatsMetrics
  t: StatsTranslate
  dialog: PillDialog
}) {
  const { open, setOpen, rootRef, panelRef, pos } = useStatDialog(dialog)
  const view = statsPillsViewModel(metrics, t)
  const counts = view.countsText
  const tps = view.tpsText
  const label = (
    <span className={css.label}>
      {counts}
      {tps !== null && (
        <>
          <span className={css.sep} aria-hidden>·</span>
          {tps}
        </>
      )}
    </span>
  )
  // A session without one timed figure has no dialog rows to show, so the
  // pill stays a plain reading instead of a button opening an empty dialog.
  if (!view.timeInteractive) {
    return (
      <span className={css.anchor}>
        <span className={css.pill}>
          <IconGaugeOutline16 />
          {label}
        </span>
      </span>
    )
  }
  return (
    <span ref={rootRef} className={css.anchor}>
      <button
        type="button"
        className={css.pill}
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-label={tps === null ? counts : `${counts} · ${tps}`}
        onClick={() => { setOpen(!open) }}
      >
        <IconGaugeOutline16 />
        {label}
      </button>
      {open && createPortal(
        <div
          ref={panelRef}
          className={dialogCss.panel}
          role="dialog"
          aria-label={t('stats.dialog.title')}
          style={pos ?? MEASURE_STYLE}
        >
          <div className={dialogCss.title}>
            <span className={dialogCss.titleLabel}>
              <IconGaugeOutline16 />
              {t('stats.dialog.title')}
            </span>
          </div>
          <div className={dialogCss.titleRule} aria-hidden />
          <dl className={dialogCss.details} data-session-stats-details>
            {view.llmText !== null && (
              <>
                <dt>{t('stats.dialog.llmTime')}</dt>
                <dd>{view.llmText}</dd>
              </>
            )}
            {view.toolText !== null && (
              <>
                <dt>{t('stats.dialog.toolTime')}</dt>
                <dd>{view.toolText}</dd>
              </>
            )}
            {view.ttftText !== null && (
              <>
                <dt>{t('stats.dialog.ttft')}</dt>
                <dd>{view.ttftText}</dd>
              </>
            )}
            {view.dialogSpeedText !== null && (
              <>
                <dt>{t('stats.dialog.speed')}</dt>
                <dd>{view.dialogSpeedText}</dd>
              </>
            )}
          </dl>
        </div>,
        document.body,
      )}
    </span>
  )
}

function UsagePill({ metrics, t, dialog }: {
  metrics: SessionStatsMetrics
  t: StatsTranslate
  dialog: PillDialog
}) {
  const { open, setOpen, rootRef, panelRef, pos } = useStatDialog(dialog)
  const view = statsPillsViewModel(metrics, t)
  return (
    <span ref={rootRef} className={css.anchor}>
      <button
        type="button"
        className={css.pill}
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-label={view.cacheHitText === null
          ? view.totalText
          : `${view.totalText} · ${view.cacheHitText}`}
        onClick={() => { setOpen(!open) }}
      >
        <IconDatabaseOutline16 />
        <span className={css.label}>
          {view.totalText}
          {view.cacheHitText !== null && (
            <>
              <span className={css.sep} aria-hidden>·</span>
              {view.cacheHitText}
            </>
          )}
        </span>
      </button>
      {open && createPortal(
        <div
          ref={panelRef}
          className={dialogCss.panel}
          role="dialog"
          aria-label={t('stats.dialog.usageTitle')}
          style={pos ?? MEASURE_STYLE}
        >
          <div className={dialogCss.title}>
            <span className={dialogCss.titleLabel}>
              <IconDatabaseOutline16 />
              {t('stats.dialog.usageTitle')}
            </span>
            <span className={dialogCss.titleValue}>{view.exactTotal}</span>
          </div>
          <div className={dialogCss.titleRule} aria-hidden />
          <dl className={dialogCss.details} data-session-stats-usage>
            {view.usageRows.map((row) => (
              <>
                <dt>{row.label}</dt>
                <dd>{row.value}</dd>
              </>
            ))}
          </dl>
        </div>,
        document.body,
      )}
    </span>
  )
}

export const StatsPills = memo(function StatsPills({ metrics, t }: StatsPillsProps) {
  // One exclusive slot for both dialogs: opening either pill closes the other.
  const [openPill, setOpenPill] = useState<'time' | 'usage' | null>(null)
  // Gated on actual activity: a session whose steps all settled without
  // billing shows its counts without a usage pill; an unmeasured session
  // (null metrics) renders no row at all.
  if (metrics === null) return null
  const view = statsPillsViewModel(metrics, t)
  if (!view.render) return null
  // data-composer-stats: InputBar's `.root:has([data-composer-stats])` rule
  // tightens the composer's bottom clearance only while this row renders.
  return (
    <div className={css.root} data-composer-stats>
      {view.showTime && (
        <TimePill
          metrics={metrics}
          t={t}
          dialog={{
            open: openPill === 'time',
            setOpen: (open) => { setOpenPill(open ? 'time' : null) },
          }}
        />
      )}
      {view.showUsage && (
        <UsagePill
          metrics={metrics}
          t={t}
          dialog={{
            open: openPill === 'usage',
            setOpen: (open) => { setOpenPill(open ? 'usage' : null) },
          }}
        />
      )}
    </div>
  )
})

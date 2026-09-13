// Icon-row Turn-stat actions: a database pill labelled with the turn total
// click-opens the per-Turn usage dialog, and a clock pill labelled with the
// turn wall time click-opens the Turn-time dialog. Both sit right of the
// branch action in the tail's IconActions row, ahead of the plain clock text.
// Adapted from ui-chat's TurnUsagePanel.tsx: the usage shape is the gateway's
// TurnUsage wire type (snake_case buckets, always-present fields) instead of
// upstream's TurnTokenUsage (optional buckets present only when every attempt
// reported them), and the routes row (provider/model attribution) and the
// reasoning subset row are cut — this gateway has no per-attempt attribution
// or reasoning-token source. The TPS/TTFT dialog rows stay prop-driven: they
// render only when the caller has the facts, matching upstream's
// facts-absent-rows-omitted semantics.

import { createPortal } from 'react-dom'
import { IconClockOutline16, IconDatabaseOutline16 } from '@primitives'
import type { TurnUsage } from '../../src/api/types'
import type { StatsTranslate } from './statsPillsModel.ts'
import { formatRunDuration } from './message-chrome-runtime.ts'
import { formatTokensPerSecond } from './message-chrome.ts'
import { formatCacheHitPercent, formatExactTokens, formatTokens } from './token-format.ts'
import { MEASURE_STYLE, useStatDialog } from './stat-dialog.ts'
import css from '@chat-styles/TurnUsagePanel.module.css'
import dialogCss from '@chat-styles/stat-dialog.module.css'

export interface TurnUsagePanelProps {
  usage: TurnUsage
  /** The owning view's locale seat, passed down as a plain prop. */
  t: StatsTranslate
}

export interface TurnTimePanelProps {
  /** Turn wall time in ms, the pill's label. */
  runMs: number
  /** Turn decode throughput, a dialog row when known. */
  tokensPerSecond?: number | undefined
  /** Turn first-step TTFT in ms, a dialog row when known. */
  ttftMs?: number | undefined
  /** The owning view's locale seat, passed down as a plain prop. */
  t: StatsTranslate
}

function formatCompactCount(value: number, t: StatsTranslate): string {
  return t('message.turnUsage.count', { count: formatTokens(value, t) })
}

function formatExactCount(value: number, t: StatsTranslate): string {
  return t('message.turnUsage.count', { count: formatExactTokens(value, t) })
}

/** The turn's prompt-side billed buckets (uncached + cache write + cache read). */
function billedPromptTokens(usage: TurnUsage): number {
  return usage.uncached_input_tokens + usage.cache_write_tokens + usage.cache_read_tokens
}

/**
 * Turn-usage IconActions pill with a click-open Turn-usage details dialog.
 * @param props - Turn usage buckets and locale seat.
 * @returns The trigger and, while open, its portaled dialog anchored above the trigger.
 */
export function TurnUsagePanel({ usage, t }: TurnUsagePanelProps) {
  const { open, setOpen, rootRef, panelRef, pos } = useStatDialog()

  const promptTokens = billedPromptTokens(usage)
  const cacheHit = promptTokens === 0
    ? null
    : formatCacheHitPercent(usage.cache_read_tokens, promptTokens, 1)
  const total = formatCompactCount(usage.total_tokens, t)

  return (
    <span ref={rootRef} className={css.root}>
      <button
        type="button"
        className={css.trigger}
        aria-haspopup="dialog"
        aria-expanded={open}
        onClick={() => { setOpen(!open) }}
      >
        <IconDatabaseOutline16 />
        <span className={css.label}>{t('message.turnUsage.consumed', { total })}</span>
      </button>
      {open && createPortal(
        <div
          ref={panelRef}
          className={dialogCss.panel}
          role="dialog"
          aria-label={t('message.turnUsage.title')}
          style={pos ?? MEASURE_STYLE}
        >
          <div className={dialogCss.title}>
            <span className={dialogCss.titleLabel}>
              <IconDatabaseOutline16 />
              {t('message.turnUsage.title')}
            </span>
            <span className={dialogCss.titleValue}>{formatExactCount(usage.total_tokens, t)}</span>
          </div>
          <div className={dialogCss.titleRule} aria-hidden />
          <dl className={dialogCss.details} data-turn-usage-details>
            {cacheHit !== null && (
              <>
                <dt>{t('message.turnUsage.cacheHit')}</dt>
                <dd>{`${cacheHit}%`}</dd>
              </>
            )}
            <dt>{t('message.turnUsage.input')}</dt>
            <dd>{formatExactCount(usage.uncached_input_tokens, t)}</dd>
            <dt>{t('message.turnUsage.cacheRead')}</dt>
            <dd>{formatExactCount(usage.cache_read_tokens, t)}</dd>
            {usage.cache_write_tokens !== 0 && (
              <>
                <dt>{t('message.turnUsage.cacheWrite')}</dt>
                <dd>{formatExactCount(usage.cache_write_tokens, t)}</dd>
              </>
            )}
            <dt>{t('message.turnUsage.output')}</dt>
            <dd>{formatExactCount(usage.output_tokens, t)}</dd>
          </dl>
        </div>,
        document.body,
      )}
    </span>
  )
}

/**
 * Turn-time IconActions pill with a click-open Turn-time details dialog.
 * @param props - Turn timing facts and locale seat.
 * @returns The clock-and-duration trigger and, while open, its portaled dialog anchored above the trigger.
 */
export function TurnTimePanel({ runMs, tokensPerSecond, ttftMs, t }: TurnTimePanelProps) {
  const { open, setOpen, rootRef, panelRef, pos } = useStatDialog()
  return (
    <span ref={rootRef} className={css.root}>
      <button
        type="button"
        className={css.trigger}
        aria-haspopup="dialog"
        aria-expanded={open}
        onClick={() => { setOpen(!open) }}
      >
        <IconClockOutline16 />
        <span className={css.label}>{t('message.ranFor', { duration: formatRunDuration(runMs, t) })}</span>
      </button>
      {open && createPortal(
        <div
          ref={panelRef}
          className={dialogCss.panel}
          role="dialog"
          aria-label={t('message.turnTime.title')}
          style={pos ?? MEASURE_STYLE}
        >
          <div className={dialogCss.title}>
            <span className={dialogCss.titleLabel}>
              <IconClockOutline16 />
              {t('message.turnTime.title')}
            </span>
          </div>
          <div className={dialogCss.titleRule} aria-hidden />
          <dl className={dialogCss.details} data-turn-time-details>
            <dt>{t('message.turnTime.duration')}</dt>
            <dd>{formatRunDuration(runMs, t)}</dd>
            {tokensPerSecond !== undefined && (
              <>
                <dt>{t('message.turnTime.speed')}</dt>
                <dd>{t('message.tokensPerSecond', { tps: formatTokensPerSecond(tokensPerSecond) })}</dd>
              </>
            )}
            {ttftMs !== undefined && (
              <>
                <dt>{t('message.turnTime.ttft')}</dt>
                <dd>{t('duration.seconds', { seconds: formatLatencySeconds(ttftMs) })}</dd>
              </>
            )}
          </dl>
        </div>,
        document.body,
      )}
    </span>
  )
}

/** Sub-turn latency figure: one decimal under ten seconds, whole beyond. */
function formatLatencySeconds(ms: number): string {
  const s = Math.max(0, ms) / 1000
  return s < 10 ? String(Math.round(s * 10) / 10) : String(Math.round(s))
}

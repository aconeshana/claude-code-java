/** Composer context-occupancy meter: a ring beside the send button fed by
 * the gateway's /api/session/context sample, with a click-open panel of the
 * usage reading and the composition breakdown (system / tools / messages).
 * Renders nothing until the endpoint reports both a used token count and a
 * context window.
 *
 * Upstream: dsh packages/client/ui-conversation/src/client/skeleton/
 * ContextMeter.tsx. The projection plumbing (`useProjection`,
 * `contextPressure`) is cut: this app feeds the provider-anchored sample as
 * a plain prop from the gateway session-context endpoint. The heuristic
 * breakdown stays in shape (system/tools/messages segments and the legend
 * rows) but rides the same plain prop — its token counts come from the
 * TUI /context analyzer's category accounting, not a client-side heuristic. */

import { useEffect, useRef, useState } from 'react'
import { Tooltip } from '../../vendor/ui-primitives/Tooltip'
import { contextOccupancy, type ContextPressureSample } from './contextOccupancy.ts'
import css from './ContextMeter.module.css'

/** Ring geometry: 14px viewBox, 2px stroke. */
const RADIUS = 5.5
const CIRCUMFERENCE = 2 * Math.PI * RADIUS

/** The composition split, in bar-segment order; each color class carries the shared swatch/segment tint. */
export interface ContextBreakdownSample {
  readonly systemTokens: number
  readonly toolsTokens: number
  readonly messageTokens: number
}

/** Format a token count with a compact K/M suffix. */
function formatTokens(value: number): string {
  const scaled = (candidate: number): string => candidate >= 100
    ? String(Math.round(candidate))
    : String(Math.round(candidate * 10) / 10)
  if (value < 1_000) return String(value)
  if (value < 1_000_000) return `${scaled(value / 1_000)}K`
  return `${scaled(value / 1_000_000)}M`
}

export interface ContextMeterProps {
  /** The provider-anchored usage sample; null while the session is unknown. */
  pressure: ContextPressureSample | null
  /** The heuristic composition split; undefined renders the single-reading form. */
  breakdown?: ContextBreakdownSample | undefined
  /** The aria label template around the percent reading, e.g. `已用上下文 {percent}%`. */
  ariaLabel: (percent: number) => string
  /** The panel headline around the reading, split around the percent slot. */
  headline: readonly [string, string]
  /** The legend row labels, in bar-segment order. */
  segmentLabels: readonly [string, string, string]
}

export function ContextMeter({ pressure, breakdown, ariaLabel, headline, segmentLabels }: ContextMeterProps) {
  const [open, setOpen] = useState(false)
  const rootRef = useRef<HTMLSpanElement | null>(null)
  const context = contextOccupancy(pressure ?? undefined)
  const available = context !== null

  // A model switch can temporarily remove capacity while this component stays
  // mounted. Close the now-unavailable panel instead of preserving stale UI.
  useEffect(() => {
    if (!available && open) setOpen(false)
  }, [available, open])

  // Outside click / Escape close, one document listener while open (Menu's pattern).
  useEffect(() => {
    if (!open || !available) return
    const onPointerDown = (e: PointerEvent): void => {
      if (e.target instanceof Node && rootRef.current?.contains(e.target) === true) return
      setOpen(false)
    }
    const onKeyDown = (e: KeyboardEvent): void => {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('pointerdown', onPointerDown)
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('pointerdown', onPointerDown)
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [available, open])

  if (context === null) return null
  const percent = context.percent
  const reading = `${percent}%`

  // The bar's overall length stays the provider-exact percent; the breakdown
  // only proportions its colored parts. A zero-width part is dropped instead
  // of rendered: `.segment`'s min-width keeps a hairline part visible, which
  // at 0% occupancy would draw a filled bar over an empty context.
  const breakdownRows = breakdown === undefined
    ? undefined
    : [
        { key: 'systemTokens', label: segmentLabels[0], value: breakdown.systemTokens, color: css.colorSystem },
        { key: 'toolsTokens', label: segmentLabels[1], value: breakdown.toolsTokens, color: css.colorTools },
        { key: 'messageTokens', label: segmentLabels[2], value: breakdown.messageTokens, color: css.colorMessages },
      ] as const
  const breakdownTotal = breakdownRows === undefined
    ? 0
    : breakdownRows.reduce((sum, row) => sum + row.value, 0)
  const parts = breakdownRows === undefined || breakdownTotal === 0
    ? [{ key: 'total', color: undefined, width: percent }]
    : breakdownRows.map(row => ({ key: row.key, color: row.color, width: percent * row.value / breakdownTotal }))
  const segments = parts.filter(part => part.width > 0)

  return (
    <span ref={rootRef} className={css.root}>
      <Tooltip label={ariaLabel(percent)} side="top" delayMs={200} disabled={open}>
        <button
          type="button"
          className={css.trigger}
          aria-label={ariaLabel(percent)}
          aria-haspopup="dialog"
          aria-expanded={open}
          onClick={() => { setOpen(!open) }}
        >
          <svg viewBox="0 0 14 14" width="14" height="14" aria-hidden>
            <circle className={css.track} cx="7" cy="7" r={RADIUS} />
            <circle
              className={css.fill}
              cx="7"
              cy="7"
              r={RADIUS}
              strokeDasharray={`${CIRCUMFERENCE * percent / 100} ${CIRCUMFERENCE}`}
              transform="rotate(-90 7 7)"
            />
          </svg>
        </button>
      </Tooltip>
      {open && (
        <div className={css.panel} role="dialog" aria-label={headline.join(' ')}>
          <div className={css.header}>
            {/* Empty sides collapse through `.headline:empty` so a locale that
                needs no leading (or trailing) text spends no header gap. */}
            <span className={css.headline}>{headline[0]}</span>
            <span className={css.percent}>{reading}</span>
            <span className={css.headline}>{headline[1]}</span>
            {/* `~`: usedTokens is the analyzer-sourced estimate, same as the
                status line's provider-anchored sample. */}
            <span className={css.figures}>
              {`~${formatTokens(context.usedTokens)} / ${formatTokens(context.contextWindow)}`}
            </span>
          </div>
          <div className={css.bar}>
            {segments.map(segment => (
              <div
                key={segment.key}
                className={segment.color === undefined ? css.segment : `${css.segment} ${segment.color}`}
                style={{ width: `${segment.width}%` }}
              />
            ))}
          </div>
          {breakdownRows !== undefined && (
            <dl className={css.rows}>
              {breakdownRows.map(row => (
                <div key={row.key} className={css.row}>
                  <dt>
                    <span className={`${css.swatch} ${row.color}`} aria-hidden />
                    {row.label}
                  </dt>
                  <dd>{`~${formatTokens(row.value)}`}</dd>
                </div>
              ))}
            </dl>
          )}
        </div>
      )}
    </span>
  )
}

/**
 * The Context Dashboard's activity heatmap: a GitHub-style contribution grid
 * (weeks as columns, Monday-first weekdays as rows) over the merged daily
 * ledger (overview.ts). Cell depth is the day's billed-token share of the
 * window's maximum, in four steps; a day with data is a button whose click
 * pins the session list to that day (click again to release). Cells tip
 * through the harness's own Tooltip primitive (instant on hover; native
 * `title` lags a second behind), the bubble carrying the date and the day's
 * active sessions. The grid is computed from the injected `today` key, so
 * the layout is deterministic in tests and follows the browser's local
 * calendar at runtime.
 */

import { Tooltip } from '@primitives'
import type { ReactElement } from 'react'
import { dayKeyOf, mondayOfWeek, shiftDayKey } from '../../shared/days'
import { type DayTotals } from '../overview'
import type { ViewKit } from '../viewkit'

export interface HeatmapProps {
  /** Merged ledger (day key → that day's figures). */
  days: Record<string, DayTotals>
  /** Columns to draw (default 8 — two months). */
  weeks?: number | undefined
  /** The pinned day key, when the list is filtered to a day. */
  selected?: string | null | undefined
  /** Day-pin relay; a data-less day is inert. */
  onSelect?: ((day: string | null) => void) | undefined
  /** The local today key (injected so specs pin the calendar). */
  today: string
}

/** One grid cell: its key, its ledger entry (undefined = no activity), and whether it is in the future. */
interface HeatCell {
  key: string
  entry?: DayTotals | undefined
  future: boolean
}

/**
 * Lay out the grid: `weeks` columns ending at today's week, each seven
 * Monday-first cells. Returns null when `today` (or its week math) fails —
 * a corrupt injected key degrades the card to its empty note.
 */
export function gridOf(today: string, weeks: number): HeatCell[][] | null {
  const lastMonday = mondayOfWeek(today)
  if (lastMonday === null || weeks < 1) return null
  const firstMonday = shiftDayKey(lastMonday, -7 * (weeks - 1))
  if (firstMonday === null) return null
  const columns: HeatCell[][] = []
  for (let w = 0; w < weeks; w++) {
    const monday = shiftDayKey(firstMonday, 7 * w)
    /* v8 ignore next -- monday is bounded by firstMonday and lastMonday (both
     * proven valid), so it can never leave the representable range; kept as a
     * guard so a future shiftDayKey change cannot leak a null into the grid. */
    if (monday === null) return null
    const column: HeatCell[] = []
    for (let d = 0; d < 7; d++) {
      const key = shiftDayKey(monday, d)
      if (key === null) return null
      column.push({ key, future: key > today, entry: undefined })
    }
    columns.push(column)
  }
  return columns
}

/** The cell's depth class: empty, then four steps up to the window's maximum. */
function levelOf(tokens: number, max: number): number {
  if (tokens <= 0 || max <= 0) return 0
  const ratio = tokens / max
  if (ratio <= 0.25) return 1
  if (ratio <= 0.5) return 2
  if (ratio <= 0.75) return 3
  return 4
}

export function makeHeatmap(kit: ViewKit): (props: HeatmapProps) => ReactElement {
  const { t } = kit
  return function Heatmap(props: HeatmapProps): ReactElement {
    const weeks = props.weeks ?? 8
    const columns = gridOf(props.today, weeks)
    if (columns === null) return <div className="lc-empty">{t('ov.heat.empty')}</div>
    // Join the ledger onto the grid and price the depth scale. The record is
    // widened honestly: a day-key read can miss at runtime.
    const byKey: Record<string, DayTotals | undefined> = props.days
    let max = 0
    let any = false
    for (const column of columns) {
      for (const cell of column) {
        const entry = byKey[cell.key]
        if (entry === undefined || (entry.tokens <= 0 && entry.requests <= 0)) continue
        cell.entry = entry
        any = true
        if (entry.tokens > max) max = entry.tokens
      }
    }
    if (!any) return <div className="lc-empty">{t('ov.heat.empty')}</div>
    const weekdayLabels = [t('ov.heat.wd.1'), t('ov.heat.wd.3'), t('ov.heat.wd.5')]
    return (
      <div className="lc-heat" role="group" aria-label={t('ov.heat.title')}>
        <div className="lc-heat-wds" aria-hidden="true">
          {[0, 1, 2, 3, 4, 5, 6].map(row => (
            <span key={row} className="lc-heat-wd">
              {row === 1 ? weekdayLabels[0] : row === 3 ? weekdayLabels[1] : row === 5 ? weekdayLabels[2] : ''}
            </span>
          ))}
        </div>
        <div className="lc-heat-cols">
          {columns.map((column, wi) => (
            <div key={wi} className="lc-heat-col">
              {column.map((cell) => {
                if (cell.future) return <span key={cell.key} className="lc-heat-cell lc-heat-future" aria-hidden="true" />
                const level = cell.entry === undefined ? 0 : levelOf(cell.entry.tokens, max)
                if (cell.entry === undefined) {
                  return (
                    <Tooltip key={cell.key} label={cell.key} side="top">
                      <span className="lc-heat-cell lc-heat-0" />
                    </Tooltip>
                  )
                }
                const picked = props.selected === cell.key
                const label = `${cell.key}\n${t('ov.heat.sessions', { n: cell.entry.sessions })}`
                return (
                  <Tooltip key={cell.key} label={label} side="top">
                    <button
                      type="button"
                      className={`lc-heat-cell lc-heat-${String(level)}${picked ? ' lc-heat-on' : ''}`}
                      aria-label={label}
                      aria-pressed={picked}
                      onClick={() => { if (props.onSelect !== undefined) props.onSelect(picked ? null : cell.key) }}
                    />
                  </Tooltip>
                )
              })}
            </div>
          ))}
        </div>
      </div>
    )
  }
}

/** Re-exported for the panel: today's key at render time (null-safe). */
export function todayKey(): string {
  /* v8 ignore next -- Date.now() is always a valid local day, so the null
   * arm of dayKeyOf is unreachable here; the '' fallback only types the seam. */
  return dayKeyOf(Date.now()) ?? ''
}

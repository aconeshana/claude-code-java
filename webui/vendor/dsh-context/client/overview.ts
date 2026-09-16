/**
 * The Context Dashboard's data layer: everything the panel renders is derived
 * here, off the root-scope `useSessions` standard prop. Each session-list
 * row carries the host-cached projection values — this plugin's
 * `contextTimeline` (composition, counts, cost) and `contextActivity` (the
 * daily ledger) — so the overview joins every session's insight WITHOUT
 * opening a single session log.
 *
 * The list is harness data (untrusted at the boundary): the snapshot is
 * re-proved field by field, every projection value passes its services.ts
 * sanitizer, and each row's derivation is isolated — one hostile row drops
 * to a metadata-only card or out of the list, never an error card. All
 * functions here are pure (the hook-level read lives in
 * {@link sessionsSnapshotOf}), so the panel's rendering stays a trivial map.
 */

import { workspaceTitleOf } from './workspacePath'
import { cacheHitPercent } from './format'
import type { CostCurrency } from './cost'
import { estimateSessionCost, mergeCostUsage } from './cost'
import type { ModelPrices } from './cost'
import { activityOf, asRecord, timelineOf } from './narrow'
import type { ContextActivity, ContextTimeline, SessionCostUsage } from '../shared/types'

/** One session-list row joined with its (sanitized) projection values. */
export interface OverviewRow {
  id: string
  /** Display title: durable title, project basename, then the raw id (the list's own ladder). */
  title: string
  cwd?: string | undefined
  updatedAt: number
  running: boolean
  /** The session the conversation currently shows — its card carries the "current" mark. */
  current: boolean
  /** The sanitized timeline head, or null when the host folded nothing for this session yet. */
  timeline: ContextTimeline | null
  /** The sanitized daily ledger, or null on an older host (the heatmap's empty note). */
  activity: ContextActivity | null
}

/**
 * claude-code-java: the rows come from `GET /api/session/context/overview`
 * (`{sessions:[{id,title,cwd,running,updatedAt,timeline,activity}],time}`)
 * instead of the harness session-list snapshot — live sessions only. Every
 * row re-proves itself; a hostile row drops whole.
 */
export function rowsOfOverview(payload: unknown, currentId: string | null | undefined): OverviewRow[] | null {
  const data = asRecord(payload)
  if (data === null || !Array.isArray(data.sessions)) return null
  const rows: OverviewRow[] = []
  for (const value of data.sessions) {
    const row = asRecord(value)
    if (row === null || typeof row.id !== 'string' || row.id === '') continue
    const title = typeof row.title === 'string' && row.title !== '' ? row.title : undefined
    const cwd = typeof row.cwd === 'string' && row.cwd !== '' ? row.cwd : undefined
    rows.push({
      id: row.id,
      title: title ?? projectOf(cwd) ?? row.id,
      ...(cwd !== undefined ? { cwd } : {}),
      updatedAt: typeof row.updatedAt === 'number' && Number.isFinite(row.updatedAt) ? row.updatedAt : 0,
      running: row.running === true,
      current: row.id === currentId,
      timeline: timelineOf(row.timeline),
      activity: activityOf(row.activity),
    })
  }
  return rows
}

/** The running-session tally for the footer entry's badge. */
export function runningCountOf(rows: readonly OverviewRow[] | null): number {
  if (rows === null) return 0
  let count = 0
  for (const row of rows) if (row.running) count++
  return count
}

/** The card's project name: the workspace browser's own basename derivation (both separators). */
export function projectOf(cwd: string | undefined): string | undefined {
  if (cwd === undefined || cwd === '') return undefined
  const title = workspaceTitleOf(cwd)
  return title === '' ? undefined : title
}

// ---- range / filter / sort -------------------------------------------------

export type OverviewRange = '7d' | '30d' | 'all'

/** The range window's start instant (epoch ms), or null for "all". */
export function rangeStartOf(range: OverviewRange, now: number): number | null {
  if (range === '7d') return now - 7 * 86_400_000
  if (range === '30d') return now - 30 * 86_400_000
  return null
}

export type OverviewSort = 'recent' | 'tokens' | 'context'

/**
 * The session's cumulative billed tokens (the host-folded cost buckets'
 * sum), or null when nothing was billed yet — the sort and the card stat
 * share this one figure.
 */
export function billedOf(timeline: ContextTimeline | null): number | null {
  const totals = usageTotalsOf(timeline?.cost)
  return totals?.total ?? null
}

/** The session's turn tally: the split head's precomputed count, else the retained records' count. */
export function turnsOf(timeline: ContextTimeline | null): number {
  if (timeline === null) return 0
  return timeline.counts?.turns ?? timeline.requests.length
}

/**
 * The session's first active day (the ledger's earliest key) — the card's
 * creation-date line. The harness's client-facing list rows carry no
 * per-session creation time, so the first billed day stands in; undefined
 * when the ledger is absent (an older host, or no model requests yet).
 */
export function createdDayOf(activity: ContextActivity | null): string | undefined {
  const days = activity?.days
  if (days === undefined) return undefined
  let first: string | undefined
  for (const key of Object.keys(days)) {
    if (first === undefined || key < first) first = key
  }
  return first
}

/**
 * The panel's row pipeline: range (by last-activity), then the heatmap's
 * picked day (sessions contributing to that day's merged ledger), then the
 * search box (title or directory substring). Each stage keeps the rows it
 * cannot prove out of the result — never an exception.
 */
export function filterRows(
  rows: readonly OverviewRow[],
  opts: { range: OverviewRange; day: string | null; query: string },
  now: number,
): OverviewRow[] {
  const start = rangeStartOf(opts.range, now)
  const query = opts.query.trim().toLowerCase()
  return rows.filter((row) => {
    if (start !== null && row.updatedAt < start) return false
    if (opts.day !== null) {
      const entry = row.activity?.days[opts.day]
      if (entry === undefined || (entry.tokens <= 0 && entry.requests <= 0)) return false
    }
    if (query !== '') {
      const inTitle = row.title.toLowerCase().includes(query)
      const inCwd = row.cwd !== undefined && row.cwd.toLowerCase().includes(query)
      if (!inTitle && !inCwd) return false
    }
    return true
  })
}

/** Order the filtered rows; the input array is never mutated. */
export function sortRows(rows: readonly OverviewRow[], sort: OverviewSort): OverviewRow[] {
  const copy = [...rows]
  if (sort === 'tokens') copy.sort((a, b) => (billedOf(b.timeline) ?? -1) - (billedOf(a.timeline) ?? -1))
  else if (sort === 'context') copy.sort((a, b) => (b.timeline?.current.total ?? -1) - (a.timeline?.current.total ?? -1))
  else copy.sort((a, b) => b.updatedAt - a.updatedAt)
  return copy
}

/** The session grid renders this many cards per page. */
export const OVERVIEW_PAGE_SIZE = 12

/**
 * The paged window over the sorted rows: the requested page clamped into
 * the live range, so a list that shrank between renders (a refresh, a
 * narrowed filter) keeps the view valid instead of showing a blank page.
 */
export function pageOf<T>(rows: readonly T[], page: number): { items: T[]; index: number; count: number } {
  const count = Math.max(1, Math.ceil(rows.length / OVERVIEW_PAGE_SIZE))
  const index = Math.min(Math.max(0, page), count - 1)
  return { items: rows.slice(index * OVERVIEW_PAGE_SIZE, (index + 1) * OVERVIEW_PAGE_SIZE), index, count }
}

// ---- aggregations ----------------------------------------------------------

/** The merged billed-bucket totals behind the KPI band and the cost estimate. */
export interface UsageTotals {
  input: number
  cacheRead: number
  cacheWrite: number
  output: number
  /** input + cacheRead + cacheWrite + output — the whole billed volume. */
  total: number
}

/**
 * Sum one cost usage's buckets (already sanitized per bucket by the
 * timeline boundary). Null when no side carried a bucket record, so the
 * caller's cells keep their dash instead of a fabricated zero.
 */
export function usageTotalsOf(usage: SessionCostUsage | null | undefined): UsageTotals | null {
  if (usage === null || usage === undefined) return null
  const totals: UsageTotals = { input: 0, cacheRead: 0, cacheWrite: 0, output: 0, total: 0 }
  let any = false
  for (const provider of Object.keys(usage)) {
    const models = usage[provider]
    // claude-code-java: `timelineOf`'s fast path hands the cost tree through
    // unsanitized below two levels, so a hostile row may carry null here;
    // it degrades to "no usage" rather than an error card.
    if (models === null || typeof models !== 'object') continue
    for (const model of Object.keys(models)) {
      const periods = models[model]
      if (periods === null || typeof periods !== 'object') continue
      for (const period of ['peak', 'off'] as const) {
        const bucket = periods[period]
        if (bucket === undefined || bucket === null || typeof bucket !== 'object') continue
        totals.input += bucket.uncached
        totals.cacheRead += bucket.cacheRead
        totals.cacheWrite += bucket.cacheWrite
        totals.output += bucket.output
        any = true
      }
    }
  }
  if (!any) return null
  totals.total = totals.input + totals.cacheRead + totals.cacheWrite + totals.output
  return totals
}

/** The KPI band's figures, priced from the models.dev book (null cost until the book lands). */
export interface OverviewKpis {
  /** Sessions in the current range filter. */
  sessions: number
  /** Sessions in the whole list (the range cell's "of N total" sub-line). */
  listed: number
  /** Cumulative billed tokens across the range's sessions. */
  tokens: number
  /** Their turn tally. */
  turns: number
  /** Estimated spend in the display currency (null: nothing priced). */
  cost: number | null
  /** Cache-hit share of billed input, truncated (null: nothing billed). */
  cacheHit: string | null
  /** Their completed tool calls. */
  toolCalls: number
  /** Their summed tool-run time (the tool-calls cell's sub-line). */
  toolsMs: number
  /** Their completed model calls. */
  calls: number
  /** Their summed wall time (the sessions' active time). */
  wallMs: number
}

export function kpisOf(
  rows: readonly OverviewRow[],
  listed: number,
  prices: ModelPrices | null | undefined,
  currency: CostCurrency,
): OverviewKpis {
  const usage = mergeCostUsage(...rows.map(row => row.timeline?.cost))
  const totals = usageTotalsOf(usage)
  let turns = 0
  let toolCalls = 0
  let toolsMs = 0
  let calls = 0
  let wallMs = 0
  for (const row of rows) {
    turns += turnsOf(row.timeline)
    const timing = row.timeline?.timing
    toolCalls += timing?.toolCalls ?? 0
    toolsMs += timing?.toolsMs ?? 0
    calls += timing?.calls ?? 0
    wallMs += timing?.wallMs ?? 0
  }
  return {
    sessions: rows.length,
    listed,
    tokens: totals?.total ?? 0,
    turns,
    cost: estimateSessionCost(usage, prices, currency),
    cacheHit: totals === null ? null : cacheHitPercent(totals.cacheRead, totals.input + totals.cacheRead + totals.cacheWrite),
    toolCalls,
    toolsMs,
    calls,
    wallMs,
  }
}

/** One merged day of the daily ledgers: billed tokens, model requests, and the sessions active that day. */
export interface DayTotals {
  tokens: number
  requests: number
  sessions: number
}

/**
 * Merge every row's daily ledger into one — the heatmap's data. A session
 * counts toward a day only when its own entry carries activity, mirroring
 * the day filter's predicate, so the cell's tooltip previews the click; a
 * zeroed entry is skipped whole. The merged record stays small even over
 * long histories.
 */
export function aggregateDays(rows: readonly OverviewRow[]): Record<string, DayTotals> {
  const days: Record<string, DayTotals> = {}
  // Widened honestly: a Record index read can miss at runtime.
  const byKey: Record<string, DayTotals | undefined> = days
  for (const row of rows) {
    if (row.activity === null) continue
    for (const key of Object.keys(row.activity.days)) {
      const entry = row.activity.days[key]
      if (entry.tokens <= 0 && entry.requests <= 0) continue
      const prev = byKey[key]
      if (prev === undefined) days[key] = { tokens: entry.tokens, requests: entry.requests, sessions: 1 }
      else {
        prev.tokens += entry.tokens
        prev.requests += entry.requests
        prev.sessions++
      }
    }
  }
  return days
}

// ---- presentation helpers --------------------------------------------------

/**
 * The row's relative-activity label ("3m ago"), unit-stepped: under a
 * minute reads "just now", then minutes, hours, days. A future or invalid
 * timestamp reads as "just now" (clock skew is not an error worth a dash).
 */
export function relativeTime(t: (key: string, params?: Record<string, string | number>) => string, updatedAt: number, now: number): string {
  const diff = now - updatedAt
  if (!Number.isFinite(diff) || diff < 60_000) return t('ov.time.now')
  const minutes = Math.floor(diff / 60_000)
  if (minutes < 60) return t('ov.time.m', { n: minutes })
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return t('ov.time.h', { n: hours })
  return t('ov.time.d', { n: Math.floor(hours / 24) })
}

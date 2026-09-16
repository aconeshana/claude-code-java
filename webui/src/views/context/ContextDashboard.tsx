/**
 * The Context Dashboard — claude-code-java re-assembly of dsh-context's
 * `client/components/overviewPanel.tsx` + `overviewButton.tsx`: the
 * sidebar-foot entry (running badge) and the cross-session insight overlay
 * (KPI 2×3 block over the activity heatmap beside the searchable, sortable,
 * paged session-card grid; heatmap day pins the list).
 *
 * Departures from upstream: rows come from the gateway's `/overview` route
 * (live sessions only — the process's active TUI + open headless sessions —
 * so a cold session never appears); there is no workspace grouping (no
 * `useWorkspaces` seat, so the group chips are gone); a card click selects
 * the session through the app's sessions store.
 */

import { useEffect, useMemo, useState, type ReactElement } from 'react'
import { createPortal } from 'react-dom'
import { useContextOverview } from '../../store/contextOverview'
import { useConversations } from '../../store/conversations'
import { useSessions } from '../../store/sessions'
import { useContextKit } from './useContextKit'
import { estimateSessionCost, formatCost, type CostCurrency, type ModelPrices } from '@dsh-context/client/cost'
import { fmt } from '@dsh-context/client/format'
import { ContextIcon } from '@dsh-context/client/icon'
import { useModelPrices } from '@dsh-context/client/modelPrices'
import {
  aggregateDays, filterRows, kpisOf, pageOf, rowsOfOverview, sortRows,
  type OverviewRange, type OverviewRow, type OverviewSort,
} from '@dsh-context/client/overview'
import { contextSettings } from '@dsh-context/client/settings'
import type { ViewKit } from '@dsh-context/client/viewkit'
import { makeErrorBoundary } from '@dsh-context/client/components/errorBoundary'
import { useEscapeClose } from '@dsh-context/client/components/escapeClose'
import { makeHeatmap, todayKey } from '@dsh-context/client/components/heatmap'
import { makeOverviewCard } from '@dsh-context/client/components/overviewCard'
import '@dsh-context/index'

const RANGES: readonly OverviewRange[] = ['7d', '30d', 'all']
const SORTS: readonly OverviewSort[] = ['recent', 'tokens', 'context']
/** Re-pull cadence while the panel is open (the heads move as turns settle). */
const REFRESH_MS = 5_000

/** The sidebar-foot entry: icon (+ label when wide) with the running-turn badge. */
export function ContextDashboardButton({ wide = true }: { wide?: boolean }): ReactElement | null {
  const { kit } = useContextKit()
  const setOpen = useContextOverview((state) => state.setOpen)
  const running = useConversations((state) =>
    Object.values(state.conversations).filter((conversation) => conversation.turnRunning).length)
  const [entry, setEntry] = useState(() => contextSettings.insightsEntry())
  useEffect(() => contextSettings.store.subscribe(() => { setEntry(contextSettings.insightsEntry()) }), [])
  if (entry === 'hide') return null
  const label = kit.t('ov.entry')
  return (
    <button
      type="button"
      className={wide ? 'lc-ov-entry' : 'lc-ov-entry lc-ov-entry-rail'}
      title={label}
      aria-label={label}
      aria-haspopup="dialog"
      onClick={() => { setOpen(true) }}
    >
      <ContextIcon size={wide ? 16 : 18} className="lc-ov-entry-icon" />
      {wide && <span className="lc-ov-entry-label">{label}</span>}
      {running > 0 && <span className="lc-ov-badge" aria-hidden="true">{running}</span>}
    </button>
  )
}

function makeCards(kit: ViewKit) {
  return {
    Heatmap: makeHeatmap(kit),
    OverviewCard: makeOverviewCard(kit),
    ErrorBoundary: makeErrorBoundary(kit.t),
  }
}

export function ContextDashboard(): ReactElement | null {
  const { kit, locale } = useContextKit()
  const cards = useMemo(() => makeCards(kit), [kit])
  const open = useContextOverview((state) => state.open)
  if (!open) return null
  const { ErrorBoundary } = cards
  return createPortal(
    <ErrorBoundary>
      <DashboardBody kit={kit} locale={locale} cards={cards} />
    </ErrorBoundary>,
    document.body,
  )
}

function DashboardBody({ kit, locale, cards }: {
  kit: ViewKit
  locale: string
  cards: ReturnType<typeof makeCards>
}): ReactElement {
  const { t, fmtDuration } = kit
  const { Heatmap, OverviewCard } = cards
  const { prices } = useModelPrices()
  const payload = useContextOverview((state) => state.payload)
  const error = useContextOverview((state) => state.error)
  const refresh = useContextOverview((state) => state.refresh)
  const setOpen = useContextOverview((state) => state.setOpen)
  const currentId = useSessions((state) => state.selectedSessionId)
  const select = useSessions((state) => state.select)

  const [range, setRange] = useState<OverviewRange>('30d')
  const [day, setDay] = useState<string | null>(null)
  const [query, setQuery] = useState('')
  const [sort, setSort] = useState<OverviewSort>('recent')
  const [page, setPage] = useState(0)
  const close = (): void => { setOpen(false) }
  useEscapeClose(true, close)

  useEffect(() => {
    const timer = window.setInterval(() => { void refresh() }, REFRESH_MS)
    return () => { window.clearInterval(timer) }
  }, [refresh])
  useEffect(() => { setPage(0) }, [range, day, query, sort])

  const rows = useMemo(() => rowsOfOverview(payload, currentId), [payload, currentId])
  const currency: CostCurrency = locale === 'zh' ? 'cny' : 'usd'
  const now = Date.now()
  const allRows = rows ?? []
  const ranged = filterRows(allRows, { range, day: null, query: '' }, now)
  const visible = sortRows(filterRows(ranged, { range: 'all', day, query }, now), sort)
  const paged = pageOf(visible, page)
  const kpi = kpisOf(ranged, allRows.length, prices, currency)
  const days = aggregateDays(allRows)
  const openOne = (id: string): void => {
    void select(id)
    close()
  }

  return (
    <div className="lc-ov-backdrop" onClick={close} data-testid="context-dashboard">
      <div className="lc-ov-card" role="dialog" aria-modal="true" aria-label={t('ov.title')} onClick={(ev) => { ev.stopPropagation() }}>
        <div className="lc-ov-head">
          <ContextIcon size={18} className="lc-ov-head-icon" />
          <span className="lc-ov-title">{t('ov.title')}</span>
          <div className="lc-gran lc-ov-range" role="group" aria-label={t('ov.range.label')}>
            {RANGES.map((r) => (
              <button
                key={r}
                type="button"
                className={'lc-gran-btn' + (range === r ? ' lc-gran-on' : '')}
                onClick={() => { setRange(r) }}
              >{t('ov.range.' + r)}</button>
            ))}
          </div>
          <button
            type="button"
            className="lc-modal-close hover:text-(--dsw-alias-label-primary) hover:bg-(--dsw-alias-bg-layer-2)"
            aria-label={t('cmd.close')}
            onClick={close}
          >×</button>
        </div>

        {rows === null ? (
          <div className="lc-empty">{error ?? t(payload === null ? 'loading' : 'ov.unavailable')}</div>
        ) : (
          <div className="lc-ov-body">
            <div className="lc-ov-left">
              <div className="lc-ov-kpis">
                <div className="lc-stat lc-ov-kpi">
                  <span className="lc-stat-label">{t('ov.kpi.sessions')}</span>
                  <span className="lc-stat-value">{kpi.sessions}</span>
                  <span className="lc-stat-sub">{t('ov.kpi.ofTotal', { n: kpi.listed })}</span>
                </div>
                <div className="lc-stat lc-ov-kpi">
                  <span className="lc-stat-label">{t('ov.kpi.tokens')}</span>
                  <span className="lc-stat-value">{fmt(kpi.tokens)}</span>
                  <span className="lc-stat-sub">{t('stats.turns')} {fmt(kpi.turns)}</span>
                </div>
                <div className="lc-stat lc-ov-kpi">
                  <span className="lc-stat-label">{t('stats.cost')}</span>
                  <span className="lc-stat-value">{kpi.cost === null ? '—' : formatCost(kpi.cost, currency)}</span>
                  <span className="lc-stat-sub">{t('ov.kpi.costSub')}</span>
                </div>
                <div className="lc-stat lc-ov-kpi">
                  <span className="lc-stat-label">{t('stats.cacheHit')}</span>
                  <span className="lc-stat-value">{kpi.cacheHit === null ? '—' : kpi.cacheHit + '%'}</span>
                  <span className="lc-stat-sub">{t('ov.kpi.cacheSub')}</span>
                </div>
                <div className="lc-stat lc-ov-kpi">
                  <span className="lc-stat-label">{t('stats.toolCalls')}</span>
                  <span className="lc-stat-value">{fmt(kpi.toolCalls)}</span>
                  <span className="lc-stat-sub">{t('ov.kpi.toolSub', { dur: fmtDuration(kpi.toolsMs) })}</span>
                </div>
                <div className="lc-stat lc-ov-kpi">
                  <span className="lc-stat-label">{t('timing.total')}</span>
                  <span className="lc-stat-value">{fmtDuration(kpi.wallMs)}</span>
                  <span className="lc-stat-sub">{t('ov.kpi.wallSub', { n: fmt(kpi.calls) })}</span>
                </div>
              </div>
              <div className="lc-card lc-ov-heat-card">
                <div className="lc-card-title">
                  <span className="lc-card-title-text">{t('ov.heat.title')}</span>
                  <span className="lc-card-sub">{t('ov.heat.sub')}</span>
                </div>
                <Heatmap days={days} selected={day} onSelect={setDay} today={todayKey()} />
              </div>
            </div>

            <div className="lc-ov-right">
              <div className="lc-ov-list-head">
                <span className="lc-ov-list-title">{t('ov.list.title')}</span>
                <span className="lc-ov-list-count">{visible.length}</span>
                {day !== null && (
                  <button type="button" className="lc-ov-day-chip" title={t('ov.list.dayClear')} onClick={() => { setDay(null) }}>
                    {t('ov.list.dayFilter', { day })} ×
                  </button>
                )}
                <input
                  className="lc-ov-search"
                  type="search"
                  value={query}
                  placeholder={t('ov.list.search')}
                  aria-label={t('ov.list.search')}
                  onChange={(ev) => { setQuery(ev.target.value) }}
                />
                <div className="lc-gran" role="group" aria-label={t('ov.list.sortLabel')}>
                  {SORTS.map((s) => (
                    <button
                      key={s}
                      type="button"
                      className={'lc-gran-btn' + (sort === s ? ' lc-gran-on' : '')}
                      onClick={() => { setSort(s) }}
                    >{t('ov.list.sort.' + s)}</button>
                  ))}
                </div>
              </div>

              {visible.length === 0 ? (
                <div className="lc-empty">{t(allRows.length === 0 ? 'ov.list.empty' : 'ov.list.noMatch')}</div>
              ) : (
                <>
                  <div className="lc-ov-grid">
                    {paged.items.map((row) => (
                      <OverviewCard
                        key={row.id}
                        row={row}
                        costLabel={cardCostOf(row, prices, currency)}
                        now={now}
                        onOpen={openOne}
                      />
                    ))}
                  </div>
                  {paged.count > 1 && (
                    <div className="lc-ov-pager" role="navigation" aria-label={t('ov.list.pager')}>
                      <button
                        type="button"
                        className="lc-ov-pager-btn"
                        disabled={paged.index === 0}
                        aria-label={t('ov.list.prev')}
                        onClick={() => { setPage(paged.index - 1) }}
                      >‹</button>
                      <span className="lc-ov-pager-n">{t('ov.list.page', { n: paged.index + 1, total: paged.count })}</span>
                      <button
                        type="button"
                        className="lc-ov-pager-btn"
                        disabled={paged.index === paged.count - 1}
                        aria-label={t('ov.list.next')}
                        onClick={() => { setPage(paged.index + 1) }}
                      >›</button>
                    </div>
                  )}
                </>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  )
}

/** One card's priced cost label, or the dash (nothing billed, unpriceable model). */
function cardCostOf(row: OverviewRow, prices: ModelPrices | null, currency: CostCurrency): string {
  if (row.timeline?.cost === undefined) return '—'
  const cost = estimateSessionCost(row.timeline.cost, prices, currency)
  return cost === null ? '—' : formatCost(cost, currency)
}

/**
 * Context tab root — the claude-code-java re-assembly of dsh-context's
 * `client/components/contextView.tsx`. Card order is upstream's (Stats →
 * Tokens → Timing → Current composition → Trend + RequestDetail → Browser →
 * Events → File activity → Agent network); every card is the vendored
 * pure-props component. What differs is the data plane:
 * - the slim head and the detail collections come from
 *   `store/contextTimeline.ts` (REST + mirror-frame-triggered refresh)
 *   instead of the harness projection seat;
 * - the meter figures (pressure / breakdown / usage) come from the existing
 *   `/api/session/context` answer through `adapters.ts`;
 * - the subagent cost and the Agent network read the detail payload's
 *   `agents` extension (our subagents are in-session tool runs);
 * - there is no workspace opener, sidebar preview, image loader, plugin
 *   card, or upgrade gate.
 */

import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type ReactElement } from 'react'
import { useSessionContext } from '../../store/sessionContext'
import { useSessions } from '../../store/sessions'
import { detailFetcher, makeContentFetcher, makeHeaderFetcher, useContextTimeline, type ContextJumpTarget } from '../../store/contextTimeline'
import { resolveJumpSeq } from './jump'
import { useContextKit } from './useContextKit'
import type { ContextEventRecord, RequestRecord, SurfaceNode } from '@dsh-context/shared/types'
import { breakdownOf, pressureOf, usageOf } from '@dsh-context/client/adapters'
import { subagentCostOf } from '@dsh-context/client/agentTree'
import { briefNodes, briefOf } from '@dsh-context/client/brief'
import { activityOfOps, locateStepOf } from '@dsh-context/client/fileActivity'
import type { FileOp } from '@dsh-context/client/fileActivity'
import { headlineOf } from '@dsh-context/client/headline'
import { numOf } from '@dsh-context/client/narrow'
import { contextSettings } from '@dsh-context/client/settings'
import { useTimelineSource } from '@dsh-context/client/timelineSource'
import type { ViewKit } from '@dsh-context/client/viewkit'
import { makeAgentGraph } from '@dsh-context/client/components/agentGraph'
import { makeContextBrowser } from '@dsh-context/client/components/browser'
import { makeCurrentComposition } from '@dsh-context/client/components/currentComposition'
import { makeDetailNote } from '@dsh-context/client/components/detailNote'
import { makeDonut } from '@dsh-context/client/components/donut'
import { makeErrorBoundary } from '@dsh-context/client/components/errorBoundary'
import { makeEventList } from '@dsh-context/client/components/events'
import { makeFileCard } from '@dsh-context/client/components/fileCard'
import { makeRequestDetail } from '@dsh-context/client/components/requestDetail'
import { makeLegend, makeStackedBar } from '@dsh-context/client/components/stackedBar'
import { countsOfRecords, makeStatsContext } from '@dsh-context/client/components/statsContext'
import { makeStatsTiming } from '@dsh-context/client/components/statsTiming'
import { makeStatsTokens } from '@dsh-context/client/components/statsTokens'
import { aggregateByTurn, attachMarkers, jumpTargetOf, makeTrendChart, turnStepsOf } from '@dsh-context/client/components/trendChart'
import '@dsh-context/index'

// The context page scrolls in its own `.lc-root`; a module-level per-session
// position ledger survives tab remounts (the chat's chatScroll pattern).
const viewScroll = new Map<string, number>()

const EVENT_KINDS = ['inject', 'compaction', 'prune', 'model', 'mode'] as const

function makeCards(kit: ViewKit) {
  const StackedBar = makeStackedBar(kit)
  const Legend = makeLegend(kit)
  const Donut = makeDonut(kit)
  return {
    StackedBar,
    CurrentComposition: makeCurrentComposition(kit, StackedBar, Legend),
    TrendChart: makeTrendChart(kit),
    RequestDetail: makeRequestDetail(kit, StackedBar),
    EventList: makeEventList(kit),
    FileCard: makeFileCard(kit, contextSettings),
    StatsContext: makeStatsContext(kit),
    StatsTiming: makeStatsTiming(kit, Donut),
    StatsTokens: makeStatsTokens(kit, Donut),
    DetailNote: makeDetailNote(kit),
    ContextBrowser: makeContextBrowser(kit, StackedBar, contextSettings),
    AgentGraph: makeAgentGraph(kit),
    ErrorBoundary: makeErrorBoundary(kit.t),
  }
}

export function ContextView({ sessionId }: { sessionId: string | null }): ReactElement {
  const { kit, locale } = useContextKit()
  const cards = useMemo(() => makeCards(kit), [kit])
  const { ErrorBoundary } = cards
  return (
    <ErrorBoundary>
      <ContextViewBody sessionId={sessionId} kit={kit} locale={locale} cards={cards} />
    </ErrorBoundary>
  )
}

function ContextViewBody({ sessionId, kit, locale, cards }: {
  sessionId: string | null
  kit: ViewKit
  locale: string
  cards: ReturnType<typeof makeCards>
}): ReactElement {
  const { t } = kit
  const {
    CurrentComposition, TrendChart, RequestDetail, EventList, FileCard, StatsContext, StatsTiming, StatsTokens,
    DetailNote, ContextBrowser, AgentGraph,
  } = cards
  const key = sessionId ?? ''

  // Viewer registration: the store polls the head only while a viewer is open.
  const open = useContextTimeline((state) => state.open)
  useEffect(() => {
    if (key === '') return
    return open(key)
  }, [key, open])
  const entry = useContextTimeline((state) => state.sessions[key])
  const head = entry?.head ?? null
  const source = useTimelineSource(head, key, detailFetcher)
  const data = source.data
  const detail = source.detail

  // The meter figures the composer ring and stats pills already read.
  const meterSession = useSessionContext((state) => state.sessionId)
  const meterUsage = useSessionContext((state) => state.usage)
  const meterMetrics = useSessionContext((state) => state.metrics)
  const meterMatches = meterSession === sessionId
  const pressure = useMemo(() => (meterMatches ? pressureOf(meterUsage) : null), [meterMatches, meterUsage])
  const breakdown = useMemo(() => (meterMatches ? breakdownOf(meterUsage) : null), [meterMatches, meterUsage])
  const usage = useMemo(() => (meterMatches ? usageOf(meterMetrics) : null), [meterMatches, meterMetrics])
  const headers = detail?.headers ?? null
  const agents = detail?.agents ?? EMPTY_AGENTS
  const subUsage = useMemo(() => subagentCostOf(agents), [agents])

  const [selectedSeq, setSelectedSeq] = useState<number | null>(null)
  const [hoveredSeq, setHoveredSeq] = useState<number | null>(null)
  const [hoverTurn, setHoverTurn] = useState<number | null>(null)
  const [granularity, setGranularity] = useState<'step' | 'turn'>(() => contextSettings.defaultGranularity())
  const [trendMode, setTrendMode] = useState<'total' | 'delta'>(() => contextSettings.defaultTrendMode())
  const [adaptive, setAdaptive] = useState(false)
  const [focusTurn, setFocusTurn] = useState<number | null>(null)
  const [jumpTarget, setJumpTarget] = useState<ContextJumpTarget | null>(null)
  const [hoverCat, setHoverCat] = useState<string | null>(null)
  const [focusCat, setFocusCat] = useState<string | null>(null)
  const [pickedKinds, setPickedKinds] = useState<string[]>([...EVENT_KINDS])
  const toggleKind = (k: string) => {
    setPickedKinds((p) => {
      if (p.length === EVENT_KINDS.length) return [k]
      if (!p.includes(k)) return [...p, k]
      return p.length === 1 ? [...EVENT_KINDS] : p.filter(x => x !== k)
    })
  }
  const [nodeFocus, setNodeFocus] = useState<{ step: number | 'live'; seq: number; cat: SurfaceNode['cat'] } | null>(null)
  const clearNodeFocus = useCallback(() => { setNodeFocus(null) }, [])

  const fetchContent = useMemo(() => (key === '' ? undefined : makeContentFetcher(key)), [key])
  const fetchHeader = useMemo(() => (key === '' ? undefined : makeHeaderFetcher(key)), [key])

  const rootRef = useRef<HTMLDivElement | null>(null)
  const restoredRef = useRef<string | null>(null)
  useLayoutEffect(() => {
    if (key === '' || data === null) return
    if (restoredRef.current === key) return
    restoredRef.current = key
    const scroller = rootRef.current
    if (scroller === null) return
    scroller.scrollTop = viewScroll.get(key) ?? 0
  }, [key, data])
  useLayoutEffect(() => {
    return () => {
      if (key === '') return
      const scroller = rootRef.current
      if (scroller === null) return
      viewScroll.set(key, scroller.scrollTop)
    }
  }, [key])

  const requests = data ? data.requests : EMPTY_REQUESTS
  const events = data ? data.events : EMPTY_EVENTS
  const counts = data?.counts ?? countsOfRecords(requests, events)
  const kindCounts: Record<string, number | undefined> = { inject: counts.injects, compaction: counts.compactions, prune: counts.prunes }
  const shownEvents = pickedKinds.length === EVENT_KINDS.length ? events : events.filter(e => pickedKinds.includes(e.kind))
  const displayRequests = useMemo(
    () => (granularity === 'turn' ? aggregateByTurn(requests) : requests),
    [requests, granularity],
  )
  const stepsOf = useMemo(() => turnStepsOf(requests), [requests])
  const markers = useMemo(() => attachMarkers(displayRequests, events), [displayRequests, events])

  // Chat → Context jump, leg 1: the relay's one-shot request for this session.
  const jump = useContextTimeline((state) => state.jump)
  const takeJump = useContextTimeline((state) => state.takeJump)
  useEffect(() => {
    if (key === '' || jump === null || jump.sessionId !== key) return
    const target = takeJump(key)
    if (target !== null) setJumpTarget(target)
  }, [key, jump, takeJump])

  // Leg 2: turn-level pin once the detail collections are in. The chat row
  // addressed the reply by time/turn; resolve it to a request seq here.
  const detailReady = source.detailState === 'ready' || source.detailState === 'legacy'
  useEffect(() => {
    if (jumpTarget === null || data === null || !detailReady) return
    setJumpTarget(null)
    const seq = resolveJumpSeq(requests, jumpTarget)
    if (seq === null) return
    const target = jumpTargetOf(aggregateByTurn(requests), seq)
    if (target === null) return
    setGranularity('turn')
    setSelectedSeq(target.seq)
    setFocusTurn(target.turn ?? 0)
    if (rootRef.current !== null) rootRef.current.scrollTop = 0
  }, [jumpTarget, data, requests, detailReady])

  const briefList = useMemo(() => (data ? briefNodes(data) : []), [data])

  let pinnedIdx = -1
  for (let i = 0; i < displayRequests.length; i++) if (displayRequests[i].seq === selectedSeq) pinnedIdx = i
  const pinnedReq = pinnedIdx >= 0 ? displayRequests[pinnedIdx] : null
  let activeIdx = -1
  if (hoveredSeq !== null) {
    for (let i = 0; i < displayRequests.length; i++) if (displayRequests[i].seq === hoveredSeq) { activeIdx = i; break }
  }
  if (activeIdx < 0) activeIdx = pinnedIdx
  if (activeIdx < 0 && displayRequests.length > 0) activeIdx = displayRequests.length - 1
  const activeReq = activeIdx >= 0 ? displayRequests[activeIdx] : null
  let filesBefore: number | null = null
  if (activeReq !== null) {
    const ri = requests.findIndex(r => r.seq === activeReq.seq)
    filesBefore = ri + 1 < requests.length ? requests[ri + 1].seq : null
  }
  const brief = useMemo(
    () => (activeReq !== null ? briefOf(briefList, displayRequests, activeIdx) : null),
    [activeReq, briefList, displayRequests, activeIdx],
  )

  const fileActivity = useMemo(
    () => activityOfOps(data?.fileOps ?? [], data?.archive ?? [], filesBefore),
    [data, filesBefore],
  )
  // The session's workspace root from the catalog row (./-relative file paths).
  const workspace = useSessions((state) => {
    if (sessionId === null) return undefined
    for (const project of state.projects) {
      for (const session of project.sessions) {
        if (session.id === sessionId) return session.cwd ?? project.project_path ?? undefined
      }
    }
    return undefined
  })
  const locateFileOp = useCallback((op: FileOp): void => {
    const seq = op.parent ?? op.seq
    const step = locateStepOf(requests, seq, op.gone)
    if (step === null) return
    setNodeFocus({ step, seq, cat: 'tool' })
  }, [requests])
  const locateNode = useCallback((node: SurfaceNode, isResponse: boolean): void => {
    if (activeReq === null) return
    const next = isResponse && activeIdx + 1 < displayRequests.length ? displayRequests[activeIdx + 1] : null
    const step: number | 'live' = isResponse ? (next !== null ? next.seq : 'live') : activeReq.seq
    setNodeFocus({ step, seq: node.seq, cat: node.cat })
  }, [activeReq, activeIdx, displayRequests])

  if (sessionId === null) {
    return <div className="lc-root" ref={rootRef}><div className="lc-empty">{t('shell.noSession')}</div></div>
  }
  if (entry?.cold === true) {
    return <div className="lc-root" ref={rootRef}><div className="lc-empty">{t('shell.cold')}</div></div>
  }
  if (!data) {
    return (
      <div className="lc-root" ref={rootRef}>
        <div className="lc-empty">{entry?.failed === true ? t('detail.failed') : t('loading')}</div>
      </div>
    )
  }

  const markerOf = (req: RequestRecord): ContextEventRecord | undefined => {
    const i = displayRequests.indexOf(req)
    return i >= 0 ? markers[i] : undefined
  }

  const headline = headlineOf(data, pressure, breakdown)
  let fileScope = t('files.scopeLatest')
  if (activeReq !== null && filesBefore !== null) {
    fileScope = activeReq.stepCount !== undefined && activeReq.stepCount > 1
      ? t('detail.turn', { t: activeReq.turn ?? 0, n: activeReq.stepCount })
      : t('detail.step', { t: activeReq.turn ?? 0, s: activeReq.step ?? 0, n: stepsOf(activeReq.turn) })
  }
  let activeTurn: number | null = hoverTurn
  if (activeTurn === null && hoveredSeq !== null) {
    for (const req of displayRequests) if (req.seq === hoveredSeq) { activeTurn = req.turn ?? null; break }
  }
  const trendHoverCat = hoverCat !== null && hoverCat !== 'free' ? hoverCat : null
  const subtitle = (data.model ?? '') + (data.provider ? ' · ' + data.provider : '')

  const compositionCard = (
    <CurrentComposition head={headline} subtitle={subtitle} hoverKey={hoverCat} onHoverKey={setHoverCat} />
  )
  const trendCard = (
    <div className="lc-card">
      <div className="lc-card-title">
        <span className="lc-card-title-text">{t('trend.title')}</span>
        <span className="lc-gran lc-trend-adaptive" role="group" title={t('trend.adaptiveHint')}>
          <button
            type="button"
            className={'lc-gran-btn' + (adaptive ? ' lc-gran-on' : '')}
            onClick={() => { setAdaptive(on => !on) }}
          >{t('trend.adaptive')}</button>
        </span>
        {focusCat !== null
          ? <span className="lc-card-sub">{t('trend.focus', { cat: kit.catLabel(focusCat) })}</span>
          : null}
        <div className="lc-trend-ctl">
          <div className="lc-gran">
            <button
              className={'lc-gran-btn' + (granularity === 'step' ? ' lc-gran-on' : '')}
              onClick={() => { setGranularity('step') }}
            >{t('gran.step')}</button>
            <button
              className={'lc-gran-btn' + (granularity === 'turn' ? ' lc-gran-on' : '')}
              onClick={() => { setGranularity('turn') }}
            >{t('gran.turn')}</button>
          </div>
          <div className="lc-gran" title={t('gran.modeHint')}>
            <button
              className={'lc-gran-btn' + (trendMode === 'total' ? ' lc-gran-on' : '')}
              onClick={() => { setTrendMode('total') }}
            >{t('gran.total')}</button>
            <button
              className={'lc-gran-btn' + (trendMode === 'delta' ? ' lc-gran-on' : '')}
              onClick={() => { setTrendMode('delta') }}
            >{t('gran.delta')}</button>
          </div>
        </div>
      </div>
      {displayRequests.length === 0
        ? (detailReady
          ? <div className="lc-empty">{t('trend.empty')}</div>
          : <DetailNote state={source.detailState === 'failed' ? 'failed' : 'loading'} onRetry={source.retryDetail} />)
        : (
          <div>
            <TrendChart
              key={sessionId}
              requests={displayRequests}
              markers={markers}
              selectedSeq={pinnedReq ? pinnedReq.seq : null}
              hoveredSeq={hoveredSeq}
              activeTurn={activeTurn}
              granularity={granularity}
              mode={trendMode}
              focusTurn={focusTurn}
              hoverCat={trendHoverCat}
              focusCat={focusCat}
              adaptive={adaptive}
              onSelect={setSelectedSeq}
              onHover={setHoveredSeq}
              onHoverTurn={setHoverTurn}
              onPickTurn={(turn) => { setGranularity('turn'); setFocusTurn(turn) }}
              onFocusTurnHandled={() => { setFocusTurn(null) }}
            />
            <RequestDetail
              request={activeReq}
              prev={trendMode === 'delta' && activeIdx >= 0 ? (activeIdx > 0 ? displayRequests[activeIdx - 1] : null) : undefined}
              marker={activeReq !== null ? markerOf(activeReq) : undefined}
              brief={brief}
              stepsOf={stepsOf}
              onLocate={locateNode}
              hoverKey={trendHoverCat}
            />
          </div>
        )}
    </div>
  )
  const browserCard = (
    <ContextBrowser
      data={data}
      headers={headers}
      fetchContent={fetchContent}
      fetchHeader={fetchHeader}
      previewSeq={hoveredSeq}
      pinSeq={pinnedReq !== null ? pinnedReq.seq : null}
      hoverKey={hoverCat}
      onHoverKey={setHoverCat}
      onOpenCat={setFocusCat}
      nodeFocus={nodeFocus}
      onNodeFocusHandled={clearNodeFocus}
      detailState={source.detailState}
      onDetailRetry={source.retryDetail}
    />
  )

  return (
    <div className="lc-root" ref={rootRef} data-testid="context-view">
      <div className="lc-cols lc-head">
        <StatsContext counts={counts} humanInputs={data.humanInputs} toolCalls={data.toolCalls} usage={usage}
          cost={data.cost} locale={locale} subUsage={subUsage} />
        <StatsTokens usage={usage} current={data.current} breakdown={breakdown} />
        <StatsTiming timing={data.timing ?? null} />
      </div>

      <div className="lc-cols lc-cols-main">
        <div className="lc-col flex-1 min-w-[min(360px,100%)]">{compositionCard}{trendCard}</div>
        <div className="lc-col lc-col-browser flex-1 min-w-[min(360px,100%)]">{browserCard}</div>
      </div>

      <div className="lc-cols">
        <div className="lc-card lc-col flex-1 min-w-[min(360px,100%)]">
          <div className="lc-card-title">
            <span className="lc-card-title-text">{t('events.title')}</span>
            <div className="lc-kinds @max-[380px]/lc-card:flex-wrap">
              {EVENT_KINDS.map((k) => {
                const n = kindCounts[k]
                return (
                  <button
                    key={k}
                    data-kind={k}
                    className={'lc-gran-btn' + (pickedKinds.includes(k) ? ' lc-gran-on lc-kind-' + k : '')}
                    onClick={() => { toggleKind(k) }}
                  >
                    {t('kind.' + k)}
                    {n !== undefined ? <span className="lc-kind-n">{kit.fmt(n)}</span> : null}
                  </button>
                )
              })}
            </div>
          </div>
          <EventList events={shownEvents} state={source.detailState} onRetry={source.retryDetail} />
        </div>
        <FileCard activity={fileActivity} scope={fileScope} workspace={workspace}
          onLocate={locateFileOp}
          state={source.detailState} onRetry={source.retryDetail} />
      </div>

      <AgentGraph
        sessionId={sessionId}
        agents={agents}
        self={{
          head: headline,
          billed: usage !== null ? numOf(usage.uncachedInputTokens) + numOf(usage.outputTokens)
            + numOf(usage.cacheReadTokens) + numOf(usage.cacheWriteTokens) : null,
          requests: requests.length,
        }}
      />

      <div className="lc-foot">{t('footer')}</div>
    </div>
  )
}

const EMPTY_AGENTS: never[] = []
const EMPTY_REQUESTS: RequestRecord[] = []
const EMPTY_EVENTS: ContextEventRecord[] = []

/**
 * The Agent network card — the foot of the Context tab: the session's
 * subagent calls as a node graph under the session's own node, where every
 * node is a donut of its context composition (the root: the tab's live
 * headline; a subagent: its reported usage split) and the inspector strip
 * mirrors the hovered node. Vendored from dsh-context; claude-code-java
 * feeds it the detail payload's `agents` extension (our subagents are
 * in-session `Task`/`Agent` runs, not separate sessions), so the
 * session-list snapshot, cold-head fetches, and click-to-navigate are cut.
 */

import { useEffect, useMemo, useRef, useState, type KeyboardEvent, type ReactElement } from 'react'
import { CATS } from '../categories'
import { containHorizontalOverscroll } from '../overscroll'
import type { ViewKit } from '../viewkit'
import type { AgentRecord } from '../../shared/types'
import type { AgentNode, AgentSelfStats } from '../agentTree'
import {
  AGENT_NODE_R,
  AGENT_RING_R,
  agentForestOf,
  fmtDurationCompact,
  layoutForest,
  ringSegments,
} from '../agentTree'

export interface AgentGraphProps {
  sessionId?: string | undefined
  /** The session's subagent calls (the detail payload's `agents`). */
  agents: readonly AgentRecord[]
  /** Live stats of the current session from the tab's own head. */
  self?: AgentSelfStats | undefined
}

/** Caption box height under a node (3 wrapped label lines + the tokens line). */
const CAPTION_H = 60

/** Fallback arc color for pressure-only nodes (no composition data), by fill ratio. */
export function ringColorOf(pct: number | null): string {
  if (pct === null) return 'var(--dsw-alias-border-l1)'
  if (pct >= 90) return 'var(--color-red-500)'
  if (pct >= 70) return 'var(--color-amber-500)'
  return 'var(--color-green-500)'
}

export function makeAgentGraph(
  kit: ViewKit,
): (props: AgentGraphProps) => ReactElement | null {
  const { t, fmt, catLabel } = kit

  function AgentGraph(props: AgentGraphProps): ReactElement | null {
    const sessionId = props.sessionId
    const [hoverId, setHoverId] = useState<string | null>(null)

    // The layout is fully responsive: re-run it whenever the stage's visible
    // width changes (sidebar toggles, window resizes, split views).
    const stageRef = useRef<HTMLDivElement | null>(null)
    const [stageWidth, setStageWidth] = useState(0)
    useEffect(() => {
      /* v8 ignore start -- jsdom has neither ResizeObserver nor layout; tests exercise the natural-pitch fallback (stageWidth 0). */
      const el = stageRef.current
      if (el === null || typeof ResizeObserver !== 'function') return
      setStageWidth(el.clientWidth)
      const observer = new ResizeObserver(() => { setStageWidth(el.clientWidth) })
      observer.observe(el)
      return () => { observer.disconnect() }
      /* v8 ignore stop */
    }, [])
    // A horizontal swipe running off the stage's edge must not chain into the browser's history navigation
    // (overscroll.ts): the sheet's overscroll-behavior-x covers Chromium/Firefox, this covers WebKit.
    useEffect(() => {
      const el = stageRef.current
      /* v8 ignore next 1 -- the stage renders whenever the card does, and React
         attaches refs before effects run; el is never null here. */
      if (el === null) return
      return containHorizontalOverscroll(el)
    }, [])

    const built = useMemo(() => {
      const forest = agentForestOf(props.agents, sessionId, props.self)
      return forest !== null ? { forest, layout: layoutForest(forest, stageWidth) } : null
    }, [props.agents, sessionId, props.self, stageWidth])

    if (built === null) return null
    const { forest, layout } = built
    const byId = new Map(forest.nodes.map(n => [n.id, n]))
    /* v8 ignore next 1 -- agentForestOf anchors the forest at the current
       session, so a current node always exists. */
    const current = forest.nodes.find(n => n.isCurrent) ?? forest.nodes[0]
    const inspected = (hoverId !== null ? byId.get(hoverId) : undefined) ?? current
    const runningCount = forest.nodes.filter(n => n.running).length
    let totalTokens = 0
    for (const n of forest.nodes) totalTokens += n.head !== null ? n.head.tokens : 0

    // No navigation: our subagents are not sessions. Click/Enter pins the
    // inspector on the node instead (hover already does on pointer devices).
    const open = (id: string): void => { setHoverId(id) }
    const keyOpen = (id: string) => (ev: KeyboardEvent) => {
      if (ev.key !== 'Enter' && ev.key !== ' ') return
      ev.preventDefault()
      open(id)
    }

    return (
      <div className="lc-card lc-agents">
        <div className="lc-card-title">
          <span className="lc-card-title-text">{t('agents.title')}</span>
          <span className="lc-card-sub">{t('agents.sub')}</span>
        </div>

        <div className="lc-agents-chips">
          <span className="lc-agents-chip">{t('agents.chip.count', { n: forest.nodes.length + forest.overflow })}</span>
          <span className={'lc-agents-chip' + (runningCount > 0 ? ' lc-agents-chip-on' : '')}>
            {t('agents.chip.running', { n: runningCount })}
          </span>
          {totalTokens > 0
            ? <span className="lc-agents-chip">{t('agents.chip.tokens', { n: fmt(totalTokens) })}</span>
            : null}
          {forest.overflow > 0
            ? <span className="lc-agents-chip">{t('agents.more', { n: forest.overflow })}</span>
            : null}
        </div>

        <div className="lc-agents-stage" ref={stageRef}>
          <svg
            className="lc-agents-svg"
            width={layout.width}
            height={layout.height}
            viewBox={`0 0 ${layout.width} ${layout.height}`}
          >
            {layout.links.map((link) => {
              // Direct segment per link, colored by the child's family; a
              // running child layers a flowing pulse of the same hue on top.
              const d = `M ${link.x1} ${link.y1} L ${link.x2} ${link.y2}`
              return (
                <g key={link.to}>
                  <path
                    className={'lc-agents-link stroke-[1.5px] stroke-opacity-45' + (link.running ? ' lc-agents-link-live' : '')}
                    d={d}
                    stroke={link.color}
                    fill="none"
                  />
                  {link.running ? <path className="lc-agents-flow animate-lc-agent-flow fill-none stroke-2" d={d} stroke={link.color} /> : null}
                </g>
              )
            })}
            {forest.nodes.map((node) => {
              const point = layout.points.find(p => p.id === node.id)
              /* v8 ignore next 2 -- layoutForest positions every forest node,
                 so the lookup never misses. */
              if (point === undefined) return null
              return (
                <AgentNodeView
                  key={node.id}
                  node={node}
                  x={point.x}
                  y={point.y}
                  captionW={layout.captionW}
                  hovered={hoverId === node.id}
                  onHover={setHoverId}
                  onOpen={open}
                  onKeyOpen={keyOpen(node.id)}
                  t={t}
                  fmt={fmt}
                />
              )
            })}
          </svg>
        </div>

        {forest.solo ? <div className="lc-empty lc-agents-solo">{t('agents.solo')}</div> : null}

        <Inspector node={inspected} t={t} fmt={fmt} />
        <div className="lc-agents-legend">
          {CATS.map(c => (
            <span key={c.key} className="lc-agents-legend-item">
              <i style={{ background: c.color }} />
              {catLabel(c.key)}
            </span>
          ))}
          <span className="lc-agents-legend-item">
            <i className="lc-agents-legend-free" />
            {t('agents.legend.free')}
          </span>
          <span className="lc-agents-legend-item">
            <i className="lc-agents-legend-edge" />
            {t('agents.running')}
          </span>
        </div>
      </div>
    )
  }

  return AgentGraph
}

interface NodeViewProps {
  node: AgentNode
  x: number
  y: number
  /** Label box width from the responsive layout (narrows as slots compress). */
  captionW: number
  hovered: boolean
  onHover: (id: string | null) => void
  onOpen: (id: string) => void
  onKeyOpen: (ev: KeyboardEvent) => void
  t: ViewKit['t']
  fmt: ViewKit['fmt']
}

function AgentNodeView(props: NodeViewProps): ReactElement {
  const { node, x, y, captionW } = props
  const pct = node.head !== null ? node.head.pct : null
  const ring = 2 * Math.PI * AGENT_RING_R
  const segs = node.head !== null ? ringSegments(node.head.parts, pct, AGENT_RING_R, ringColorOf(pct)) : []
  const cls = 'lc-agent-node'
    + (node.isCurrent ? ' lc-agent-self' : '')
    + (node.running ? ' lc-agent-running' : '')
    + (node.completed && !node.running ? ' lc-agent-done' : '')
    + (props.hovered ? ' lc-agent-hover' : '')
    + (node.isCurrent ? '' : ' lc-agent-clickable')
    // The halo's hover/focus wash rides group variants on the node (the React
    // hover state only drives the inspector; lc-agent-hover stays as a test anchor).
    + ' group/agent'
  return (
    <g
      className={cls}
      transform={`translate(${x}, ${y})`}
      data-agent={node.id}
      role={node.isCurrent ? 'img' : 'button'}
      tabIndex={node.isCurrent ? undefined : 0}
      onClick={() => { props.onOpen(node.id) }}
      onKeyDown={props.onKeyOpen}
      onMouseEnter={() => { props.onHover(node.id) }}
      onMouseLeave={() => { props.onHover(null) }}
    >
      {/* Halo carries the state: wash for self, breathing green while running, faint green for done. */}
      <circle
        className={'lc-agent-halo fill-transparent group-hover/agent:fill-[var(--dsw-alias-interactive-bg-hover,var(--dsw-alias-bg-layer-2))] group-focus-visible/agent:fill-[var(--dsw-alias-interactive-bg-hover,var(--dsw-alias-bg-layer-2))]'
          + (node.running ? ' animate-lc-agent-glow' : '')}
        r={AGENT_NODE_R + 9}
      />
      <circle
        className="lc-agent-track fill-(--dsw-alias-bg-layer-1) stroke-(--dsw-alias-border-l1) stroke-[1.5px]"
        r={AGENT_NODE_R}
      />
      {segs.map(seg => (
        <circle
          key={seg.key}
          className={'lc-agent-seg fill-none stroke-9' + (seg.free ? ' lc-agent-free' : '')}
          r={AGENT_RING_R}
          strokeDasharray={`${seg.len} ${ring - seg.len}`}
          strokeDashoffset={-seg.offset}
          // Inline style, not the stroke attribute: segment colors are CSS variables
          // (var() is unusable in a presentation attribute). Free segments carry no
          // inline stroke so the .lc-agent-free class rule keeps painting the remainder.
          style={{ stroke: seg.free ? undefined : seg.color }}
          transform="rotate(-90)"
        />
      ))}
      <text className="lc-agent-pct fill-(--dsw-alias-label-primary)" textAnchor="middle" dy="0.32em">
        {pct !== null ? `${pct}%` : (node.head !== null ? props.fmt(node.head.tokens) : '—')}
      </text>
      {/* HTML caption (foreignObject): the full label wraps instead of truncating;
          the current agent is marked in text, keeping every node's ring semantics identical. */}
      <foreignObject x={-captionW / 2} y={AGENT_NODE_R + 8} width={captionW} height={CAPTION_H}>
        <div className="lc-agent-caption">
          <div className="lc-agent-label">
            {node.label}
            {node.isCurrent ? <span className="lc-agents-badge lc-agent-self-badge">{props.t('agents.self')}</span> : null}
          </div>
          <div className="lc-agent-tokens">{node.head !== null ? props.fmt(node.head.tokens) : '—'}</div>
        </div>
      </foreignObject>
    </g>
  )
}

/** The detail strip mirroring the hovered (or current) node: identity, occupancy, activity, and the open hint. */
function Inspector(props: { node: AgentNode; t: ViewKit['t']; fmt: ViewKit['fmt'] }): ReactElement {
  const { node, t, fmt } = props
  const bits: string[] = []
  if (node.head !== null) {
    const head = node.head
    const window = head.window !== undefined ? ` / ${fmt(head.window)}` : ''
    const pct = head.pct !== null ? ` · ${head.pct}%` : ''
    bits.push(`${fmt(head.tokens)}${window}${pct}`)
  }
  if (node.requests > 0) bits.push(t('agents.requests', { n: node.requests }))
  if (node.billed !== null && node.billed > 0) bits.push(t('agents.billed', { n: fmt(node.billed) }))
  if (node.durationMs !== null) bits.push(fmtDurationCompact(node.durationMs))
  return (
    <div className="lc-agents-inspector">
      <b className="lc-agents-inspector-name">{node.label}</b>
      {node.isCurrent ? <span className="lc-agents-badge">{t('agents.self')}</span> : null}
      {node.running ? <span className="lc-agents-badge lc-agents-badge-on">{t('agents.running')}</span> : null}
      {node.identity !== null
        ? <span className="lc-agents-badge">{t(node.identity.mode === 'one-shot' ? 'agents.oneshot' : 'agents.continuable')}</span>
        : null}
      <span className="lc-agents-inspector-stats">{bits.join(' · ')}</span>
    </div>
  )
}

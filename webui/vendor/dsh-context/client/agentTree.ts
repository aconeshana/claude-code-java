/**
 * The Agent network card's data + layout (vendored from dsh-context
 * `client/agentTree.ts`). claude-code-java rewrite of the data half: our
 * subagents are `Task`/`Agent` tool calls INSIDE the session (never separate
 * sessions), so the forest is one root (the session itself) plus one leaf
 * per {@link AgentRecord} the detail payload carries — no `parentId` walk,
 * no session-list snapshot, no navigation. The layout half (tidy tree,
 * family hues, fused composition rings) is upstream verbatim.
 */

import type { PartsPart } from './categories'
import { CAT_COLOR } from './categories'
import type { AgentRecord, SessionCostUsage, TokenUsage } from '../shared/types'
import { mergeCostUsage } from './cost'
import type { Headline } from './headline'

/** The subagent's descriptor identity (our `subagent_type` and the model it ran on). */
export interface AgentIdentity {
  mode: 'one-shot' | 'continuable'
  label?: string | undefined
}

/** Per-node context stats. */
export interface AgentStats {
  /** Occupancy + composition parts (null = nothing known about this agent's context). */
  head: Headline | null
  /** Retained request records of the session's timeline (root) / tool uses (subagent). */
  requests: number
  /** Total billed tokens (null = no usage reported yet). */
  billed: number | null
  /** Run duration, null while unknown. */
  durationMs: number | null
  /** Descriptor identity of a subagent (null for the root agent). */
  identity: AgentIdentity | null
}

/** Live stats of the CURRENT session, fed by the tab's own projections (fresher than any list row). */
export interface AgentSelfStats {
  head: Headline | null
  billed: number | null
  requests: number
}

/** One render-ready tree node. */
export interface AgentNode extends AgentStats {
  id: string
  label: string
  parentId?: string | undefined
  /** Layout column (root = 0), assigned during the DFS. */
  depth: number
  /** Which level-1 subtree this node belongs to (root = -1) — drives the family link hue. */
  family: number
  isCurrent: boolean
  running: boolean
  completed: boolean
  subagent: boolean
}

export interface AgentForest {
  /** DFS pre-order (parents ahead of their children). */
  nodes: AgentNode[]
  edges: { from: string; to: string }[]
  /** Subtree members dropped by AGENT_TREE_LIMIT. */
  overflow: number
  /** True when the current agent stands alone (no relatives visible). */
  solo: boolean
}

/** The card stays readable up to this many nodes; the rest folds into an overflow note. */
export const AGENT_TREE_LIMIT = 25

/** Donut geometry: node disc radius and the fused composition/occupancy ring radius (SVG units). */
export const AGENT_NODE_R = 26
export const AGENT_RING_R = 20
/* Horizontal cell pitch adapts to the stage width between these bounds; each
   node owns one cell, so the caption box (cell minus an 8px gutter) can never
   clip at a neighbor or the stage edge. */
const SLOT_MAX = 184
const SLOT_MIN = 112
const CAPTION_GUTTER = 8
/* Vertical pitch between depth levels: node radius + caption zone (up to 3
   wrapped lines + the tokens line) + a dedicated 28px link channel below it. */
const LEVEL_H = 154
/* Bottom edge of a node cell — links exit here, below the caption zone, so a
   connector never crosses a label. */
const CELL_H = AGENT_NODE_R + 64
const PAD_Y = 56


/**
 * One subagent's composition ring, estimated from its reported usage: the
 * prompt side (uncached + cache read + cache write) draws as the `tool`
 * bucket the parent's context sees it through is NOT what ran inside — so
 * the ring splits the subagent's OWN billed tokens into input vs output
 * (`user`/`assistant` hues). Null without usage.
 */
function agentHeadOf(record: AgentRecord): Headline | null {
  const usage = record.usage
  if (usage === undefined) {
    return record.tokens !== undefined && record.tokens > 0
      ? { tokens: record.tokens, pct: null, parts: [] }
      : null
  }
  const input = usage.uncachedInputTokens + usage.cacheReadTokens + usage.cacheWriteTokens
  const output = usage.outputTokens
  const parts: PartsPart[] = []
  if (input > 0) parts.push({ key: 'user', color: CAT_COLOR.user, value: input })
  if (output > 0) parts.push({ key: 'assistant', color: CAT_COLOR.assistant, value: output })
  return { tokens: record.tokens ?? input + output, pct: null, parts }
}

function billedOf(usage: TokenUsage | undefined): number | null {
  if (usage === undefined) return null
  return usage.uncachedInputTokens + usage.outputTokens + usage.cacheReadTokens + usage.cacheWriteTokens
}

/**
 * Build the session's agent family: the session itself as the root, one
 * child per subagent call (running first, then newest, id as the stable
 * tiebreak), capped at AGENT_TREE_LIMIT with an exact overflow count. Null
 * without a current session id.
 */
export function agentForestOf(
  agents: readonly AgentRecord[],
  currentId: string | undefined,
  self?: AgentSelfStats | undefined,
): AgentForest | null {
  if (currentId === undefined || currentId === '') return null
  const root: AgentNode = {
    id: currentId,
    label: currentId.length > 12 ? currentId.slice(0, 8) : currentId,
    depth: 0,
    family: -1,
    isCurrent: true,
    running: false,
    completed: false,
    subagent: false,
    head: self?.head ?? null,
    requests: self?.requests ?? 0,
    billed: self?.billed ?? null,
    durationMs: null,
    identity: null,
  }
  const sorted = [...agents].sort((a, b) => {
    const runDelta = Number(b.status === 'running') - Number(a.status === 'running')
    if (runDelta !== 0) return runDelta
    const timeDelta = (b.time ?? b.seq) - (a.time ?? a.seq)
    return timeDelta !== 0 ? timeDelta : (a.id < b.id ? -1 : 1)
  })
  const nodes: AgentNode[] = [root]
  const edges: { from: string; to: string }[] = []
  sorted.forEach((record, index) => {
    if (nodes.length >= AGENT_TREE_LIMIT) return
    const label = record.description ?? record.type ?? record.agentId ?? record.id
    nodes.push({
      id: record.id,
      label,
      parentId: currentId,
      depth: 1,
      family: index,
      isCurrent: false,
      running: record.status === 'running',
      completed: record.status !== 'running',
      subagent: true,
      head: agentHeadOf(record),
      requests: record.toolUses ?? 0,
      billed: billedOf(record.usage) ?? record.tokens ?? null,
      durationMs: record.durationMs ?? null,
      identity: { mode: 'one-shot', ...(record.type !== undefined ? { label: record.type } : {}) },
    })
    edges.push({ from: currentId, to: record.id })
  })
  return { nodes, edges, overflow: Math.max(0, agents.length + 1 - nodes.length), solo: nodes.length === 1 }
}

/**
 * The session's subagent cost fold: every subagent's reported usage merged
 * into one `SessionCostUsage` under its model (the stats board's subagent
 * cost cell). Null when no subagent reported usage.
 */
export function subagentCostOf(agents: readonly AgentRecord[]): SessionCostUsage | null {
  const parts: SessionCostUsage[] = []
  for (const record of agents) {
    if (record.usage === undefined) continue
    parts.push({
      '': {
        [record.model ?? '']: {
          peak: {
            uncached: record.usage.uncachedInputTokens,
            cacheRead: record.usage.cacheReadTokens,
            cacheWrite: record.usage.cacheWriteTokens,
            output: record.usage.outputTokens,
          },
        },
      },
    })
  }
  return parts.length > 0 ? mergeCostUsage(...parts) : null
}

export interface AgentPoint {
  id: string
  x: number
  y: number
  depth: number
}

export interface AgentLink {
  to: string
  running: boolean
  /** Family hue of the child's level-1 subtree — parents are told apart by color. */
  color: string
  x1: number
  y1: number
  x2: number
  y2: number
}

export interface AgentLayout {
  width: number
  height: number
  /** Caption box width for this layout — follows the resolved slot pitch. */
  captionW: number
  points: AgentPoint[]
  links: AgentLink[]
}

/**
 * Tidy top-down tree layout: one row per depth level, siblings claim leaf
 * slots, parents center over their children. Fully responsive to the stage's
 * visible width: the slot pitch stretches up to SLOT_MAX and compresses down
 * to SLOT_MIN (captions wrap tighter); a level that still overflows wraps
 * into bands of at most a per-level node count derived from the stage width —
 * vertical room is cheaper than horizontal scrolling. Links exit a parent at
 * its cell bottom (below the caption zone) and enter the child at its top,
 * so a connector never crosses a label.
 */
export function layoutForest(forest: AgentForest, stageWidth = 0): AgentLayout {
  // Children lists in DFS order (nodes are DFS pre-order, so plain iteration appends in visit order).
  const childrenOf = new Map<string, AgentNode[]>()
  for (const n of forest.nodes) {
    if (n.parentId === undefined) continue
    const kids = childrenOf.get(n.parentId) ?? []
    kids.push(n)
    childrenOf.set(n.parentId, kids)
  }

  // Tidy x: leaves claim successive slots; internal nodes center over their children.
  const slotOf = new Map<string, number>()
  let leafSlots = 0
  const place = (node: AgentNode): number => {
    const kids = childrenOf.get(node.id) ?? []
    if (kids.length === 0) {
      const slot = leafSlots
      leafSlots++
      slotOf.set(node.id, slot)
      return slot
    }
    let first = 0
    let last = 0
    kids.forEach((kid, index) => {
      const slot = place(kid)
      if (index === 0) first = slot
      last = slot
    })
    const slot = (first + last) / 2
    slotOf.set(node.id, slot)
    return slot
  }
  /* v8 ignore next 1 -- a forest always holds at least the (possibly
     synthesized) current node. */
  if (forest.nodes.length > 0) place(forest.nodes[0])

  // Cell model: the layout is exactly `leafSlots` cells wide. While the cells
  // fit the stage at the minimum pitch, the pitch simply adapts; beyond that,
  // levels wrap into bands of `perLevel` cells — vertical room is cheaper
  // than horizontal scrolling, and the stage never overflows.
  const perLevel = stageWidth > 0 ? Math.max(2, Math.floor(stageWidth / SLOT_MIN)) : 0

  if (perLevel > 0 && leafSlots > perLevel) {
    // Wrapped layout: bands of at most perLevel cells, interleaved by kinship
    // — after each parent band come the bands of exactly those parents'
    // children (sibling groups never split unless one group alone exceeds the
    // band). DFS order is preserved at every level, so trunks from parents to
    // child bands match monotonically and never cross.
    const bandSlot = Math.min(SLOT_MAX, stageWidth / perLevel)
    const width = perLevel * bandSlot
    const points: AgentPoint[] = []
    let row = 0
    const emitBand = (nodes: AgentNode[], depth: number): void => {
      // A short (last) band centers its cells instead of hugging the left edge.
      const inset = (width - nodes.length * bandSlot) / 2
      nodes.forEach((node, i) => {
        points.push({ id: node.id, x: inset + (i + 0.5) * bandSlot, y: PAD_Y + row * LEVEL_H, depth })
      })
      row++
      let band: AgentNode[] = []
      const flush = (): void => {
        if (band.length === 0) return
        const packed = band
        band = []
        emitBand(packed, depth + 1)
      }
      for (const node of nodes) {
        const kids = childrenOf.get(node.id) ?? []
        for (let start = 0; start < kids.length; start += perLevel) {
          const group = kids.slice(start, start + perLevel)
          if (band.length + group.length > perLevel) flush()
          band.push(...group)
          if (band.length === perLevel) flush()
        }
      }
      flush()
    }
    /* v8 ignore next 1 -- a forest always holds at least the current node. */
    if (forest.nodes.length > 0) emitBand([forest.nodes[0]], 0)
    return {
      width,
      height: PAD_Y + (row - 1) * LEVEL_H + CELL_H + 28,
      captionW: bandSlot - CAPTION_GUTTER,
      points,
      links: linksOf(forest, points),
    }
  }

  // Tidy rows: adaptive pitch (0 = unmeasured stage → the natural maximum).
  const slot = stageWidth > 0 && leafSlots > 1 ? Math.min(SLOT_MAX, stageWidth / leafSlots) : SLOT_MAX
  const points: AgentPoint[] = forest.nodes.map(node => ({
    id: node.id,
    /* v8 ignore next 1 -- place() visits every node: the forest is exactly
       the root's subtree by construction. */
    x: (slotOf.get(node.id) ?? 0) * slot + slot / 2,
    y: PAD_Y + node.depth * LEVEL_H,
    depth: node.depth,
  }))
  const maxDepth = points.reduce((max, p) => Math.max(max, p.depth), 0)
  return {
    width: leafSlots * slot,
    // The deepest level still carries its full caption cell below the node.
    height: PAD_Y + maxDepth * LEVEL_H + CELL_H + 28,
    captionW: slot - CAPTION_GUTTER,
    points,
    links: linksOf(forest, points),
  }
}

/**
 * Family hue by level-1 subtree index: the golden angle keeps consecutive
 * families maximally separated on the color wheel without a hand-tuned palette.
 */
export function familyHue(index: number): string {
  return `hsl(${Math.round(index * 137.508) % 360} 58% 52%)`
}

/** Parent→child links: exit the parent's cell bottom, enter the child's top. */
function linksOf(forest: AgentForest, points: AgentPoint[]): AgentLink[] {
  const pointOf = new Map(points.map(p => [p.id, p]))
  const nodeOf = new Map(forest.nodes.map(n => [n.id, n]))
  const runningIds = new Set(forest.nodes.filter(n => n.running).map(n => n.id))
  const links: AgentLink[] = []
  for (const edge of forest.edges) {
    const from = pointOf.get(edge.from)
    const to = pointOf.get(edge.to)
    /* v8 ignore next 2 -- edges are emitted only for visited parent/child
       pairs, so both points always exist. */
    if (from === undefined || to === undefined) continue
    links.push({
      to: edge.to,
      running: runningIds.has(edge.to),
      /* v8 ignore next 1 -- edges only connect visited nodes. */
      color: familyHue(nodeOf.get(edge.to)?.family ?? 0),
      x1: from.x,
      y1: from.y + CELL_H,
      x2: to.x,
      y2: to.y - AGENT_NODE_R - 10,
    })
  }
  return links
}

export interface RingSeg {
  key: string
  /** Segment color; unused for the free remainder (styled by its CSS class). */
  color: string
  /** Arc length along the circle's circumference. */
  len: number
  /** Arc start, as a (negative) stroke dash offset. */
  offset: number
  /** True for the unoccupied-window remainder. */
  free: boolean
}

/**
 * One fused ring per agent — the exact semantics of the chat composer's own
 * context ring: the composition parts, scaled to the occupancy share of the
 * window, fill the circle, and a neutral remainder marks the free window.
 * With no known window the composition fills the whole circle; with no
 * composition (pressure-only rows) a single threshold-colored arc carries
 * the occupancy; a known window with zero occupancy draws the free outline.
 */
export function ringSegments(parts: PartsPart[], pct: number | null, radius: number, fallbackColor: string): RingSeg[] {
  const circumference = 2 * Math.PI * radius
  const occ = pct === null ? 1 : Math.min(100, Math.max(0, pct)) / 100
  let total = 0
  for (const p of parts) total += p.value > 0 ? p.value : 0
  const segs: RingSeg[] = []
  let offset = 0
  if (total > 0) {
    for (const p of parts) {
      if (p.value <= 0) continue
      const len = circumference * (p.value / total) * occ
      if (len <= 0) continue
      segs.push({ key: p.key, color: p.color, len, offset, free: false })
      offset += len
    }
  } else if (pct !== null && occ > 0) {
    // Pressure-only node: a solid occupancy arc in the threshold color.
    segs.push({ key: 'fill', color: fallbackColor, len: circumference * occ, offset: 0, free: false })
    offset = circumference * occ
  }
  if (pct !== null && offset < circumference) {
    segs.push({ key: 'free', color: '', len: circumference - offset, offset, free: true })
  }
  return segs
}

/**
 * Compact duration: `42s`, `3m05s`, `1h07m` (shared by both locales).
 * Deliberately distinct from format.ts's `fmtDuration` (the timing card's
 * `12.3s` / `3m25s`): the inspector's caption column needs whole-second,
 * fixed-width text.
 */
export function fmtDurationCompact(ms: number): string {
  if (!Number.isFinite(ms) || ms < 0) return '—'
  const s = Math.round(ms / 1000)
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m}m${String(s % 60).padStart(2, '0')}s`
  return `${Math.floor(m / 60)}h${String(m % 60).padStart(2, '0')}m`
}

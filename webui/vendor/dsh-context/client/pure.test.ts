/**
 * Pins the vendored dsh-context pure helpers the claude-code-java views lean
 * on: the per-step context assembly, the anchored composition split, the
 * file-activity fold, the agent family, and the overview row pipeline over
 * the gateway's `/overview` payload.
 */
import { describe, expect, it } from 'vitest'
import { agentForestOf, subagentCostOf } from './agentTree'
import { assemble } from './assemble'
import { anchoredParts } from './categories'
import { activityOfOps } from './fileActivity'
import { aggregateDays, filterRows, kpisOf, rowsOfOverview, sortRows } from './overview'
import type { AgentRecord, ContextTimeline, FileOpRecord, SurfaceNode } from '../shared/types'

function node(seq: number, cat: SurfaceNode['cat'], tokens: number, gone?: number): SurfaceNode {
  return { seq, cat, tokens, ...(gone !== undefined ? { gone } : {}) }
}

const TIMELINE: ContextTimeline = {
  ok: true,
  model: 'claude-sonnet-4-5',
  provider: 'anthropic',
  current: { system: 100, tools: 200, user: 30, inject: 0, skill: 0, assistant: 40, tool: 50, total: 420 },
  counts: { turns: 2, steps: 3, injects: 0, compactions: 1, prunes: 0 },
  detailRev: 3,
  requests: [],
  events: [],
  nodes: [node(1, 'user', 10), node(6, 'assistant', 40), node(9, 'user', 20)],
  droppedNodes: 0,
  archive: [node(2, 'assistant', 500, 5), node(3, 'tool', 700, 5)],
}

describe('assemble', () => {
  it('live assembly is the surface; a past step re-includes archived nodes it still saw', () => {
    expect(assemble(TIMELINE, null, null).nodes.map((n) => n.seq)).toEqual([1, 6, 9])
    // Step 4 (before the compaction at 5) saw seqs 1, 2, 3.
    expect(assemble(TIMELINE, null, 4).nodes.map((n) => n.seq)).toEqual([1, 2, 3])
    // Step 7 (after the boundary) sees only the live nodes below it.
    expect(assemble(TIMELINE, null, 7).nodes.map((n) => n.seq)).toEqual([1, 6])
  })
})

describe('anchoredParts', () => {
  it('scales estimated parts onto the provider-reported total and keeps raw', () => {
    const parts = anchoredParts([
      { key: 'user', color: '#a', value: 30 },
      { key: 'assistant', color: '#b', value: 70 },
    ], 1_000)
    expect(parts.map((p) => p.value)).toEqual([300, 700])
    expect(parts.map((p) => p.raw)).toEqual([30, 70])
    expect(anchoredParts([{ key: 'user', color: '#a', value: 30 }], null)[0].value).toBe(30)
  })
})

describe('activityOfOps', () => {
  it('folds file ops into per-path entries with kind totals and archive-joined gone', () => {
    const ops: FileOpRecord[] = [
      { seq: 3, path: 'src/a.ts', kind: 'read', tool: 'Read', err: false, added: 0, removed: 0 },
      { seq: 4, path: 'src/a.ts', kind: 'write', tool: 'Edit', err: false, added: 5, removed: 2 },
      { seq: 8, path: 'src/b.ts', kind: 'read', tool: 'Read', err: true, added: 0, removed: 0 },
    ]
    const activity = activityOfOps(ops, [node(3, 'tool', 10, 6)], null)
    expect(activity.entries.map((e) => e.path)).toEqual(['src/b.ts', 'src/a.ts'])
    const a = activity.entries.find((e) => e.path === 'src/a.ts')!
    expect(a).toMatchObject({ reads: 1, writes: 1, added: 5, removed: 2, errs: 0 })
    expect(a.ops.find((op) => op.seq === 3)?.gone).toBe(6)
    expect(activity.totals.added).toBe(5)
    expect(activity.totals.removed).toBe(2)
    expect(activity.entries.find((e) => e.path === 'src/b.ts')?.errs).toBe(1)
  })
})

describe('agent family over detail.agents', () => {
  const agents: AgentRecord[] = [
    { id: 'toolu_1', seq: 4, time: 1_000, type: 'Explore', description: 'find files', status: 'done', tokens: 900,
      model: 'claude-haiku-4-5', durationMs: 4_000,
      usage: { uncachedInputTokens: 500, outputTokens: 100, cacheReadTokens: 300, cacheWriteTokens: 0 } },
    { id: 'toolu_2', seq: 9, time: 2_000, type: 'general-purpose', status: 'running' },
  ]

  it('roots the session and lists subagents running-first', () => {
    const forest = agentForestOf(agents, 'session-1234567890abc')!
    expect(forest.nodes.map((n) => n.id)).toEqual(['session-1234567890abc', 'toolu_2', 'toolu_1'])
    expect(forest.nodes[0]).toMatchObject({ isCurrent: true, depth: 0 })
    expect(forest.nodes[1]).toMatchObject({ running: true, subagent: true, label: 'general-purpose' })
    expect(forest.nodes[2]).toMatchObject({ completed: true, label: 'find files', billed: 900, durationMs: 4_000 })
    expect(forest.edges).toHaveLength(2)
    expect(agentForestOf(agents, undefined)).toBeNull()
  })

  it('subagentCostOf merges reported usage under each model', () => {
    const cost = subagentCostOf(agents)!
    expect(cost['']['claude-haiku-4-5'].peak).toEqual({ uncached: 500, cacheRead: 300, cacheWrite: 0, output: 100 })
    expect(subagentCostOf([agents[1]])).toBeNull()
  })
})

describe('overview rows over GET /api/session/context/overview', () => {
  const payload = {
    time: 10_000,
    sessions: [
      { id: 'a', title: 'Fix build', cwd: '/w/alpha', running: true, updatedAt: 9_000,
        timeline: { ...TIMELINE, cost: { '': { 'claude-sonnet-4-5': { peak: { uncached: 1_000, cacheRead: 4_000, cacheWrite: 0, output: 500 } } } } },
        activity: { days: { '2026-09-16': { tokens: 5_500, requests: 3 } } } },
      { id: 'b', cwd: '/w/beta/', running: false, updatedAt: 1_000, timeline: null, activity: null },
      { id: '', title: 'hostile' },
      'garbage',
    ],
  }

  it('re-proves every row, titles by project basename, marks the current one', () => {
    const rows = rowsOfOverview(payload, 'b')!
    expect(rows.map((r) => r.id)).toEqual(['a', 'b'])
    expect(rows[0]).toMatchObject({ title: 'Fix build', running: true, current: false })
    expect(rows[1]).toMatchObject({ title: 'beta', current: true, timeline: null, activity: null })
    expect(rowsOfOverview({ nope: true }, null)).toBeNull()
  })

  it('filters by range/day/query, sorts, and aggregates KPIs and days', () => {
    const rows = rowsOfOverview(payload, null)!
    const now = 10_000
    expect(filterRows(rows, { range: 'all', day: '2026-09-16', query: '' }, now).map((r) => r.id)).toEqual(['a'])
    expect(filterRows(rows, { range: 'all', day: null, query: 'beta' }, now).map((r) => r.id)).toEqual(['b'])
    expect(sortRows(rows, 'context').map((r) => r.id)).toEqual(['a', 'b'])
    const kpi = kpisOf(rows, rows.length, null, 'usd')
    expect(kpi.sessions).toBe(2)
    expect(kpi.tokens).toBe(5_500)
    expect(kpi.turns).toBe(2)
    expect(Number(kpi.cacheHit)).toBe(80)
    expect(aggregateDays(rows)['2026-09-16']).toMatchObject({ tokens: 5_500, requests: 3 })
  })
})

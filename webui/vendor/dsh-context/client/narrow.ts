/**
 * Boundary narrowers for the dsh-context wire values (vendored from
 * dsh-context `client/services.ts`, cordis-free).
 *
 * Upstream `services.ts` also declared the harness faces (`ClientCtx`,
 * slots, RPC openers, history page). Those are cut here: the Java gateway
 * serves the same `shared/types.ts` shapes over plain REST
 * (`/api/session/context/{timeline,detail,content,overview}`), so only the
 * type-only node/image faces and the `*Of` sanitizers — the no-white-screen
 * guarantee every card rides — survive.
 */

import { estimateSystemTokens } from '../shared/estimate'
import type { ActivityDay, ContextActivity, ContextBreakdown, ContextHeaders, ContextPressure, ContextTimeline, CostModelUsage, HeaderEpochContent, SystemPromptNode, TimingTotals, TokenUsage, ToolTimingTotals } from '../shared/types'
/**
 * The conversation node, as far as the Context browser consumes it: the
 * framework's finalized chat nodes carry the source surface event's `seq`
 * plus the full content — the browser joins its surface nodes on `seq` to
 * show actual content without carrying it through the projection.
 */
export interface ConversationNodeLike {
  kind: string
  seq: number
  /**
   * The durable message id (assistant nodes on the harness chat nodes; absent
   * on synthetic/interrupted replies) — the key the chat's assistant-action
   * seat addresses a finalized reply by.
   */
  messageId?: unknown | undefined
  content?: readonly unknown[] | undefined
  blocks?: readonly unknown[] | undefined
  call?: { name: string; argsRaw: string } | null | undefined
  isError?: boolean | undefined
  summary?: string | null | undefined
  /**
   * Nested Code-Mode call tree (dsh's recursive ToolCallBlock[]) on a tool
   * result whose call ran sub-dispatches — a PTC `run_code` program. Consumed
   * structurally only (fileActivity): every block is re-proved at runtime and
   * malformed shapes drop out instead of throwing.
   */
  subCalls?: readonly unknown[] | undefined
  /** The tool result's bounded presentation meta (a search's matched files), as the join delivers it. */
  meta?: unknown | undefined
}

/**
 * A durable image attachment reference, as far as this plugin consumes it
 * (dsh's `ImageAttachmentRef`, minimally re-typed so the plugin stays free
 * of an attachment-package dependency). The durable log holds only this ref
 * — never inline bytes. Since dsh 0.1.2-rc.1 the width/height/bytes describe
 * the NORMALIZED raster under a deployment-resolvable policy (defaults:
 * total-pixel budget 2048×2048, long edge capped at 8192px — the 0.1.1 line
 * capped the long edge at 2048px); `originalDimensions` carries the
 * pre-normalization size when normalization reduced the image.
 */
export interface ImageRefLike {
  attachmentId: string
  name?: string | undefined
  bytes?: number | undefined
  width?: number | undefined
  height?: number | undefined
  originalDimensions?: { width: number; height: number } | undefined
}

/** Loads a session-authorized display URL for one durable image reference. */
export type ImageLoader = (attachment: ImageRefLike) => Promise<string>
/**
 * Narrow an unknown projection value to a string-keyed record, or null when
 * it is not one. The boundary type is Record<string, unknown> on purpose:
 * every field read below must re-prove itself (the no-white-screen
 * guarantee), so no field may borrow the wire type before its check.
 * Shared by every sanitizer here and by the agent-tree derivation
 * (agentTree.ts) — the ONE record guard for the whole client half.
 */
export function asRecord(value: unknown): Record<string, unknown> | null {
  if (value === null || value === undefined || typeof value !== 'object') return null
  return value as Record<string, unknown>
}

/**
 * Safe finite-number read: a missing/non-numeric/NaN field degrades to 0
 * instead of leaking into the UI as NaN percentages or broken arithmetic.
 */
export function numOf(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0
}

/** Shared per-item collection guard: drop non-object entries, keep the rest. */
export function objectsOf<T>(value: unknown): T[] {
  if (!Array.isArray(value)) return []
  return value.filter((v): v is T => v !== null && typeof v === 'object')
}

/**
 * The fast path's collection check: a real array whose entries are ALL
 * records. A null/primitive entry would pass a bare Array.isArray yet throw
 * on the first property read downstream (`req.seq` on null), so it sends the
 * value down the sanitizing slow path, where `objectsOf` drops it.
 */
function recordsOnly(value: unknown): boolean {
  return Array.isArray(value) && value.every(e => e !== null && typeof e === 'object')
}

/**
 * Narrow a delivered `unsupported` gate record (the host's baseline gate —
 * see host/fallback.ts): both version strings re-proved, anything else
 * degrades to null (no gate shown) instead of rendering garbage.
 */
export function unsupportedOf(value: unknown): { current: string; minimum: string } | null {
  const data = asRecord(value)
  if (data === null) return null
  if (typeof data.current !== 'string' || typeof data.minimum !== 'string') return null
  return { current: data.current, minimum: data.minimum }
}
/**
 * The session-cost raw material, re-proved per provider/model/period/bucket
 * (the shape the client's cost.ts prices): a branch, model, or period that
 * is not a plain record drops whole — never a half-proved row — and bucket
 * fields zero out via numOf, so garbage can only price as zero, never as
 * NaN. Absent stays absent.
 */
function costOf(value: unknown): ContextTimeline['cost'] | undefined {
  const data = asRecord(value)
  if (data === null || Array.isArray(data)) return undefined
  const out: NonNullable<ContextTimeline['cost']> = {}
  for (const provider of Object.keys(data)) {
    const models = asRecord(data[provider])
    if (models === null || Array.isArray(models)) continue
    const branch: Record<string, CostModelUsage> = {}
    for (const model of Object.keys(models)) {
      const periods = asRecord(models[model])
      if (periods === null || Array.isArray(periods)) continue
      const copy: CostModelUsage = {}
      for (const period of ['peak', 'off'] as const) {
        const b = asRecord(periods[period])
        if (b === null || Array.isArray(b)) continue
        copy[period] = {
          uncached: numOf(b.uncached),
          cacheRead: numOf(b.cacheRead),
          cacheWrite: numOf(b.cacheWrite),
          output: numOf(b.output),
        }
      }
      branch[model] = copy
    }
    out[provider] = branch
  }
  return out
}

/** The fast-path structural check for `cost`: every branch is a plain record (bucket fields re-prove in costOf/cost.ts). */
function costFastOk(value: unknown): boolean {
  if (value === undefined) return true
  const data = asRecord(value)
  if (data === null || Array.isArray(data)) return false
  return Object.keys(data).every((provider) => {
    const models = asRecord(data[provider])
    return models !== null && !Array.isArray(models)
  })
}

/**
 * Narrow a delivered projection value to a RENDER-SAFE context timeline —
 * the client's no-white-screen guarantee against backend/parse failures.
 *
 * A value that is not a record at all (capability absent, nothing delivered
 * yet) stays `null` and callers show the loading screen. A record that fails
 * the wire shape (corrupt checkpoint restore, a failed/older host payload,
 * plugin drift) is SANITIZED instead of rejected: every collection becomes
 * an array, non-object entries are dropped, `current` becomes a numeric
 * breakdown, and wrong-typed scalars are dropped or zeroed — so the whole
 * tab still renders with every usable piece of data instead of throwing
 * during render and unmounting the conversation view.
 */
export function timelineOf(value: unknown): ContextTimeline | null {
  const data = asRecord(value)
  if (data === null) return null
  const current = data.current
  // The wire shape check for the cheap pass-through path: `current` must be
  // a full numeric breakdown (the host always sends all seven fields), and
  // every collection must be a real list. Anything else takes the slow path
  // and is rebuilt into the safe shape below.
  const numericBreakdown = current !== null && typeof current === 'object'
    && ['system', 'tools', 'user', 'inject', 'skill', 'assistant', 'tool', 'total']
      .every(k => typeof (current as Record<string, unknown>)[k] === 'number')
  if (numericBreakdown
    && recordsOnly(data.requests)
    && recordsOnly(data.events)
    && recordsOnly(data.nodes)
    && recordsOnly(data.archive)
    && systemsFastOk(data.systems)
    && timingFastOk(data.timing)
    && costFastOk(data.cost)) {
    // Well-formed: pass the delivered value through untouched (cheap, and reference-stable so plain re-renders stay zero-copy).
    return data as unknown as ContextTimeline
  }
  const safeCurrent: Record<string, unknown> = current !== null && typeof current === 'object' ? current as Record<string, unknown> : {}
  const cost = costOf(data.cost)
  const timing = timingOf(data.timing)
  // The baseline-gate record survives sanitizing: a fallback payload that
  // somehow fails the fast path must still pop the gate modal.
  const unsupported = unsupportedOf(data.unsupported)
  // The split-generation head fields survive sanitizing too.
  const counts = countsOf(data.counts)
  const last = lastOf(data.last)
  const safe: ContextTimeline = {
    ok: true,
    ...(unsupported !== null ? { unsupported } : {}),
    ...(typeof data.model === 'string' ? { model: data.model } : {}),
    ...(typeof data.provider === 'string' ? { provider: data.provider } : {}),
    ...(typeof data.contextWindow === 'number' ? { contextWindow: data.contextWindow } : {}),
    current: {
      system: numOf(safeCurrent.system),
      tools: numOf(safeCurrent.tools),
      user: numOf(safeCurrent.user),
      inject: numOf(safeCurrent.inject),
      skill: numOf(safeCurrent.skill),
      assistant: numOf(safeCurrent.assistant),
      tool: numOf(safeCurrent.tool),
      total: numOf(safeCurrent.total),
    },
    requests: objectsOf(data.requests),
    events: objectsOf(data.events),
    nodes: objectsOf(data.nodes),
    droppedNodes: numOf(data.droppedNodes),
    ...(typeof data.images === 'number' ? { images: data.images } : {}),
    ...(typeof data.toolCalls === 'number' ? { toolCalls: data.toolCalls } : {}),
    ...(typeof data.humanInputs === 'number' ? { humanInputs: data.humanInputs } : {}),
    ...(typeof data.lastUser === 'string' && data.lastUser !== '' ? { lastUser: data.lastUser.slice(0, 200) } : {}),
    archive: objectsOf(data.archive),
    ...(counts !== undefined ? { counts } : {}),
    ...(last !== undefined ? { last } : {}),
    ...(typeof data.detailRev === 'number' && Number.isFinite(data.detailRev) ? { detailRev: data.detailRev } : {}),
    ...(cost !== undefined ? { cost } : {}),
    ...(timing !== null ? { timing } : {}),
    ...(data.systems !== undefined ? { systems: systemsOf(data.systems) } : {}),
    ...(typeof data.surfaceFloor === 'number' ? { surfaceFloor: data.surfaceFloor } : {}),
    ...(typeof data.archiveFloor === 'number' ? { archiveFloor: data.archiveFloor } : {}),
    ...(data.fileOps !== undefined ? { fileOps: objectsOf(data.fileOps) } : {}),
    ...(typeof data.fileOpsFloor === 'number' ? { fileOpsFloor: data.fileOpsFloor } : {}),
  }
  return safe
}

/**
 * The live system-prompt nodes, re-proved per entry and sorted by seq: an
 * entry missing a finite seq/time/tokens drops out (the browser then falls
 * back to the header epoch), so a hostile collection can never produce a NaN
 * prompt figure or an unfetchable seq. Absent or empty stays absent.
 */
function systemsOf(value: unknown): ContextTimeline['systems'] {
  const list = objectsOf<Record<string, unknown>>(value)
  const out: SystemPromptNode[] = []
  for (const entry of list) {
    const { seq, time, tokens } = entry
    if (typeof seq !== 'number' || !Number.isFinite(seq)) continue
    if (typeof time !== 'number' || !Number.isFinite(time)) continue
    if (typeof tokens !== 'number' || !Number.isFinite(tokens)) continue
    out.push({ seq, time, tokens })
  }
  return out.sort((a, b) => a.seq - b.seq)
}

/**
 * The fast path's check for the live system-prompt nodes: every entry must
 * carry the three finite numbers the browser reads — `seq` for the per-step
 * resolution, `time` for the DNA band, `tokens` for its width. A primitive
 * entry, or one whose fields are not numbers, sends the payload down the
 * sanitizing slow path (`systemsOf` drops it) instead of leaking `undefined`
 * into the bar math. An absent list is fine.
 */
function systemsFastOk(value: unknown): boolean {
  if (value === undefined) return true
  if (!Array.isArray(value)) return false
  return value.every((entry) => {
    if (entry === null || typeof entry !== 'object') return false
    const { seq, time, tokens } = entry as Record<string, unknown>
    return typeof seq === 'number' && Number.isFinite(seq)
      && typeof time === 'number' && Number.isFinite(time)
      && typeof tokens === 'number' && Number.isFinite(tokens)
  })
}

/**
 * The split head's count figures, re-proved field by field: a present-but-
 * partial record zeroes its unreadable fields (the stats board's no-NaN
 * guarantee), an absent or non-record value stays absent (legacy generation
 * — callers derive the counts from the collections instead).
 */
function countsOf(value: unknown): ContextTimeline['counts'] {
  const data = asRecord(value)
  if (data === null) return undefined
  return {
    turns: numOf(data.turns),
    steps: numOf(data.steps),
    injects: numOf(data.injects),
    compactions: numOf(data.compactions),
    prunes: numOf(data.prunes),
  }
}

/** The split head's newest-request summary; absent or shapeless stays absent. */
function lastOf(value: unknown): ContextTimeline['last'] {
  const data = asRecord(value)
  if (data === null) return undefined
  if (typeof data.seq !== 'number' || !Number.isFinite(data.seq)) return undefined
  if (typeof data.total !== 'number' || !Number.isFinite(data.total)) return undefined
  return {
    seq: data.seq,
    total: data.total,
    ...(typeof data.prompt === 'number' && Number.isFinite(data.prompt) ? { prompt: data.prompt } : {}),
  }
}

/**
 * Narrow a delivered projection value to the official token-meter
 * `contextPressure` projection (provider-anchored occupancy of the next
 * request). Absent key or value = the meter's projection is not composed
 * (e.g. a harness without the session-projection registry) — callers fall
 * back to their derived anchor, so the UI degrades gracefully. The three
 * fields are independent last-wins records on the wire (dsh's strict wire
 * schema), so each is re-proved on its own: a wrong-typed field drops out,
 * the readable ones survive.
 */
export function contextPressureOf(value: unknown): ContextPressure | null {
  const data = asRecord(value)
  if (data === null) return null
  const out: ContextPressure = {}
  if (typeof data.pressureTokens === 'number' && Number.isFinite(data.pressureTokens)) out.pressureTokens = data.pressureTokens
  if (typeof data.projectedTokens === 'number' && Number.isFinite(data.projectedTokens)) out.projectedTokens = data.projectedTokens
  if (typeof data.contextWindow === 'number' && Number.isFinite(data.contextWindow)) out.contextWindow = data.contextWindow
  return out
}

/**
 * Narrow a delivered projection value to the official token-meter
 * `contextBreakdown` projection (the heuristic composition rows of the chat
 * ring's panel). Every figure must be a finite number — a partial/corrupt
 * value degrades to null so the composition card falls back to the fold's
 * own sums instead of mixing sources.
 */
export function contextBreakdownOf(value: unknown): ContextBreakdown | null {
  const data = asRecord(value)
  if (data === null) return null
  const { systemTokens, toolsTokens, messageTokens } = data
  if (typeof systemTokens !== 'number' || !Number.isFinite(systemTokens)) return null
  if (typeof toolsTokens !== 'number' || !Number.isFinite(toolsTokens)) return null
  if (typeof messageTokens !== 'number' || !Number.isFinite(messageTokens)) return null
  return { systemTokens, toolsTokens, messageTokens }
}

/**
 * Narrow a delivered projection value to the official token-meter
 * `tokenUsage` projection (durable cumulative provider usage). Absent key or
 * value = the meter's projection is not composed (or no request has reported
 * usage yet) — callers drop the cache-hit cell to a dash. The wire schema is
 * strict with all four buckets REQUIRED (dsh token-meter's projectionSchema),
 * so a partial/corrupt value degrades the whole value to null instead of
 * undercounting the billed total.
 */
export function tokenUsageOf(value: unknown): TokenUsage | null {
  const data = asRecord(value)
  if (data === null) return null
  const { uncachedInputTokens, outputTokens, cacheReadTokens, cacheWriteTokens } = data
  if (typeof uncachedInputTokens !== 'number' || !Number.isFinite(uncachedInputTokens)) return null
  if (typeof outputTokens !== 'number' || !Number.isFinite(outputTokens)) return null
  if (typeof cacheReadTokens !== 'number' || !Number.isFinite(cacheReadTokens)) return null
  if (typeof cacheWriteTokens !== 'number' || !Number.isFinite(cacheWriteTokens)) return null
  return { uncachedInputTokens, outputTokens, cacheReadTokens, cacheWriteTokens }
}

/**
 * A non-negative finite number (the timing totals' every field): NaN or a
 * negative degrades to 0 instead of leaking into donut shares.
 */
function msNumOf(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : 0
}

/**
 * The OPTIONAL timing scalars (the generation split): a real non-negative
 * number passes, anything else — including absence — reads as undefined so the
 * field stays absent on the narrowed value (see `timingOf`).
 */
function optMsNumOf(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : undefined
}

/**
 * Cheap whole-value check for the pass-through path of `timelineOf`: absent
 * timing passes; present timing must already be well-formed (every scalar
 * numeric, every per-name row shaped) — anything else sends the payload down
 * the sanitizing slow path.
 */
function timingFastOk(value: unknown): boolean {
  if (value === undefined) return true
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return false
  const t = value as Record<string, unknown>
  for (const k of ['wallMs', 'ttftMs', 'genMs', 'calls', 'toolsMs', 'toolCalls']) {
    if (typeof t[k] !== 'number') return false
  }
  // The generation split is optional but, when present, must be a finite
  // non-negative number — the same gate the slow path applies, so a hostile
  // bucket cannot slip through the fast path (see `timingOf`).
  for (const k of ['reasoningMs', 'textMs', 'toolArgMs']) {
    const v = t[k]
    if (v !== undefined && (typeof v !== 'number' || !Number.isFinite(v) || v < 0)) return false
  }
  const tools = t.tools
  if (tools === null || typeof tools !== 'object' || Array.isArray(tools)) return false
  for (const k in tools) {
    const row = (tools as Record<string, unknown>)[k]
    if (row === null || typeof row !== 'object') return false
    if (typeof (row as Record<string, unknown>).calls !== 'number') return false
    if (typeof (row as Record<string, unknown>).ms !== 'number') return false
  }
  return true
}

/**
 * Narrow a delivered timing totals value (see TimingTotals) to a RENDER-SAFE
 * shape — the timing card's no-white-screen guarantee. A value that is not a
 * record stays null (the card renders its empty state); wrong-typed scalars
 * zero out and per-name rows failing the shape drop individually, so one
 * hostile row never blanks the ranking.
 */
export function timingOf(value: unknown): TimingTotals | null {
  const data = asRecord(value)
  if (data === null) return null
  const tools: Record<string, ToolTimingTotals> = {}
  const rawTools = data.tools
  if (rawTools !== null && typeof rawTools === 'object' && !Array.isArray(rawTools)) {
    for (const k in rawTools) {
      // A JSON-delivered record can carry an own '__proto__' key; assigning it
      // would set the prototype instead of a row — skip it.
      if (k === '__proto__' || !Object.hasOwn(rawTools, k)) continue
      const row = (rawTools as Record<string, unknown>)[k]
      if (row === null || typeof row !== 'object') continue
      const calls = (row as Record<string, unknown>).calls
      const ms = (row as Record<string, unknown>).ms
      if (typeof calls !== 'number' || !(calls >= 0) || typeof ms !== 'number' || !(ms >= 0)) continue
      tools[k] = { calls, ms }
    }
  }
  const totals: TimingTotals = {
    wallMs: msNumOf(data.wallMs),
    ttftMs: msNumOf(data.ttftMs),
    genMs: msNumOf(data.genMs),
    calls: msNumOf(data.calls),
    toolsMs: msNumOf(data.toolsMs),
    toolCalls: msNumOf(data.toolCalls),
    tools,
  }
  // The generation split stays ABSENT when the host did not serve it (a row
  // cached before the split) or served a non-number: the card then renders the
  // un-split shape instead of three meaningless zero rows.
  const reasoning = optMsNumOf(data.reasoningMs)
  if (reasoning !== undefined) totals.reasoningMs = reasoning
  const textMs = optMsNumOf(data.textMs)
  if (textMs !== undefined) totals.textMs = textMs
  const toolArgMs = optMsNumOf(data.toolArgMs)
  if (toolArgMs !== undefined) totals.toolArgMs = toolArgMs
  return totals
}

/**
 * Narrow a delivered projection value to the plugin's `contextHeaders`
 * (request-header epoch METADATA — boundaries, token prices, attribution).
 * Absent key = an older Host half without the companion unit — the Context
 * browser degrades its system/tools sections to a metadata-only note.
 *
 * Entry-level shape is checked too: a malformed epoch (corrupt payload with
 * a missing tools list, a wrong-typed systemTokens, or a tool row whose
 * name/tokens the browser reads blindly — `tool.name.toLowerCase()` and
 * `b.tokens - a.tokens` throw on junk) would crash the browser's
 * tools/sections reads, so the WHOLE projection degrades to null and the
 * card falls back to its metadata-only note. The epoch CONTENT is
 * not part of this value — the browser fetches it per epoch on demand.
 *
 * The pre-#37 wire generation carries the system TEXT instead of its token
 * price (a host still running the old view — stale watch build, an app not
 * restarted since the upgrade — serves it from its cache verbatim), so the
 * two generations are normalized to the metadata shape here: unpriced legacy
 * entries get the shared meter heuristic applied, priced ones and
 * new-shape values pass through untouched.
 */
export function headersOf(value: unknown): ContextHeaders | null {
  const headers = asRecord(value)
  if (headers === null || !Array.isArray(headers.headers)) return null
  for (const h of headers.headers as unknown[]) {
    if (h === null || typeof h !== 'object') return null
    const entry = h as { tools?: unknown; systemTokens?: unknown }
    if (!Array.isArray(entry.tools)) return null
    if (entry.systemTokens !== undefined && (typeof entry.systemTokens !== 'number' || !Number.isFinite(entry.systemTokens))) return null
    for (const t of entry.tools as unknown[]) {
      if (t === null || typeof t !== 'object') return null
      const tool = t as { name?: unknown; tokens?: unknown; plugin?: unknown }
      if (typeof tool.name !== 'string') return null
      if (typeof tool.tokens !== 'number' || !Number.isFinite(tool.tokens)) return null
      if (tool.plugin !== undefined && typeof tool.plugin !== 'string') return null
    }
  }
  let legacy = false
  for (const entry of headers.headers as { systemTokens?: unknown; system?: unknown }[]) {
    if (entry.systemTokens === undefined && typeof entry.system === 'string' && entry.system !== '') {
      legacy = true
      break
    }
  }
  if (!legacy) return headers as unknown as ContextHeaders
  return {
    headers: (headers.headers as { systemTokens?: number; system?: unknown }[]).map((entry) => {
      if (entry.systemTokens !== undefined) return entry
      return { ...entry, systemTokens: estimateSystemTokens(entry.system) || undefined }
    }),
  } as unknown as ContextHeaders
}

/**
 * Narrow a delivered `contextActivity` value (the per-day ledger the Context
 * Overview's heatmap reads off each session-list row) to a render-safe shape.
 * Absent or non-record stays null (an older host serves no such key — the
 * heatmap renders its empty note). Per-day re-proved: a malformed key or a
 * wrong-typed figure drops just that entry, a hostile day record can never
 * produce a NaN cell, and a well-formed payload passes through untouched
 * (reference-stable for the selector equality).
 */
export function activityOf(value: unknown): ContextActivity | null {
  const data = asRecord(value)
  if (data === null) return null
  const rawDays = asRecord(data.days)
  if (rawDays === null || Array.isArray(rawDays)) return null
  const days: Record<string, ActivityDay> = {}
  let dirty = false
  for (const key of Object.keys(rawDays)) {
    const entry = asRecord(rawDays[key])
    const tokens = entry?.tokens
    const requests = entry?.requests
    if (!/^\d{4}-\d{2}-\d{2}$/.test(key)
      || entry === null || Array.isArray(entry)
      || typeof tokens !== 'number' || !Number.isFinite(tokens) || tokens < 0
      || typeof requests !== 'number' || !Number.isFinite(requests) || requests < 0) {
      dirty = true
      continue
    }
    days[key] = { tokens, requests }
  }
  // Fully well-formed: pass the delivered value through untouched (cheap, and
  // reference-stable for the selector equality); otherwise the sanitized copy.
  return dirty ? { days } : data as unknown as ContextActivity
}
/** Pick-moment snapshot of the trigger token span (draftRev CAS). */
export interface TokenSpan {
  start: number
  end: number
  draftRev: number
}
/**
 * On-demand full content for one surface-node seq: resolves the joined
 * conversation node, `null` when the durable log does not hold the seq,
 * rejects on transport/RPC failure (the caller distinguishes the three).
 */
export type ContentFetcher = (seq: number) => Promise<ConversationNodeLike | null>

/**
 * On-demand CONTENT for one `contextHeaders` epoch seq: the fetched system
 * prompt and tool schemas (see historyPage.ts), `null` when the durable log
 * does not hold the epoch, rejects on transport/RPC failure (the caller
 * distinguishes the three).
 */
export type HeaderFetcher = (seq: number) => Promise<HeaderEpochContent | null>

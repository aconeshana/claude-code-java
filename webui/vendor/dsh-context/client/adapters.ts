/**
 * claude-code-java adapters: the gateway's EXISTING `GET /api/session/context`
 * answer (snake_case occupancy, three-way breakdown, and the durable
 * `SessionMetrics` fold) mapped onto the three token-meter projection shapes
 * the dsh cards read (`ContextPressure` / `ContextBreakdown` / `TokenUsage`).
 * The composer ring and the stats pills read the same answer, so the
 * Context tab's headline, legend, and cache-hit cell agree with them by
 * construction — the same guarantee dsh derives from its meter projections.
 */

import type { ContextBreakdown, ContextPressure, TokenUsage } from '../shared/types'

/** The gateway's `context` field (src/api/types.ts SessionContextUsage), structurally. */
export interface SessionContextUsageLike {
  readonly model?: string
  readonly context_window?: number
  readonly used_tokens?: number | undefined
  readonly used_percentage?: number | undefined
  readonly breakdown?: {
    readonly system_tokens: number
    readonly tools_tokens: number
    readonly message_tokens: number
  } | undefined
}

/** The gateway's `metrics` field (src/api/types.ts SessionMetrics), structurally. */
export interface SessionMetricsLike {
  readonly uncached_input_tokens: number
  readonly output_tokens: number
  readonly cache_write_tokens: number
  readonly cache_read_tokens: number
}

function finite(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : undefined
}

/**
 * Occupancy of the next request: the meter's `used_tokens` is already the
 * provider-anchored figure (last usage sample + heuristic movement), so it
 * lands as both `pressureTokens` and `projectedTokens`.
 */
export function pressureOf(usage: SessionContextUsageLike | null | undefined): ContextPressure | null {
  if (usage == null) return null
  const out: ContextPressure = {}
  const used = finite(usage.used_tokens)
  const window = finite(usage.context_window)
  if (used !== undefined) {
    out.pressureTokens = used
    out.projectedTokens = used
  }
  if (window !== undefined && window > 0) out.contextWindow = window
  return Object.keys(out).length > 0 ? out : null
}

/** The three heuristic composition rows; null when the gateway served no breakdown. */
export function breakdownOf(usage: SessionContextUsageLike | null | undefined): ContextBreakdown | null {
  const breakdown = usage?.breakdown
  if (breakdown == null) return null
  const system = finite(breakdown.system_tokens)
  const tools = finite(breakdown.tools_tokens)
  const messages = finite(breakdown.message_tokens)
  if (system === undefined || tools === undefined || messages === undefined) return null
  return { systemTokens: system, toolsTokens: tools, messageTokens: messages }
}

/** The durable cumulative provider usage (all four buckets required, as dsh's schema demands). */
export function usageOf(metrics: SessionMetricsLike | null | undefined): TokenUsage | null {
  if (metrics == null) return null
  const uncached = finite(metrics.uncached_input_tokens)
  const output = finite(metrics.output_tokens)
  const write = finite(metrics.cache_write_tokens)
  const read = finite(metrics.cache_read_tokens)
  if (uncached === undefined || output === undefined || write === undefined || read === undefined) return null
  return { uncachedInputTokens: uncached, outputTokens: output, cacheReadTokens: read, cacheWriteTokens: write }
}

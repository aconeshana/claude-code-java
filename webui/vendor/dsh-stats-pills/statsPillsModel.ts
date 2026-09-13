// Pure derivations for the composer stats pills, factored out of the
// upstream JSX so the render gates and label composition are unit-testable
// without a render harness. The rules mirror upstream StatsPills.tsx
// exactly; the metrics arrive from the gateway's durable fold instead of
// the upstream client-side window fold (docs/hud-metrics-specification.md
// §6 forbids reconstructing whole-session metrics from visible messages).

import { formatTokens, formatExactTokens, formatCacheHitPercent } from './token-format.ts'
import { formatTokensPerSecond } from './message-chrome.ts'

/** The gateway's durable metrics fold (raw integers; display formatting is here). */
export interface SessionStatsMetrics {
  readonly turns: number
  readonly steps: number
  readonly llm_ms: number
  readonly tool_ms: number
  readonly ttft_ms: number
  readonly ttft_steps: number
  readonly decode_ms: number
  readonly decode_tokens: number
  readonly uncached_input_tokens: number
  readonly output_tokens: number
  readonly cache_write_tokens: number
  readonly cache_read_tokens: number
}

/** The locale seat the formatters and labels read. */
export type StatsTranslate = (key: string, params?: Record<string, string | number>) => string

/** Display-ready figures for the two pills and their dialogs. */
export interface StatsPillsView {
  /** The row renders at all (upstream gate: no steps and no token activity). */
  readonly render: boolean
  readonly showTime: boolean
  readonly showUsage: boolean
  /** The time pill is a button opening a dialog (has at least one timed figure). */
  readonly timeInteractive: boolean
  readonly countsText: string
  readonly tpsText: string | null
  readonly totalText: string
  readonly cacheHitText: string | null
  readonly exactTotal: string
  readonly llmText: string | null
  readonly toolText: string | null
  readonly ttftText: string | null
  readonly dialogSpeedText: string | null
  readonly usageRows: readonly UsageRow[]
}

/** One usage dialog row; absent buckets drop out whole. */
export interface UsageRow {
  readonly label: string
  readonly value: string
}

/** Sum of the three disjoint prompt-side billing buckets. */
export function billedInputTokens(metrics: SessionStatsMetrics): number {
  return metrics.uncached_input_tokens + metrics.cache_read_tokens + metrics.cache_write_tokens
}

/**
 * Localized compact duration: one-decimal seconds under a minute, whole
 * `XmYs` from there — upstream StatsPills' own formatDuration.
 */
export function formatStatsDuration(ms: number, t: StatsTranslate): string {
  const s = ms / 1_000
  if (s < 60) return t('duration.compactSeconds', { seconds: Math.round(s * 10) / 10 })
  const whole = Math.round(s)
  return t('duration.compactMinutes', {
    minutes: Math.floor(whole / 60),
    seconds: whole % 60,
  })
}

/** Fold the durable metrics into every display string the pills render. */
export function statsPillsViewModel(metrics: SessionStatsMetrics, t: StatsTranslate): StatsPillsView {
  const billed = billedInputTokens(metrics)
  const hasTokens = billed > 0 || metrics.output_tokens > 0
  const showTime = metrics.steps > 0
  const showUsage = hasTokens
  const countsText = t('stats.counts', { turns: metrics.turns, steps: metrics.steps })
  const tpsText = metrics.decode_ms > 0
    ? t('message.tokensPerSecond', {
      tps: formatTokensPerSecond(metrics.decode_tokens / (metrics.decode_ms / 1_000)),
    })
    : null
  const total = billed + metrics.output_tokens
  const totalText = t('message.turnUsage.count', { count: formatTokens(total, t) })
  const exactTotal = t('message.turnUsage.count', { count: formatExactTokens(total, t) })
  const cacheHit = formatCacheHitPercent(metrics.cache_read_tokens, billed)
  const cacheHitText = cacheHit !== null ? t('stats.cacheHit', { percent: cacheHit }) : null

  const llmText = metrics.llm_ms > 0 ? formatStatsDuration(metrics.llm_ms, t) : null
  const toolText = metrics.tool_ms > 0 ? formatStatsDuration(metrics.tool_ms, t) : null
  const ttftText = metrics.ttft_steps > 0
    ? formatStatsDuration(metrics.ttft_ms / metrics.ttft_steps, t)
    : null
  const dialogSpeedText = metrics.decode_ms > 0
    ? t('message.tokensPerSecond', {
      tps: formatTokensPerSecond(metrics.decode_tokens / (metrics.decode_ms / 1_000)),
    })
    : null

  const usageRows: UsageRow[] = []
  if (cacheHit !== null) {
    usageRows.push({ label: t('message.turnUsage.cacheHit'), value: `${cacheHit}%` })
  }
  usageRows.push({
    label: t('message.turnUsage.input'),
    value: t('message.turnUsage.count', { count: formatExactTokens(metrics.uncached_input_tokens, t) }),
  })
  usageRows.push({
    label: t('message.turnUsage.cacheRead'),
    value: t('message.turnUsage.count', { count: formatExactTokens(metrics.cache_read_tokens, t) }),
  })
  if (metrics.cache_write_tokens !== 0) {
    usageRows.push({
      label: t('message.turnUsage.cacheWrite'),
      value: t('message.turnUsage.count', { count: formatExactTokens(metrics.cache_write_tokens, t) }),
    })
  }
  usageRows.push({
    label: t('message.turnUsage.output'),
    value: t('message.turnUsage.count', { count: formatExactTokens(metrics.output_tokens, t) }),
  })

  return {
    render: !(metrics.steps === 0 && !hasTokens),
    showTime,
    showUsage,
    timeInteractive: llmText !== null || toolText !== null
      || ttftText !== null || dialogSpeedText !== null,
    countsText,
    tpsText,
    totalText,
    cacheHitText,
    exactTotal,
    llmText,
    toolText,
    ttftText,
    dialogSpeedText,
    usageRows,
  }
}

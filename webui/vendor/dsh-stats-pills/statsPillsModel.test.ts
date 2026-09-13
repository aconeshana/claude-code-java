import { describe, expect, it } from 'vitest'
import {
  billedInputTokens,
  formatStatsDuration,
  statsPillsViewModel,
  type SessionStatsMetrics,
  type StatsTranslate,
} from './statsPillsModel'

/**
 * The app's zh dictionary, exercised directly so the fixture tables pin the
 * composed labels end-to-end (template + number formatting) — the same rows
 * the Java-side SessionMetricsFormat fixtures pin (docs/hud-metrics-
 * specification.md §4: both sides must agree on every formula).
 */
const zh: StatsTranslate = (key, params) => {
  const templates: Record<string, string> = {
    'number.thousand': '{value}K',
    'number.million': '{value}M',
    'number.groupSeparator': ',',
    'duration.compactSeconds': '{seconds}秒',
    'duration.compactMinutes': '{minutes}分{seconds}秒',
    'stats.counts': '{turns} 轮 {steps} 步',
    'stats.cacheHit': '缓存命中 {percent}%',
    'message.tokensPerSecond': '{tps} tok/s',
    'message.turnUsage.count': '{count} tok',
    'message.turnUsage.cacheHit': '缓存命中',
    'message.turnUsage.input': '未缓存输入',
    'message.turnUsage.cacheRead': '缓存读取',
    'message.turnUsage.cacheWrite': '缓存写入',
    'message.turnUsage.output': '输出',
  }
  const template = templates[key] ?? key
  if (params == null) return template
  return template.replace(/\{(\w+)\}/g, (match, name) => (name in params ? String(params[name]) : match))
}

function metrics(overrides: Partial<SessionStatsMetrics> = {}): SessionStatsMetrics {
  return {
    turns: 3,
    steps: 5,
    llm_ms: 12_000,
    tool_ms: 4_000,
    ttft_ms: 900,
    ttft_steps: 5,
    decode_ms: 6_000,
    decode_tokens: 400,
    uncached_input_tokens: 1_000,
    output_tokens: 800,
    cache_write_tokens: 200,
    cache_read_tokens: 9_000,
    ...overrides,
  }
}

describe('billedInputTokens', () => {
  it('sums the three disjoint prompt-side billing buckets', () => {
    expect(billedInputTokens(metrics())).toBe(1_000 + 9_000 + 200)
  })
})

describe('formatStatsDuration', () => {
  it('renders one-decimal seconds under a minute', () => {
    expect(formatStatsDuration(12_250, zh)).toBe('12.3秒')
    expect(formatStatsDuration(0, zh)).toBe('0秒')
    expect(formatStatsDuration(59_999, zh)).toBe('60秒')
  })

  it('renders whole XmYs from a minute up', () => {
    expect(formatStatsDuration(60_000, zh)).toBe('1分0秒')
    expect(formatStatsDuration(83_400, zh)).toBe('1分23秒')
  })
})

describe('statsPillsViewModel gates', () => {
  it('renders nothing for an unmeasured session (no steps, no tokens)', () => {
    const view = statsPillsViewModel(metrics({ turns: 0, steps: 0, llm_ms: 0, tool_ms: 0, ttft_ms: 0, ttft_steps: 0, decode_ms: 0, decode_tokens: 0, uncached_input_tokens: 0, output_tokens: 0, cache_write_tokens: 0, cache_read_tokens: 0 }), zh)
    expect(view.render).toBe(false)
  })

  it('renders the time pill only for a billed-zero but stepped session', () => {
    const view = statsPillsViewModel(metrics({ uncached_input_tokens: 0, output_tokens: 0, cache_write_tokens: 0, cache_read_tokens: 0 }), zh)
    expect(view.render).toBe(true)
    expect(view.showTime).toBe(true)
    expect(view.showUsage).toBe(false)
  })

  it('renders the usage pill only for a token-billed but stepless session', () => {
    const view = statsPillsViewModel(metrics({ steps: 0, llm_ms: 0, tool_ms: 0, ttft_steps: 0, decode_ms: 0 }), zh)
    expect(view.render).toBe(true)
    expect(view.showTime).toBe(false)
    expect(view.showUsage).toBe(true)
  })

  it('the time pill is a plain span when no timed figure exists', () => {
    const view = statsPillsViewModel(metrics({ llm_ms: 0, tool_ms: 0, ttft_ms: 0, ttft_steps: 0, decode_ms: 0, decode_tokens: 0 }), zh)
    expect(view.showTime).toBe(true)
    expect(view.timeInteractive).toBe(false)
    expect(view.tpsText).toBeNull()
    expect(view.llmText).toBeNull()
    expect(view.toolText).toBeNull()
    expect(view.ttftText).toBeNull()
    expect(view.dialogSpeedText).toBeNull()
  })

  it('ttft needs stepping (a bare ttft_ms without steps renders no ttft row)', () => {
    const view = statsPillsViewModel(metrics({ ttft_ms: 900, ttft_steps: 0 }), zh)
    expect(view.ttftText).toBeNull()
  })
})

describe('statsPillsViewModel labels', () => {
  it('composes the counts and throughput pill labels', () => {
    const view = statsPillsViewModel(metrics(), zh)
    expect(view.countsText).toBe('3 轮 5 步')
    // 400 tokens over 6s = 66.67 → whole tokens from 10 up.
    expect(view.tpsText).toBe('67 tok/s')
  })

  it('renders one decimal under 10 tok/s', () => {
    const view = statsPillsViewModel(metrics({ decode_tokens: 40, decode_ms: 6_000 }), zh)
    expect(view.tpsText).toBe('6.7 tok/s')
  })

  it('drops the throughput label when nothing decoded', () => {
    const view = statsPillsViewModel(metrics({ decode_ms: 0, decode_tokens: 0 }), zh)
    expect(view.tpsText).toBeNull()
  })

  it('composes the token pill: compact total plus cache hit', () => {
    const view = statsPillsViewModel(metrics(), zh)
    // billed 10,200 + output 800 = 11,000 → 11K; cache read 9,000/10,200 billed.
    expect(view.totalText).toBe('11K tok')
    expect(view.cacheHitText).toBe('缓存命中 88%')
    expect(view.exactTotal).toBe('11,000 tok')
  })

  it('drops the cache-hit share when no prompt input was billed', () => {
    const view = statsPillsViewModel(metrics({ uncached_input_tokens: 0, cache_read_tokens: 0, cache_write_tokens: 0 }), zh)
    expect(view.cacheHitText).toBeNull()
  })

  it('formats compact totals across the K/M scale (the §4 fixture rows)', () => {
    const at = (tokens: number): string =>
      statsPillsViewModel(metrics({
        uncached_input_tokens: tokens, output_tokens: 0,
        cache_write_tokens: 0, cache_read_tokens: 0,
      }), zh).totalText
    expect(at(999)).toBe('999 tok')
    expect(at(1_000)).toBe('1K tok')
    expect(at(12_200)).toBe('12.2K tok')
    expect(at(517_000)).toBe('517K tok')
    expect(at(1_234_567)).toBe('1.2M tok')
  })

  it('groups exact totals with the locale separator', () => {
    const view = statsPillsViewModel(metrics({ uncached_input_tokens: 1_234_567, output_tokens: 0, cache_write_tokens: 0, cache_read_tokens: 0 }), zh)
    expect(view.exactTotal).toBe('1,234,567 tok')
  })
})

describe('statsPillsViewModel dialog rows', () => {
  it('derives the four time dialog rows from the fold', () => {
    const view = statsPillsViewModel(metrics(), zh)
    expect(view.llmText).toBe('12秒')
    expect(view.toolText).toBe('4秒')
    // ttft_ms is the sum; the row shows the per-step average: 900/5 = 180ms.
    expect(view.ttftText).toBe('0.2秒')
    expect(view.dialogSpeedText).toBe('67 tok/s')
  })

  it('omits absent dialog rows whole and orders the usage rows', () => {
    const view = statsPillsViewModel(metrics({ cache_write_tokens: 0 }), zh)
    expect(view.usageRows.map((row) => row.label)).toEqual([
      '缓存命中', '未缓存输入', '缓存读取', '输出',
    ])
    // 9,000 cached of 10,000 billed (the write bucket dropped) → 90%.
    expect(view.usageRows.map((row) => row.value)).toEqual([
      '90%', '1,000 tok', '9,000 tok', '800 tok',
    ])
  })

  it('adds the cache-write row only when the bucket is nonzero', () => {
    const view = statsPillsViewModel(metrics(), zh)
    expect(view.usageRows.map((row) => row.label)).toEqual([
      '缓存命中', '未缓存输入', '缓存读取', '缓存写入', '输出',
    ])
  })

  it('a full cache hit never rounds up to 100% from a partial hit', () => {
    // 9,999 cached of 10,000 billed: 99.99% — must stay honest, not "100".
    const view = statsPillsViewModel(metrics({
      uncached_input_tokens: 1, cache_read_tokens: 9_999, cache_write_tokens: 0, output_tokens: 0,
    }), zh)
    expect(view.cacheHitText).toBe('缓存命中 99.99%')
  })
})

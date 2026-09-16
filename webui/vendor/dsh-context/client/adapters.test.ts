import { describe, expect, it } from 'vitest'
import { breakdownOf, pressureOf, usageOf } from './adapters'

describe('dsh-context adapters over GET /api/session/context', () => {
  it('pressureOf lands used_tokens as both the anchor and the projection', () => {
    expect(pressureOf({ model: 'sonnet', context_window: 200_000, used_tokens: 31_000, used_percentage: 16 }))
      .toEqual({ pressureTokens: 31_000, projectedTokens: 31_000, contextWindow: 200_000 })
  })

  it('pressureOf keeps the window when the count is not yet known and nulls an empty seat', () => {
    expect(pressureOf({ context_window: 200_000 })).toEqual({ contextWindow: 200_000 })
    expect(pressureOf({ context_window: 0 })).toBeNull()
    expect(pressureOf(null)).toBeNull()
    expect(pressureOf({ used_tokens: -1, context_window: Number.NaN })).toBeNull()
  })

  it('breakdownOf maps the three snake_case rows and refuses a partial breakdown', () => {
    expect(breakdownOf({ breakdown: { system_tokens: 3_000, tools_tokens: 12_000, message_tokens: 16_000 } }))
      .toEqual({ systemTokens: 3_000, toolsTokens: 12_000, messageTokens: 16_000 })
    expect(breakdownOf({})).toBeNull()
    expect(breakdownOf({ breakdown: { system_tokens: 1, tools_tokens: Number.NaN, message_tokens: 2 } })).toBeNull()
  })

  it('usageOf requires all four provider buckets', () => {
    expect(usageOf({ uncached_input_tokens: 1_000, output_tokens: 800, cache_write_tokens: 200, cache_read_tokens: 9_000 }))
      .toEqual({ uncachedInputTokens: 1_000, outputTokens: 800, cacheReadTokens: 9_000, cacheWriteTokens: 200 })
    expect(usageOf(null)).toBeNull()
    expect(usageOf({ uncached_input_tokens: 1, output_tokens: 1, cache_write_tokens: 1, cache_read_tokens: -5 })).toBeNull()
  })
})

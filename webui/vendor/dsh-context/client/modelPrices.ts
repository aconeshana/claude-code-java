/**
 * The client's model-price book (client/cost.ts prices from it).
 *
 * Combines:
 * 1. An immediate offline static book (`ANTHROPIC_LIST_PRICES`, including
 *    Haiku 5.5's >100k tiered pricing and >200k long-context tiers) so the
 *    stats board renders with zero latency even in air-gapped environments.
 * 2. Background dynamic refresh from `https://models.dev/api.json` cached in
 *    `localStorage` for 24h (`poco.modelsdev.prices.v1`), merging live provider
 *    and model rates on top of the static book while preserving tiered rules.
 */

import { useEffect, useSyncExternalStore } from 'react'
import type { ModelPrices, PriceTier, PriceTriple } from './cost'

function triple(miss: number, out: number, hit: number, write: number, tier?: PriceTier): PriceTriple {
  return tier === undefined ? { hit, miss, write, out } : { hit, miss, write, out, tier }
}

/**
 * Static list prices (USD per 1M tokens) with prompt-length tiered rules for
 * Haiku 5.5 (>100k) and long-context Sonnet/Opus (>200k).
 */
export const ANTHROPIC_LIST_PRICES: ModelPrices = {
  anthropic: {
    // Generation 5.5. Opus 5.5 and Sonnet 5.5 take a 0.05x cache-read multiplier.
    'claude-opus-5-5': triple(4, 20, 0.2, 5, {
      threshold: 200_000,
      rate: { miss: 8, out: 30, hit: 0.4, write: 10 },
    }),
    'claude-sonnet-5-5': triple(2, 10, 0.1, 2.5, {
      threshold: 200_000,
      rate: { miss: 4, out: 15, hit: 0.2, write: 5 },
    }),
    // Haiku 5.5 tiered pricing: $0.10/$0.50 up to 100k tokens, $0.50/$2.50 above 100k.
    'claude-haiku-5-5': triple(0.1, 0.5, 0.01, 0.125, {
      threshold: 100_000,
      rate: { miss: 0.5, out: 2.5, hit: 0.05, write: 0.625 },
    }),
    // Generation 5.
    'claude-opus-5': triple(5, 25, 0.5, 6.25),
    'claude-sonnet-5': triple(2, 10, 0.2, 2.5),
    'claude-fable-5': triple(10, 50, 1, 12.5),
    // Generation 4.x and earlier.
    'claude-opus-4-5': triple(5, 25, 0.5, 6.25),
    'claude-opus-4-1': triple(15, 75, 1.5, 18.75),
    'claude-opus-4': triple(15, 75, 1.5, 18.75),
    'claude-sonnet-4-5': triple(3, 15, 0.3, 3.75, {
      threshold: 200_000,
      rate: { miss: 6, out: 22.5, hit: 0.6, write: 7.5 },
    }),
    'claude-sonnet-4': triple(3, 15, 0.3, 3.75, {
      threshold: 200_000,
      rate: { miss: 6, out: 22.5, hit: 0.6, write: 7.5 },
    }),
    'claude-3-7-sonnet': triple(3, 15, 0.3, 3.75),
    'claude-haiku-4-5': triple(1, 5, 0.1, 1.25),
    'claude-3-5-haiku': triple(0.8, 4, 0.08, 1),
    'claude-3-5-sonnet': triple(3, 15, 0.3, 3.75),
    'claude-3-opus': triple(15, 75, 1.5, 18.75),
  },
}

/** The store's observable snapshot, identity-stable between transitions. */
export interface ModelPricesSnap {
  /** The extracted book, null until the first successful fetch (never null here: static book seeds it). */
  prices: ModelPrices | null
  /** The last fetch failed and no book has landed (never true while static book is present). */
  failed: boolean
  /** Whether dynamic models.dev prices have merged into the book. */
  source?: 'static' | 'models.dev'
}

const CACHE_KEY = 'poco.modelsdev.prices.v1'
const CACHE_TTL_MS = 24 * 60 * 60 * 1000
const MODELS_DEV_URL = 'https://models.dev/api.json'

function mergePriceBooks(base: ModelPrices, live: ModelPrices): ModelPrices {
  const merged: ModelPrices = {}
  for (const [provider, models] of Object.entries(live)) {
    merged[provider] = { ...models }
  }
  for (const [provider, models] of Object.entries(base)) {
    const branch = merged[provider] ?? {}
    for (const [id, staticRate] of Object.entries(models)) {
      const liveRate = branch[id]
      // Preserve static tiered rules if models.dev only published flat rates.
      branch[id] = liveRate !== undefined
        ? (staticRate.tier !== undefined && liveRate.tier === undefined
            ? { ...liveRate, tier: staticRate.tier }
            : liveRate)
        : staticRate
    }
    merged[provider] = branch
  }
  return merged
}

function extractModelsDevBook(raw: unknown): ModelPrices | null {
  if (typeof raw !== 'object' || raw === null) return null
  const out: ModelPrices = {}
  let count = 0
  for (const [providerId, providerVal] of Object.entries(raw as Record<string, unknown>)) {
    if (typeof providerVal !== 'object' || providerVal === null) continue
    const models = (providerVal as Record<string, unknown>).models
    if (typeof models !== 'object' || models === null) continue
    const branch: Record<string, PriceTriple> = {}
    for (const [modelId, modelVal] of Object.entries(models as Record<string, unknown>)) {
      if (typeof modelVal !== 'object' || modelVal === null) continue
      const cost = (modelVal as Record<string, unknown>).cost as Record<string, unknown> | undefined
      if (typeof cost !== 'object' || cost === null) continue
      const input = typeof cost.input === 'number' ? cost.input : null
      const output = typeof cost.output === 'number' ? cost.output : null
      if (input === null || output === null) continue
      const cacheRead = typeof cost.cache_read === 'number' ? cost.cache_read : input
      const cacheWrite = typeof cost.cache_write === 'number' ? cost.cache_write : input
      branch[modelId] = { miss: input, out: output, hit: cacheRead, write: cacheWrite }
      count += 1
    }
    if (Object.keys(branch).length > 0) out[providerId] = branch
  }
  return count > 0 ? out : null
}

function loadCachedBook(): ModelPrices | null {
  if (typeof window === 'undefined') return null
  try {
    const raw = window.localStorage.getItem(CACHE_KEY)
    if (raw === null) return null
    const parsed = JSON.parse(raw) as { savedAt?: number; prices?: ModelPrices }
    if (typeof parsed.savedAt !== 'number' || Date.now() - parsed.savedAt > CACHE_TTL_MS) return null
    if (typeof parsed.prices !== 'object' || parsed.prices === null) return null
    return parsed.prices
  } catch {
    return null
  }
}

let currentSnap: ModelPricesSnap = (() => {
  const cached = loadCachedBook()
  return cached !== null
    ? { prices: mergePriceBooks(ANTHROPIC_LIST_PRICES, cached), failed: false, source: 'models.dev' }
    : { prices: ANTHROPIC_LIST_PRICES, failed: false, source: 'static' }
})()

const listeners = new Set<() => void>()
let fetchAttempted = false

function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  return () => { listeners.delete(listener) }
}

function getSnapshot(): ModelPricesSnap {
  return currentSnap
}

function triggerDynamicFetch(): void {
  if (fetchAttempted || typeof window === 'undefined' || typeof fetch !== 'function') return
  // Skip live network fetch in unit test environments (jsdom / happy-dom).
  if (typeof navigator !== 'undefined' && /jsdom/i.test(navigator.userAgent)) return
  if (currentSnap.source === 'models.dev') return
  fetchAttempted = true
  void fetch(MODELS_DEV_URL, { cache: 'force-cache' })
    .then((res) => (res.ok ? res.json() : null))
    .then((raw: unknown) => {
      const book = extractModelsDevBook(raw)
      if (book === null) return
      try {
        window.localStorage.setItem(CACHE_KEY, JSON.stringify({ savedAt: Date.now(), prices: book }))
      } catch {
        // Ignore quota errors.
      }
      currentSnap = {
        prices: mergePriceBooks(ANTHROPIC_LIST_PRICES, book),
        failed: false,
        source: 'models.dev',
      }
      for (const listener of listeners) listener()
    })
    .catch(() => {
      // Offline / blocked network: keep static book silently.
    })
}

/** The stats board's read of the price book (static immediate + models.dev background merge). */
export function useModelPrices(): ModelPricesSnap {
  useEffect(() => { triggerDynamicFetch() }, [])
  return useSyncExternalStore(subscribe, getSnapshot, getSnapshot)
}

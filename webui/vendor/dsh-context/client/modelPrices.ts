/**
 * The client's model-price book (client/cost.ts prices from it).
 *
 * Upstream dsh-context fetches the models.dev registry through the
 * `@opencode-ai/models` SDK. claude-code-java replaces that with a STATIC
 * Anthropic list price table (USD per 1M tokens, public list prices at
 * vendoring time): no network, no retry machine; a model the table does not
 * carry simply prices nothing and the cost cells dash with the outage note.
 * The `ModelPricesSnap` shape and `useModelPrices()` seat stay so the stats
 * board is untouched.
 */

import type { ModelPrices, PriceTriple } from './cost'

function triple(miss: number, out: number, hit: number, write: number): PriceTriple {
  return { hit, miss, write, out }
}

/**
 * Keyed by the model family id (the date suffix of a concrete snapshot id,
 * `claude-sonnet-4-5-20250929`, is stripped by cost.ts's lookup). Provider
 * branch `anthropic`: the fold keys our usage under the empty provider id,
 * and `priceOf` resolves an unknown provider by a cross-branch scan.
 */
export const ANTHROPIC_LIST_PRICES: ModelPrices = {
  anthropic: {
    'claude-opus-4-5': triple(5, 25, 0.5, 6.25),
    'claude-opus-4-1': triple(15, 75, 1.5, 18.75),
    'claude-opus-4': triple(15, 75, 1.5, 18.75),
    'claude-sonnet-4-5': triple(3, 15, 0.3, 3.75),
    'claude-sonnet-4': triple(3, 15, 0.3, 3.75),
    'claude-3-7-sonnet': triple(3, 15, 0.3, 3.75),
    'claude-haiku-4-5': triple(1, 5, 0.1, 1.25),
    'claude-3-5-haiku': triple(0.8, 4, 0.08, 1),
    'claude-3-5-sonnet': triple(3, 15, 0.3, 3.75),
    'claude-3-opus': triple(15, 75, 1.5, 18.75),
  },
}

/** The store's observable snapshot, identity-stable between transitions. */
export interface ModelPricesSnap {
  /** The extracted book, null until the first successful fetch (never null here: the book is static). */
  prices: ModelPrices | null
  /** The last fetch failed and no book has landed (never true here). */
  failed: boolean
}

const SNAP: ModelPricesSnap = { prices: ANTHROPIC_LIST_PRICES, failed: false }

/** The stats board's read of the price book. */
export function useModelPrices(): ModelPricesSnap {
  return SNAP
}

/**
 * Dedup/collapse logic for one assistant message's produced-file chip row —
 * the pure function `DeliverablesRow.tsx` renders. Dedup is by exact path
 * string, keeping first-occurrence order across the message's tool calls.
 */

export interface DeliverablesSummary {
  readonly shown: readonly string[]
  readonly overflow: number
}

const CHIP_LIMIT = 6

export function collectDeliverables(
  toolCalls: readonly { readonly locations: readonly string[] | null }[],
): DeliverablesSummary {
  const seen = new Set<string>()
  const ordered: string[] = []
  for (const call of toolCalls) {
    for (const path of call.locations ?? []) {
      if (seen.has(path)) continue
      seen.add(path)
      ordered.push(path)
    }
  }
  return {
    shown: ordered.slice(0, CHIP_LIMIT),
    overflow: Math.max(0, ordered.length - CHIP_LIMIT),
  }
}

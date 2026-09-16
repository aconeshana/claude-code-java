/**
 * Chat→Context jump resolution (the data half of dsh-context's
 * `contextJump.tsx` + `viewFocus.ts`). dsh reads the reply's request seq
 * off its conversation-node seat; claude-code-java's chat rows carry the
 * transcript timestamp and the 1-based turn, so the Context tab resolves
 * the seq against its own request ledger:
 *
 * 1. an explicit `seq` wins when a request carries it;
 * 2. else the request whose `time` is nearest the reply's timestamp (both
 *    are the same `Message.timestamp`, so this is normally exact; a 60s
 *    window guards against pinning an unrelated request);
 * 3. else the last request of the named turn;
 * 4. else null (the tab switches without a pin, as upstream does).
 */

import type { ContextJumpTarget } from '../../store/contextTimeline'
import type { RequestRecord } from '@dsh-context/shared/types'

const TIME_WINDOW_MS = 60_000

export function resolveJumpSeq(requests: readonly RequestRecord[], target: ContextJumpTarget): number | null {
  if (target.seq !== undefined && requests.some((request) => request.seq === target.seq)) return target.seq
  if (target.time !== undefined && Number.isFinite(target.time)) {
    let best: RequestRecord | null = null
    let bestDelta = Number.POSITIVE_INFINITY
    for (const request of requests) {
      const delta = Math.abs(request.time - target.time)
      if (delta < bestDelta) {
        best = request
        bestDelta = delta
      }
    }
    if (best !== null && bestDelta <= TIME_WINDOW_MS) return best.seq
  }
  if (target.turn !== undefined) {
    let last: RequestRecord | null = null
    for (const request of requests) if (request.turn === target.turn) last = request
    if (last !== null) return last.seq
  }
  return null
}

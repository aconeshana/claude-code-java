/** Operation-sequence and recorded-time projections for the trajectory overview. */

import type { TrajectoryTurnModel } from './shim-types.ts'
import type { TrajectoryTranslate } from './locales.ts'
import { formatDurationMillis } from './trajectory-record.ts'
import type { TrajectoryCellKind, TrajectoryCellProps } from './trajectory-record.ts'

/** Horizontal projection used by the trajectory timeline. */
export type TrajectoryTimelineMode = 'sequence' | 'duration' | 'time' | 'actual'

/** Inclusive selection in the active timeline projection's domain. */
export interface TrajectoryTimeRange {
  start: number
  end: number
}

/** One ledger record projected into the active timeline domain. */
export interface TrajectoryTimelineSpan extends TrajectoryTimeRange {
  index: number
  isError: boolean
  kind: TrajectoryCellKind
  label: string
  lane: number
  subLane?: number
  subLaneCount?: number
  parallelSize?: number
}

/** One turn boundary in the active timeline domain. */
export interface TrajectoryTimelineTurnBoundary {
  turn: number
  time: number
}

/** Full-domain model used by the overview. */
export interface TrajectoryTimelineModel extends TrajectoryTimeRange {
  spans: readonly TrajectoryTimelineSpan[]
  turnBoundaries: readonly TrajectoryTimelineTurnBoundary[]
}

/**
 * Format a timeline duration as an integer-millisecond label.
 * @param milliseconds - Non-negative duration in milliseconds.
 * @param t - Trajectory locale translator.
 * @returns Millisecond label with thousands separators.
 */
export function formatTimelineOffset(
  milliseconds: number,
  t: TrajectoryTranslate,
): string {
  return formatDurationMillis(milliseconds, t)
}

function laneFor(kind: TrajectoryCellKind): number {
  if (kind === 'tool' || kind === 'subtool') return 2
  if (kind === 'message' || kind === 'compacted') return 1
  return 0
}

function finite(value: number | null | undefined): value is number {
  return value !== null && value !== undefined && Number.isFinite(value)
}

function cellRange(cell: TrajectoryCellProps): TrajectoryTimeRange | null {
  if (!finite(cell.startedAt)) return null
  const durationMs = finite(cell.timeSeconds)
    ? Math.max(0, cell.timeSeconds * 1_000)
    : 0
  return { start: cell.startedAt, end: cell.startedAt + durationMs }
}

/**
 * Project every visible record into a stable three-lane timeline.
 * @param turns - Unfiltered trajectory layout.
 * @param mode - Independent equal/recorded duration and compressed/complete time projection.
 * @returns Timeline model, or `null` when no record is visible.
 */
export function deriveTrajectoryTimeline(
  turns: readonly TrajectoryTurnModel[],
  mode: TrajectoryTimelineMode = 'sequence',
): TrajectoryTimelineModel | null {
  if (mode !== 'sequence') {
    return deriveTimedTimeline(
      turns,
      mode === 'duration' || mode === 'actual',
      mode === 'duration',
    )
  }
  const spans: TrajectoryTimelineSpan[] = []
  const turnBoundaries: TrajectoryTimelineTurnBoundary[] = []
  let cursor = 0

  for (const turn of turns) {
    const cells = turn.groups.flatMap(group =>
      group.cells.filter(cell => cell.requestOnly !== true),
    )
    if (cells.length === 0) continue
    if (turn.turn !== null) {
      turnBoundaries.push({
        turn: turn.turn,
        time: cursor,
      })
    }
    let idx = 0
    while (idx < cells.length) {
      const cell = cells[idx]!
      const pGroup = cell.parallelGroupId
      if (pGroup !== undefined && (cell.parallelSize ?? 0) >= 2 && laneFor(cell.kind) === 2) {
        const batch: TrajectoryCellProps[] = [cell]
        let next = idx + 1
        while (next < cells.length && cells[next]?.parallelGroupId === pGroup) {
          batch.push(cells[next]!)
          next += 1
        }
        const laneCount = Math.min(4, batch.length)
        for (let b = 0; b < batch.length; b++) {
          const member = batch[b]!
          spans.push({
            start: cursor,
            end: cursor + 1,
            index: member.index,
            isError: member.isError === true,
            kind: member.kind,
            label: member.text,
            lane: 2,
            subLane: b % laneCount,
            subLaneCount: laneCount,
            parallelSize: member.parallelSize ?? batch.length,
          })
        }
        cursor += 1
        idx = next
        continue
      }
      spans.push({
        start: cursor,
        end: cursor + 1,
        index: cell.index,
        isError: cell.isError === true,
        kind: cell.kind,
        label: cell.text,
        lane: laneFor(cell.kind),
      })
      cursor += 1
      idx += 1
    }
  }

  if (spans.length === 0) return null
  return {
    start: 0,
    end: cursor,
    spans,
    turnBoundaries,
  }
}

function deriveTimedTimeline(
  turns: readonly TrajectoryTurnModel[],
  actualDuration: boolean,
  compressIdle: boolean,
): TrajectoryTimelineModel | null {
  const timedTurns = turns.flatMap((turn) => {
    const rawSpans = turn.groups.flatMap(group =>
      group.cells.flatMap((cell): TrajectoryTimelineSpan[] => {
        if (cell.requestOnly === true) return []
        const range = cellRange(cell)
        return range === null
          ? []
          : [{
            ...range,
            index: cell.index,
            isError: cell.isError === true,
            kind: cell.kind,
            label: cell.text,
            lane: laneFor(cell.kind),
            ...(cell.parallelSize !== undefined && cell.parallelSize >= 2
              ? { parallelSize: cell.parallelSize }
              : {}),
          }]
      }),
    )
    return rawSpans.length === 0 ? [] : [{ turn: turn.turn, rawSpans }]
  })
  const rawSpans = timedTurns.flatMap(turn => turn.rawSpans)
  if (rawSpans.length === 0) return null

  const removedIdleBySpan = new Map<TrajectoryTimelineSpan, number>()
  let removedIdle = 0
  let coveredUntil: number | null = null
  for (const span of [...rawSpans].sort((left, right) =>
    left.start - right.start || left.end - right.end)) {
    if (compressIdle && coveredUntil !== null && span.start > coveredUntil) {
      removedIdle += span.start - coveredUntil
    }
    removedIdleBySpan.set(span, removedIdle)
    coveredUntil = coveredUntil === null ? span.end : Math.max(coveredUntil, span.end)
  }

  const spans: TrajectoryTimelineSpan[] = []
  const turnBoundaries: TrajectoryTimelineTurnBoundary[] = []
  for (const turn of timedTurns) {
    const projected = turn.rawSpans.map((span): TrajectoryTimelineSpan => {
      const offset = removedIdleBySpan.get(span) ?? 0
      return {
        ...span,
        start: span.start - offset,
        end: (actualDuration ? span.end : span.start) - offset,
      }
    })
    spans.push(...projected)
    if (turn.turn !== null) {
      turnBoundaries.push({
        turn: turn.turn,
        time: Math.min(...projected.map(span => span.start)),
      })
    }
  }

  packToolSublanes(spans)

  return {
    start: Math.min(...spans.map(span => span.start)),
    end: Math.max(...spans.map(span => span.end)),
    spans,
    turnBoundaries,
  }
}

const MAX_TOOL_SUBLANES = 4

function packToolSublanes(spans: TrajectoryTimelineSpan[]): void {
  const toolSpans = spans
    .filter(span => span.lane === 2)
    .sort((a, b) => a.start - b.start || a.index - b.index)
  if (toolSpans.length < 2) return

  let idx = 0
  while (idx < toolSpans.length) {
    const first = toolSpans[idx]!
    const cluster: TrajectoryTimelineSpan[] = [first]
    let clusterMaxEnd = first.end
    let next = idx + 1
    while (next < toolSpans.length) {
      const cand = toolSpans[next]!
      const overlaps = cand.start < clusterMaxEnd
        || (cand.start === first.start && cand.parallelSize !== undefined)
      if (!overlaps) break
      cluster.push(cand)
      clusterMaxEnd = Math.max(clusterMaxEnd, cand.end)
      next += 1
    }
    if (cluster.length >= 2) {
      const laneEnds: number[] = []
      const assigned: number[] = []
      for (const member of cluster) {
        let chosen = laneEnds.findIndex(end => end <= member.start && end < member.end)
        if (chosen === -1) {
          if (laneEnds.length < MAX_TOOL_SUBLANES) {
            chosen = laneEnds.length
            laneEnds.push(member.end)
          } else {
            let minIdx = 0
            for (let k = 1; k < laneEnds.length; k++) {
              if (laneEnds[k]! < laneEnds[minIdx]!) minIdx = k
            }
            chosen = minIdx
            laneEnds[chosen] = Math.max(laneEnds[chosen]!, member.end)
          }
        } else {
          laneEnds[chosen] = member.end
        }
        assigned.push(chosen)
      }
      const usedCount = Math.max(2, laneEnds.length)
      for (let k = 0; k < cluster.length; k++) {
        cluster[k]!.subLane = assigned[k]!
        cluster[k]!.subLaneCount = usedCount
      }
    }
    idx = next
  }
}

/**
 * Identify records active at any point inside an inclusive selected interval.
 * @param turns - Unfiltered trajectory layout.
 * @param range - Selected interval in the active projection.
 * @param mode - Independent equal/recorded duration and compressed/complete time projection.
 * @returns Record indexes inside the focus interval.
 */
export function trajectoryTimelineFocusIndexes(
  turns: readonly TrajectoryTurnModel[],
  range: TrajectoryTimeRange,
  mode: TrajectoryTimelineMode = 'sequence',
): ReadonlySet<number> {
  const model = deriveTrajectoryTimeline(turns, mode)
  return new Set(
    model?.spans
      .filter(span => span.start <= range.end && span.end >= range.start)
      .map(span => span.index),
  )
}

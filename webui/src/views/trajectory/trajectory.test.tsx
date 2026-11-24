import { describe, expect, it } from 'vitest'
import { deriveTrajectoryTimeline } from '@vendor/ui-trajectory/timeline.ts'
import type { TrajectoryTurnModel } from '@vendor/ui-trajectory/shim-types.ts'

describe('deriveTrajectoryTimeline parallel tool sublanes', () => {
  const sampleTurns: readonly TrajectoryTurnModel[] = [
    {
      turn: 1,
      groups: [
        {
          title: 'Message',
          cells: [
            {
              index: 1,
              kind: 'user',
              text: 'Inspect A, B, and C concurrently',
              timeSeconds: 0,
              startedAt: 1000,
            },
          ],
        },
        {
          title: 'Step 1',
          cells: [
            {
              index: 2,
              kind: 'message',
              text: 'Reading 3 files in parallel',
              timeSeconds: 0.4,
              startedAt: 1050,
            },
            {
              index: 3,
              kind: 'tool',
              toolName: 'Read',
              callId: 'c1',
              text: 'Read · A.ts',
              timeSeconds: 0.25,
              startedAt: 1500,
              parallelGroupId: 'pg-1-1-1',
              parallelSize: 3,
              parallelIndex: 0,
            },
            {
              index: 4,
              kind: 'tool',
              toolName: 'Read',
              callId: 'c2',
              text: 'Read · B.ts',
              timeSeconds: 0.22,
              startedAt: 1510,
              parallelGroupId: 'pg-1-1-1',
              parallelSize: 3,
              parallelIndex: 1,
            },
            {
              index: 5,
              kind: 'tool',
              toolName: 'Grep',
              callId: 'c3',
              text: 'Grep · TODO',
              timeSeconds: 0.20,
              startedAt: 1520,
              parallelGroupId: 'pg-1-1-1',
              parallelSize: 3,
              parallelIndex: 2,
            },
            {
              index: 6,
              kind: 'tool',
              toolName: 'Bash',
              callId: 'c4',
              text: 'Bash · pnpm test',
              timeSeconds: 0.5,
              startedAt: 1900,
            },
          ],
        },
      ],
    },
  ]

  it('stacks parallel tool calls in a single horizontal slot in sequence mode', () => {
    const model = deriveTrajectoryTimeline(sampleTurns, 'sequence')
    expect(model).not.toBeNull()
    // 1 user + 1 assistant + 1 shared parallel slot (for 3 tools) + 1 serial Bash = 4 slots!
    expect(model!.end).toBe(4)

    const c1 = model!.spans.find(s => s.index === 3)!
    const c2 = model!.spans.find(s => s.index === 4)!
    const c3 = model!.spans.find(s => s.index === 5)!
    const bash = model!.spans.find(s => s.index === 6)!

    expect(c1.start).toBe(2)
    expect(c2.start).toBe(2)
    expect(c3.start).toBe(2)
    expect([c1.subLane, c2.subLane, c3.subLane]).toEqual([0, 1, 2])
    expect(c1.subLaneCount).toBe(3)

    // Serial Bash comes in slot 3 without sublanes
    expect(bash.start).toBe(3)
    expect(bash.subLane).toBeUndefined()
  })

  it('packs overlapping timed tool spans into distinct sublanes in actual mode', () => {
    const model = deriveTrajectoryTimeline(sampleTurns, 'actual')
    expect(model).not.toBeNull()

    const c1 = model!.spans.find(s => s.index === 3)!
    const c2 = model!.spans.find(s => s.index === 4)!
    const c3 = model!.spans.find(s => s.index === 5)!
    const bash = model!.spans.find(s => s.index === 6)!

    expect(new Set([c1.subLane, c2.subLane, c3.subLane]).size).toBe(3)
    expect(c1.subLaneCount).toBe(3)
    expect(bash.subLane).toBeUndefined()
  })
})

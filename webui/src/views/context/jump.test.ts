import { describe, expect, it } from 'vitest'
import { resolveJumpSeq } from './jump'
import type { RequestRecord } from '@dsh-context/shared/types'

function request(seq: number, time: number, turn: number): RequestRecord {
  return { seq, time, turn, step: 1, system: 0, tools: 0, user: 0, inject: 0, assistant: 0, tool: 0, total: 0 }
}

const REQUESTS = [request(3, 1_000, 1), request(7, 5_000, 2), request(9, 6_500, 2), request(12, 90_000, 3)]

describe('resolveJumpSeq', () => {
  it('an explicit seq wins when a request carries it', () => {
    expect(resolveJumpSeq(REQUESTS, { seq: 7, time: 90_000, turn: 3 })).toBe(7)
  })

  it('an unknown seq falls through to the time match', () => {
    expect(resolveJumpSeq(REQUESTS, { seq: 99, time: 6_400 })).toBe(9)
  })

  it('the nearest time within the window resolves; outside the window it does not', () => {
    expect(resolveJumpSeq(REQUESTS, { time: 5_200 })).toBe(7)
    expect(resolveJumpSeq(REQUESTS, { time: 500_000 })).toBeNull()
  })

  it('the turn number pins the last request of that turn when no time matches', () => {
    expect(resolveJumpSeq(REQUESTS, { time: 500_000, turn: 2 })).toBe(9)
    expect(resolveJumpSeq(REQUESTS, { turn: 3 })).toBe(12)
    expect(resolveJumpSeq(REQUESTS, { turn: 8 })).toBeNull()
  })

  it('an empty ledger or an empty target resolves to null', () => {
    expect(resolveJumpSeq([], { seq: 1, time: 1, turn: 1 })).toBeNull()
    expect(resolveJumpSeq(REQUESTS, {})).toBeNull()
  })
})

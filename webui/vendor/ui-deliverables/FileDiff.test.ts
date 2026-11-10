import { describe, expect, it } from 'vitest'
import { hunkRows, MAX_RENDERED_LINES, renderedHunks, splitRows } from './FileDiff.tsx'
import { changesReviewAddress, isChangesDiff, isHunk } from './changes.ts'
import type { ChangesDiff, WorkspaceDiffHunk } from './changes.ts'

/**
 * The vendored comparison's pure drawing logic. The assertions are upstream's
 * own (`packages/client/ui-deliverables/tests/review-tab.client.spec.tsx`), so
 * a later alignment pass can re-sync this file against upstream's spec rather
 * than re-deriving what the numbers should be.
 */

const SESSION = 'viewed'

const text: ChangesDiff = {
  kind: 'text',
  path: 'src/app/main.ts',
  display: 'src/app/main.ts',
  before: true,
  after: true,
  coarse: false,
  hunks: [
    { oldStart: 1, oldLines: 3, newStart: 1, newLines: 4, lines: [' a', '-b', '+B', '+c', ' d'] },
    { oldStart: 10, oldLines: 1, newStart: 11, newLines: 1, lines: ['-x', '+y'] },
  ],
}

describe('the comparison address', () => {
  it('names the review tab by session and turn', () => {
    expect(changesReviewAddress({ sessionId: SESSION, turn: 2 }))
      .toBe('dsh-resource://changes-review/session/viewed/2')
  })

  it('escapes a session id a path segment cannot carry unescaped', () => {
    expect(changesReviewAddress({ sessionId: 'a b/c', turn: 1 }))
      .toBe('dsh-resource://changes-review/session/a%20b%2Fc/1')
  })
})

describe('hunkRows', () => {
  it('numbers context lines on both sides and deletions/additions on their own', () => {
    expect(hunkRows(text.hunks[0]!)).toEqual([
      { kind: 'context', old: 1, new: 1, text: 'a' },
      { kind: 'del', old: 2, new: undefined, text: 'b' },
      { kind: 'add', old: undefined, new: 2, text: 'B' },
      { kind: 'add', old: undefined, new: 3, text: 'c' },
      { kind: 'context', old: 3, new: 4, text: 'd' },
    ])
  })

  // A hunk with oldLines 0 is a pure addition: the old side never advances.
  it('does not advance a side the hunk does not cover', () => {
    const added: WorkspaceDiffHunk = {
      oldStart: 5, oldLines: 0, newStart: 6, newLines: 2, lines: ['+p', '+q'],
    }
    expect(hunkRows(added)).toEqual([
      { kind: 'add', old: undefined, new: 6, text: 'p' },
      { kind: 'add', old: undefined, new: 7, text: 'q' },
    ])
  })
})

describe('splitRows', () => {
  it('pairs a run of deletions with the run of additions that follows it', () => {
    expect(splitRows(text.hunks[0]!)).toEqual([
      { left: { no: 1, text: 'a', kind: 'context' }, right: { no: 1, text: 'a', kind: 'context' } },
      { left: { no: 2, text: 'b', kind: 'del' }, right: { no: 2, text: 'B', kind: 'add' } },
      { right: { no: 3, text: 'c', kind: 'add' } },
      { left: { no: 3, text: 'd', kind: 'context' }, right: { no: 4, text: 'd', kind: 'context' } },
    ])
  })

  // A trailing run of deletions with no additions ends the hunk on the left alone.
  it('leaves the unpairable tail on its own side', () => {
    expect(splitRows({ oldStart: 1, oldLines: 2, newStart: 1, newLines: 0, lines: ['-p', '-q'] })).toEqual([
      { left: { no: 1, text: 'p', kind: 'del' } }, { left: { no: 2, text: 'q', kind: 'del' } },
    ])
  })
})

describe('renderedHunks', () => {
  it('cuts the drawn hunks at the rendered-line cap', () => {
    const long = (count: number): string[] => Array.from({ length: count }, (_, at) => `+line ${at}`)
    const whole: WorkspaceDiffHunk = {
      oldStart: 1, oldLines: 0, newStart: 1, newLines: MAX_RENDERED_LINES + 2, lines: long(MAX_RENDERED_LINES + 2),
    }
    const cut = renderedHunks([whole])
    expect(cut.truncated).toBe(true)
    expect(cut.hunks[0]!.lines).toHaveLength(MAX_RENDERED_LINES)
    const small: WorkspaceDiffHunk = { oldStart: 1, oldLines: 1, newStart: 1, newLines: 1, lines: ['-a', '+b'] }
    expect(renderedHunks([small, small])).toEqual({ hunks: [small, small], truncated: false })
    const two = renderedHunks([{ ...whole, lines: long(MAX_RENDERED_LINES) }, small])
    expect(two.hunks).toHaveLength(1)
    expect(two.truncated).toBe(true)
  })
})

describe('the served-shape guards', () => {
  it('accepts a hunk whose lines all carry a diff prefix', () => {
    expect(isHunk({ oldStart: 1, oldLines: 1, newStart: 1, newLines: 1, lines: ['-a', '+b'] })).toBe(true)
  })

  // A line without a `+`/`-`/space prefix is not a hunk this viewer can number:
  // the prefix is what tells `hunkRows` which side a line belongs to.
  it('rejects a hunk whose line carries no diff prefix', () => {
    expect(isHunk({ oldStart: 1, oldLines: 1, newStart: 1, newLines: 1, lines: ['a'] })).toBe(false)
  })

  it('rejects a hunk with a negative or fractional offset', () => {
    const base = { oldStart: 1, oldLines: 1, newStart: 1, newLines: 1, lines: ['-a'] }
    expect(isHunk({ ...base, oldStart: -1 })).toBe(false)
    expect(isHunk({ ...base, newStart: 1.5 })).toBe(false)
  })

  it('accepts the comparison and notice shapes the gateway sends', () => {
    expect(isChangesDiff(text)).toBe(true)
    expect(isChangesDiff({ kind: 'binary', path: 'a', display: 'a' })).toBe(true)
    expect(isChangesDiff({ kind: 'oversized', path: 'a', display: 'a' })).toBe(true)
  })

  // The shape that blanked the viewer: a `text` comparison whose `hunks` is
  // absent. The guard must refuse it so the store answers `error` instead of
  // handing the drawing code a field it will `.map` over.
  it('rejects a text comparison with no hunks field', () => {
    const { hunks: _dropped, ...withoutHunks } = text as Extract<ChangesDiff, { kind: 'text' }>
    expect(isChangesDiff(withoutHunks)).toBe(false)
  })
})
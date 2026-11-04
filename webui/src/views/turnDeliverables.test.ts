import { describe, expect, it, vi } from 'vitest'
import type { ToolCallState } from '../store/conversations'
import { basename, collectTurnDeliverables, producedFileMentions } from './turnDeliverables'

/**
 * Only the two fields the collector reads are set: a full `ToolCallState`
 * carries args, result data and more that this pure layer never touches, and
 * fabricating them here would be noise.
 */
function call(
  status: ToolCallState['status'],
  locations: readonly string[] | null,
): ToolCallState {
  return { id: `c-${Math.random()}`, name: 'Write', status, locations } as unknown as ToolCallState
}

describe('collectTurnDeliverables', () => {
  it('returns nothing for a turn with no rows', () => {
    expect(collectTurnDeliverables([])).toEqual([])
    expect(collectTurnDeliverables([[], []])).toEqual([])
  })

  it('collects locations across every row of the turn, in first-seen order', () => {
    const produced = collectTurnDeliverables([
      [call('executed', ['/work/a.txt'])],
      [call('executed', ['/work/b.txt', '/work/a.txt'])],
    ])
    expect(produced).toEqual(['/work/a.txt', '/work/b.txt'])
  })

  // A failed mutation writes nothing, and an in-flight one has no result yet:
  // neither contributes a delivered file.
  it('skips calls that are not executed', () => {
    const produced = collectTurnDeliverables([
      [call('pending', ['/work/pending.txt'])],
      [call('failed', ['/work/failed.txt'])],
      [call('executed', ['/work/real.txt'])],
    ])
    expect(produced).toEqual(['/work/real.txt'])
  })

  it('tolerates a null location list', () => {
    expect(collectTurnDeliverables([[call('executed', null)]])).toEqual([])
  })
})

describe('basename', () => {
  it('takes the last segment of either separator, or the whole value', () => {
    expect(basename('/work/src/App.tsx')).toBe('App.tsx')
    expect(basename('C:\\work\\src\\App.cs')).toBe('App.cs')
    expect(basename('bare.txt')).toBe('bare.txt')
  })
})

describe('producedFileMentions', () => {
  const open = vi.fn()
  const label = (path: string): string => path

  it('resolves an exact path even when a different file shares its basename', () => {
    const { resolve } = producedFileMentions(
      ['/a/App.tsx', '/b/App.tsx'],
      open,
      label,
    )
    const mention = resolve('/a/App.tsx')
    expect(mention?.title).toBe('/a/App.tsx')
  })

  // A bare basename is only safe to link when exactly one delivered file has
  // it; with two candidates, linking either would be a guess.
  it('resolves a unique basename but stays inert on a shared one', () => {
    const unique = producedFileMentions(['/a/Only.tsx', '/b/Other.tsx'], open, label)
    expect(unique.resolve('Only.tsx')?.title).toBe('/a/Only.tsx')

    const shared = producedFileMentions(['/a/App.tsx', '/b/App.tsx'], open, label)
    expect(shared.resolve('App.tsx')).toBeUndefined()
  })

  it('resolves nothing for a path no turn delivered', () => {
    const { resolve } = producedFileMentions(['/a/App.tsx'], open, label)
    expect(resolve('/elsewhere/Nope.ts')).toBeUndefined()
  })

  it('opens the resolved path when the mention is followed', () => {
    const { resolve } = producedFileMentions(['/a/App.tsx'], open, label)
    resolve('App.tsx')?.open()
    expect(open).toHaveBeenCalledWith('/a/App.tsx')
  })
})
import { describe, expect, it } from 'vitest'
import { collectDeliverables } from './deliverables'

describe('collectDeliverables', () => {
  it('returns nothing for an empty tool call list', () => {
    expect(collectDeliverables([])).toEqual({ shown: [], overflow: 0 })
  })

  it('skips tool calls with no locations', () => {
    const result = collectDeliverables([{ locations: null }, { locations: [] }])
    expect(result).toEqual({ shown: [], overflow: 0 })
  })

  it('dedups the same path across calls, keeping first-occurrence order', () => {
    const result = collectDeliverables([
      { locations: ['/work/a.txt'] },
      { locations: ['/work/b.txt', '/work/a.txt'] },
    ])
    expect(result).toEqual({ shown: ['/work/a.txt', '/work/b.txt'], overflow: 0 })
  })

  it('collapses beyond 6 paths into an overflow count', () => {
    const paths = Array.from({ length: 8 }, (_, i) => `/work/file-${i}.txt`)
    const result = collectDeliverables([{ locations: paths }])
    expect(result.shown).toEqual(paths.slice(0, 6))
    expect(result.overflow).toBe(2)
  })
})

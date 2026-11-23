import { describe, expect, it } from 'vitest'
import type { ComposerCommandEntry } from '../api/types'
import {
  activeCommandHint,
  activeSlashToken,
  applySlashPick,
  atBreadcrumbSegments,
  plusMenuCandidates,
  sameSlashToken,
  slashMenuCandidates,
} from './slashCommand'

describe('activeSlashToken', () => {
  it('detects a bare slash at the start of the draft', () => {
    expect(activeSlashToken('/', 1)).toEqual({ start: 0, end: 1, query: '' })
  })

  it('detects a command prefix at the start of the draft', () => {
    expect(activeSlashToken('/comp', 5)).toEqual({ start: 0, end: 5, query: 'comp' })
  })

  it('allows leading horizontal whitespace before slash', () => {
    expect(activeSlashToken('  /he', 5)).toEqual({ start: 2, end: 5, query: 'he' })
  })

  it('returns null when caret is before the slash', () => {
    expect(activeSlashToken('  /he', 2)).toBeNull()
    expect(activeSlashToken('/he', 0)).toBeNull()
  })

  it('closes once whitespace follows the command token', () => {
    expect(activeSlashToken('/compact ', 9)).toBeNull()
    expect(activeSlashToken('/compact now', 12)).toBeNull()
  })

  it('stays active when caret returns inside the command token before args', () => {
    expect(activeSlashToken('/comp now', 5)).toEqual({ start: 0, end: 5, query: 'comp' })
    expect(activeSlashToken('/compact now', 4)).toEqual({ start: 0, end: 8, query: 'com' })
  })

  it('ignores slashes in the middle of prose or on later lines', () => {
    expect(activeSlashToken('check /compact', 14)).toBeNull()
    expect(activeSlashToken('http://example.com', 18)).toBeNull()
    expect(activeSlashToken('\n/compact', 9)).toBeNull()
  })

  it('returns null for out-of-range caret', () => {
    expect(activeSlashToken('/help', 99)).toBeNull()
    expect(activeSlashToken('', 0)).toBeNull()
  })
})

describe('sameSlashToken', () => {
  it('compares tokens by value', () => {
    expect(sameSlashToken(null, null)).toBe(true)
    expect(sameSlashToken({ start: 0, end: 2, query: 'h' }, { start: 0, end: 2, query: 'h' })).toBe(true)
    expect(sameSlashToken({ start: 0, end: 2, query: 'h' }, { start: 0, end: 3, query: 'he' })).toBe(false)
    expect(sameSlashToken({ start: 0, end: 2, query: 'h' }, null)).toBe(false)
  })
})

describe('applySlashPick', () => {
  it('replaces a bare slash with /name and trailing space', () => {
    const token = activeSlashToken('/', 1)!
    expect(applySlashPick('/', token, 'compact')).toEqual({
      text: '/compact ',
      caret: 9,
    })
  })

  it('replaces a partial query with /name and trailing space', () => {
    const token = activeSlashToken('/com', 4)!
    expect(applySlashPick('/com', token, 'compact')).toEqual({
      text: '/compact ',
      caret: 9,
    })
  })

  it('replaces the full token without duplicating an existing space before args', () => {
    const token = activeSlashToken('/com focus on tests', 4)!
    expect(applySlashPick('/com focus on tests', token, 'compact')).toEqual({
      text: '/compact focus on tests',
      caret: 9,
    })
  })

  it('preserves leading whitespace when present', () => {
    const token = activeSlashToken('  /he', 5)!
    expect(applySlashPick('  /he', token, 'help')).toEqual({
      text: '  /help ',
      caret: 8,
    })
  })
})

describe('activeCommandHint', () => {
  const catalogue: readonly ComposerCommandEntry[] = [
    { name: 'compact', description: 'Compact', argument_hint: '[focus instructions]', kind: 'command' },
    { name: 'clear', description: 'Clear', kind: 'command' },
  ]

  it('returns the argument hint when a command is followed by whitespace and no args yet', () => {
    expect(activeCommandHint('/compact ', catalogue)).toEqual({
      name: 'compact',
      hint: '[focus instructions]',
    })
  })

  it('returns null when arguments are already typed or no hint exists', () => {
    expect(activeCommandHint('/compact keep tests', catalogue)).toBeNull()
    expect(activeCommandHint('/clear ', catalogue)).toBeNull()
    expect(activeCommandHint('/compact', catalogue)).toBeNull()
  })
})

describe('atBreadcrumbSegments', () => {
  it('extracts directory segments from nested queries', () => {
    expect(atBreadcrumbSegments('')).toEqual([])
    expect(atBreadcrumbSegments('README.md')).toEqual([])
    expect(atBreadcrumbSegments('src/')).toEqual(['src'])
    expect(atBreadcrumbSegments('src/views/In')).toEqual(['src', 'views'])
  })
})

describe('slashMenuCandidates & plusMenuCandidates', () => {
  const catalogue: readonly ComposerCommandEntry[] = [
    { name: 'help', description: 'Show help', kind: 'command' },
    { name: 'compact', description: 'Compact conversation', argument_hint: '[instructions]', kind: 'command' },
    { name: 'clear', description: 'Clear history', kind: 'command' },
    { name: 'code-review', description: 'Review code changes', kind: 'skill' },
  ]

  it('orders pinned built-ins first and attaches localized labels for an empty slash query', () => {
    const rows = slashMenuCandidates(catalogue, '')
    expect(rows.map((r) => r.name)).toEqual(['compact', 'clear', 'help', 'code-review'])
    expect(rows[0]?.label).toBe('压缩上下文')
    expect(rows[0]?.hint).toBe('[instructions]')
    expect(rows.every((r) => r.section === '指令')).toBe(true)
    expect(rows[3]?.icon).toBeDefined()
  })

  it('ranks prefix, fuzzy subsequence, and localized Chinese label matches', () => {
    const rows = slashMenuCandidates(catalogue, 'c')
    expect(rows.map((r) => r.name)).toEqual(['compact', 'clear', 'code-review'])
    expect(rows.every((r) => r.section === undefined)).toBe(true)

    const fuzzy = slashMenuCandidates(catalogue, 'cr')
    expect(fuzzy.map((r) => r.name)).toEqual(['code-review', 'clear'])

    const chinese = slashMenuCandidates(catalogue, '压缩')
    expect(chinese.map((r) => r.name)).toEqual(['compact'])
  })

  it('returns empty list when no command matches the query', () => {
    expect(slashMenuCandidates(catalogue, 'zzz')).toEqual([])
  })

  it('prepends the File row in Add section for the + menu', () => {
    const rows = plusMenuCandidates(catalogue)
    expect(rows[0]).toMatchObject({ name: 'file', section: '添加', value: 'file' })
    expect(rows.slice(1).map((r) => r.name)).toEqual(['compact', 'clear', 'help', 'code-review'])
    expect(rows.slice(1).every((r) => r.section === '指令')).toBe(true)
  })
})

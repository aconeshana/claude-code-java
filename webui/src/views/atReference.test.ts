import { describe, expect, it } from 'vitest'
import { activeAtToken, formatMention } from './atReference'

/**
 * The backend's `AtMentionedFilesProvider.MENTION` pattern, transcribed so
 * these tests fail if the client's wire form drifts from the provider's. A
 * mismatch here means the model would receive the mention as literal text.
 */
const PROVIDER_MENTION = /(?:^|\s)@((?:[^\s\\]|\\ )+)/g

/** Paths the provider would resolve out of `text` (its own unescaping). */
function providerPaths(text: string): string[] {
  const out: string[] = []
  for (const match of text.matchAll(PROVIDER_MENTION)) {
    out.push(match[1]!.replace(/\\ /g, ' '))
  }
  return out
}

describe('activeAtToken', () => {
  it('finds a token at the start of the draft', () => {
    expect(activeAtToken('@src', 4)).toEqual({ start: 0, end: 4, query: 'src' })
  })

  it('finds a token after whitespace', () => {
    expect(activeAtToken('look at @src/foo', 16)).toEqual({
      start: 8, end: 16, query: 'src/foo',
    })
  })

  it('reports the empty query for a bare @', () => {
    expect(activeAtToken('@', 1)).toEqual({ start: 0, end: 1, query: '' })
  })

  it('refuses an @ inside another token (an email address)', () => {
    expect(activeAtToken('mail me at bob@example.com', 25)).toBeNull()
  })

  it('unescapes a backslash-escaped space', () => {
    expect(activeAtToken('@my\\ file', 9)).toEqual({
      start: 0, end: 9, query: 'my file',
    })
  })

  // The provider takes a word greedily, so a second `@` inside the same word
  // belongs to the path it resolves. Reporting only the tail would offer
  // candidates for a path the backend never reads.
  it('keeps a second @ inside the same word in the query', () => {
    expect(activeAtToken('@a.ts@b', 7)).toEqual({
      start: 0, end: 7, query: 'a.ts@b',
    })
  })

  it('closes the token at the first whitespace', () => {
    expect(activeAtToken('@src foo', 8)).toBeNull()
  })

  it('refuses a caret past the end of the text', () => {
    expect(activeAtToken('@src', 99)).toBeNull()
    expect(activeAtToken('@src', 0)).toBeNull()
  })
})

describe('formatMention', () => {
  it('inserts a plain path unchanged', () => {
    expect(formatMention('src/foo.ts')).toBe('@src/foo.ts')
  })

  it('escapes spaces so the token stays one token', () => {
    expect(formatMention('src/my file.ts')).toBe('@src/my\\ file.ts')
  })

  it('keeps a directory trailing slash', () => {
    expect(formatMention('src/')).toBe('@src/')
  })
})

describe('mention round-trip through the provider grammar', () => {
  // The whole point of the escaping rule: what the pick inserts must be what
  // the backend resolves. Reasoning about the two regexes is not enough —
  // assert the closed loop.
  const paths = [
    'src/foo.ts',
    'src/my file.ts',
    'a/b c/d e.txt',
    'README.md',
    'src/',
  ]

  it.each(paths)('resolves %s back to itself', (path) => {
    const inserted = formatMention(path)
    expect(providerPaths(inserted)).toEqual([path])
  })

  it('resolves one mention among surrounding prose', () => {
    const draft = `please look at ${formatMention('src/my file.ts')} and report`
    expect(providerPaths(draft)).toEqual(['src/my file.ts'])
  })

  it('resolves two mentions with spaces on one line', () => {
    const draft = `${formatMention('a b.txt')} then ${formatMention('c/d e.txt')}`
    expect(providerPaths(draft)).toEqual(['a b.txt', 'c/d e.txt'])
  })
})
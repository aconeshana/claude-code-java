/**
 * The composer's `@` reference grammar — the client half of the token the
 * backend's `AtMentionedFilesProvider` parses at submission time.
 *
 * The grammar is deliberately the backend's, not upstream dsh's: our
 * provider matches `(?:^|\s)@((?:[^\s\\]|\\ )+)`, so a path with spaces is
 * escaped (`@my\ file.ts`) where dsh quotes it (`@"my file"`). Writing dsh's
 * form here would insert a token the provider cannot resolve, so the mention
 * would silently reach the model as literal text. Keep the two in step: this
 * module and `AtMentionedFilesProvider` are the definition of the wire form.
 */

/** The active `@` token under the caret, or null when there is none. */
export interface AtToken {
  /** Offset of the `@` in the draft text. */
  readonly start: number
  /** Offset just past the token (the caret). */
  readonly end: number
  /** The typed query after `@`, unescaped. */
  readonly query: string
}

/**
 * The active `@` token ending at `caret`, or null.
 *
 * The scan matches the provider's own forward pass exactly, which is a
 * stricter rule than "the nearest `@`": the provider anchors on a word start
 * (`(?:^|\s)@`) and then takes every non-space, non-backslash character
 * greedily, so in `@a.ts@b` the path it resolves is `a.ts@b` — not `b`. A
 * client that reported `b` would offer candidates for a path the backend
 * never reads. So: find the word holding the caret, and the token is active
 * only when that word *begins* with `@`; the query is everything after it.
 *
 * An `@` inside a word (an email address) is not a reference, and a
 * backslash-escaped space does not end the word, which is what keeps
 * `@my\ file.ts` one token.
 */
export function activeAtToken(text: string, caret: number): AtToken | null {
  if (caret <= 0 || caret > text.length) return null
  // Walk back to the start of the whitespace-delimited word holding the
  // caret; an escaped space does not delimit.
  let start = caret
  while (start > 0) {
    const previous = text[start - 1]!
    if (/\s/.test(previous)) {
      if (start >= 2 && text[start - 2] === '\\') {
        start -= 2
        continue
      }
      break
    }
    start--
  }
  if (text[start] !== '@') return null
  // The word must not run past the caret into a following `@`-word's space —
  // the provider stops at whitespace, and so do we; the query is the word's
  // remainder up to the caret.
  return { start, end: caret, query: unescapeQuery(text.slice(start + 1, caret)) }
}

/** `@my\ file.ts` → `my file.ts` (the provider's own unescaping rule). */
function unescapeQuery(raw: string): string {
  return raw.replace(/\\ /g, ' ')
}

/**
 * Format a picked candidate as the mention text the draft receives.
 *
 * Escapes whitespace the way the provider's grammar expects, so the token
 * round-trips: a pick of `src/my file.ts` inserts `@src/my\ file.ts`, which
 * the provider unescapes back to the path it resolved.
 */
export function formatMention(path: string): string {
  return `@${path.replace(/ /g, '\\ ')}`
}
import { describe, expect, it } from 'vitest'
import { SUBAGENT_NS, subagentDicts, subagentEn, subagentZh } from './subagent'

/**
 * The subagent namespace is fed to `useTranslate` as a pair of dictionaries,
 * and the runtime falls back to zh for any key the active locale is missing —
 * silently. So a key present only in zh renders Chinese text on an English
 * page and nothing in the type checker catches it, because the en dictionary
 * is typed as `Record<keyof typeof subagentZh, string>`, which a *missing* key
 * would flag but a *stale* one would not.
 */
describe('subagent dictionary parity', () => {
  it('carries the same keys in both locales', () => {
    expect(Object.keys(subagentEn).sort()).toEqual(Object.keys(subagentZh).sort())
  })

  it('leaves no key empty in either locale', () => {
    for (const [key, value] of Object.entries(subagentZh)) {
      expect(value, `zh.${key}`).not.toBe('')
    }
    for (const [key, value] of Object.entries(subagentEn)) {
      expect(value, `en.${key}`).not.toBe('')
    }
  })

  it('keeps the same placeholders in both locales', () => {
    // A `{count}` dropped from the en copy renders the bare word with no
    // number, which reads as a bug rather than a translation.
    const holes = (text: string): string[] => (text.match(/\{[a-zA-Z]+\}/g) ?? []).sort()
    for (const key of Object.keys(subagentZh) as (keyof typeof subagentZh)[]) {
      expect(holes(subagentEn[key]), `placeholders of ${key}`).toEqual(holes(subagentZh[key]))
    }
  })

  it('registers both locales under the namespace', () => {
    expect(SUBAGENT_NS).toBe('subagent')
    expect(subagentDicts.zh).toBe(subagentZh)
    expect(subagentDicts.en).toBe(subagentEn)
  })
})
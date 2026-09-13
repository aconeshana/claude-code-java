import { describe, expect, it } from 'vitest'
import { BASE_REGISTRY, translate, type NamespaceRegistry } from './runtime'
import { COMMON_NS } from './dictionaries/common'

describe('translate', () => {
  const ns = 'demo'
  const registry: NamespaceRegistry = new Map([
    ...BASE_REGISTRY,
    [ns, { zh: { greet: '你好, {name}', 'only.zh': '仅中文' }, en: { greet: 'Hello, {name}' } }],
  ])

  it('hits the active locale within the entry namespace', () => {
    expect(translate(registry, ns, 'en', 'greet')).toBe('Hello, {name}')
  })

  it('falls back to the namespace zh dict when the active locale is missing the key', () => {
    expect(translate(registry, ns, 'en', 'only.zh')).toBe('仅中文')
  })

  it('falls back to the common namespace when the entry namespace has no dict for the key', () => {
    expect(translate(registry, ns, 'en', 'ok')).toBe('OK')
  })

  it('falls back to the common namespace zh dict as a last resort', () => {
    const partial: NamespaceRegistry = new Map([...BASE_REGISTRY, [ns, { zh: {}, en: {} }]])
    expect(translate(partial, ns, 'en', 'ok')).toBe('OK')
  })

  it('falls back to the key itself when nothing matches anywhere', () => {
    expect(translate(registry, ns, 'en', 'totally.missing')).toBe('totally.missing')
  })

  it('does not double-fall-back when the namespace already is common', () => {
    expect(translate(registry, COMMON_NS, 'en', 'totally.missing')).toBe('totally.missing')
  })

  it('interpolates {name}-style placeholders from params', () => {
    expect(translate(registry, ns, 'zh', 'greet', { name: '张三' })).toBe('你好, 张三')
  })

  it('leaves an unmatched placeholder untouched when its param is missing', () => {
    expect(translate(registry, ns, 'zh', 'greet', {})).toBe('你好, {name}')
  })
})

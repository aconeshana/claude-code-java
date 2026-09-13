import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

describe('locale store', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.resetModules()
    vi.unstubAllGlobals()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('defaults to zh when storage is empty and the browser locale is unrecognized', async () => {
    vi.stubGlobal('navigator', { ...navigator, language: 'fr-FR', languages: ['fr-FR'] })
    const { useLocale } = await import('./locale')
    expect(useLocale.getState().locale).toBe('zh')
  })

  it('detects a supported browser locale when no preference is persisted', async () => {
    vi.stubGlobal('navigator', { ...navigator, language: 'en-US', languages: ['en-US'] })
    const { useLocale } = await import('./locale')
    expect(useLocale.getState().locale).toBe('en')
  })

  it('initializes from a persisted locale, overriding browser detection', async () => {
    localStorage.setItem('webui-locale', 'en')
    vi.stubGlobal('navigator', { ...navigator, language: 'zh-CN', languages: ['zh-CN'] })
    const { useLocale } = await import('./locale')
    expect(useLocale.getState().locale).toBe('en')
  })

  it('falls back to detection for a corrupt persisted value', async () => {
    vi.stubGlobal('navigator', { ...navigator, language: 'fr-FR', languages: ['fr-FR'] })
    localStorage.setItem('webui-locale', 'fr')
    const { useLocale } = await import('./locale')
    expect(useLocale.getState().locale).toBe('zh')
  })

  it('setLocale persists and updates the store', async () => {
    vi.stubGlobal('navigator', { ...navigator, language: 'fr-FR', languages: ['fr-FR'] })
    const { useLocale } = await import('./locale')
    useLocale.getState().setLocale('en')
    expect(useLocale.getState().locale).toBe('en')
    expect(localStorage.getItem('webui-locale')).toBe('en')
  })

  it('picks up a cross-tab write via the storage event', async () => {
    vi.stubGlobal('navigator', { ...navigator, language: 'fr-FR', languages: ['fr-FR'] })
    const { useLocale } = await import('./locale')
    localStorage.setItem('webui-locale', 'en')
    window.dispatchEvent(new StorageEvent('storage', { key: 'webui-locale', newValue: 'en' }))
    expect(useLocale.getState().locale).toBe('en')
  })

  it('ignores storage events for unrelated keys', async () => {
    vi.stubGlobal('navigator', { ...navigator, language: 'fr-FR', languages: ['fr-FR'] })
    const { useLocale } = await import('./locale')
    window.dispatchEvent(new StorageEvent('storage', { key: 'unrelated-key', newValue: 'en' }))
    expect(useLocale.getState().locale).toBe('zh')
  })
})

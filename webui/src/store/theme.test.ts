import { beforeEach, describe, expect, it, vi } from 'vitest'

describe('theme store', () => {
  beforeEach(() => {
    localStorage.clear()
    document.body.removeAttribute('data-ds-dark-theme')
    vi.resetModules()
  })

  it('defaults when storage is empty', async () => {
    const { useTheme } = await import('./theme')
    expect(useTheme.getState().preference).toBe('system')
    expect(useTheme.getState().fontSize).toBe(14)
  })

  it('initializes from stored settings on first import', async () => {
    localStorage.setItem('webui-theme', JSON.stringify({ preference: 'dark', fontSize: 16 }))
    const { useTheme } = await import('./theme')
    expect(useTheme.getState().preference).toBe('dark')
    expect(useTheme.getState().fontSize).toBe(16)
  })

  it('setPreference persists and applies the dark attribute', async () => {
    const { useTheme } = await import('./theme')
    useTheme.getState().setPreference('dark')
    expect(document.body.hasAttribute('data-ds-dark-theme')).toBe(true)
    expect(JSON.parse(localStorage.getItem('webui-theme') ?? '{}').preference).toBe('dark')
  })

  it('setFontSize persists, updates the CSS variable, and keeps the current preference', async () => {
    const { useTheme } = await import('./theme')
    useTheme.getState().setPreference('dark')
    useTheme.getState().setFontSize(16)
    expect(useTheme.getState()).toMatchObject({ preference: 'dark', fontSize: 16 })
    expect(document.body.style.getPropertyValue('--dsh-content-font-size')).toBe('16px')
    expect(JSON.parse(localStorage.getItem('webui-theme') ?? '{}')).toEqual({ preference: 'dark', fontSize: 16 })
  })

  it('picks up a cross-tab write via the storage event', async () => {
    const { useTheme } = await import('./theme')
    const written = JSON.stringify({ preference: 'dark', fontSize: 16 })
    localStorage.setItem('webui-theme', written)
    window.dispatchEvent(new StorageEvent('storage', { key: 'webui-theme', newValue: written }))
    expect(useTheme.getState()).toMatchObject({ preference: 'dark', fontSize: 16 })
    expect(document.body.hasAttribute('data-ds-dark-theme')).toBe(true)
  })

  it('ignores storage events for unrelated keys', async () => {
    const { useTheme } = await import('./theme')
    window.dispatchEvent(new StorageEvent('storage', { key: 'unrelated-key', newValue: 'x' }))
    expect(useTheme.getState()).toMatchObject({ preference: 'system', fontSize: 14 })
  })
})

import { beforeEach, describe, expect, it, vi } from 'vitest'
import { DEFAULT_SETTINGS, readSettings, writeSettings } from './theme'

describe('theme settings persistence', () => {
  beforeEach(() => {
    localStorage.clear()
    document.body.removeAttribute('data-ds-dark-theme')
  })

  it('returns defaults when storage is empty', () => {
    expect(readSettings()).toEqual(DEFAULT_SETTINGS)
  })

  it('round-trips a written preference', () => {
    writeSettings({ preference: 'dark', fontSize: 16 })
    expect(readSettings()).toEqual({ preference: 'dark', fontSize: 16 })
  })

  it('applies the dark attribute and font variables to the DOM', () => {
    writeSettings({ preference: 'dark', fontSize: 15 })
    expect(document.body.hasAttribute('data-ds-dark-theme')).toBe(true)
    expect(document.body.style.getPropertyValue('--dsh-content-font-size')).toBe('15px')
    expect(document.body.style.getPropertyValue('--dsh-content-font-delta')).toBe('1px')
  })

  it('light preference clears the dark attribute', () => {
    writeSettings({ preference: 'light', fontSize: 14 })
    expect(document.body.hasAttribute('data-ds-dark-theme')).toBe(false)
    expect(document.documentElement.style.colorScheme).toBe('light')
  })

  it('clamps a corrupt font size back to the default', () => {
    localStorage.setItem('webui-theme', JSON.stringify({ preference: 'dark', fontSize: 99 }))
    expect(readSettings().fontSize).toBe(14)
  })

  it('falls back to system for an unknown preference', () => {
    localStorage.setItem('webui-theme', JSON.stringify({ preference: 'neon', fontSize: 14 }))
    expect(readSettings().preference).toBe('system')
  })

  it('keeps defaults on corrupt JSON', () => {
    const spy = vi.spyOn(Storage.prototype, 'getItem').mockReturnValue('{not json')
    expect(readSettings()).toEqual(DEFAULT_SETTINGS)
    spy.mockRestore()
  })
})

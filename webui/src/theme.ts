/**
 * Theme preference persistence (mirrors dsh ui-theme theme-settings.ts).
 *
 * The durable shape is `{preference, fontSize}` in localStorage under
 * `webui-theme`; index.html's inline bootstrap reads the same key before
 * first paint. Values and bounds match the vendored token system
 * (font 12..17px, default 14).
 */

export const THEME_PREFERENCES = ['light', 'dark', 'system'] as const

export type ThemePreference = (typeof THEME_PREFERENCES)[number]

export const FONT_SIZE_MIN = 12
export const FONT_SIZE_MAX = 17
export const DEFAULT_FONT_SIZE = 14
const STORAGE_KEY = 'webui-theme'

export interface ThemeSettings {
  preference: ThemePreference
  fontSize: number
}

export const DEFAULT_SETTINGS: ThemeSettings = {
  preference: 'system',
  fontSize: DEFAULT_FONT_SIZE,
}

function clampFontSize(value: unknown): number {
  return typeof value === 'number' && Number.isInteger(value)
    && value >= FONT_SIZE_MIN && value <= FONT_SIZE_MAX ? value : DEFAULT_FONT_SIZE
}

function readPreference(value: unknown): ThemePreference {
  return THEME_PREFERENCES.includes(value as ThemePreference)
    ? (value as ThemePreference)
    : 'system'
}

export function readSettings(): ThemeSettings {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw == null) return DEFAULT_SETTINGS
    const parsed: unknown = JSON.parse(raw)
    if (parsed == null || typeof parsed !== 'object') return DEFAULT_SETTINGS
    const record = parsed as { preference?: unknown; fontSize?: unknown }
    return {
      preference: readPreference(record.preference),
      fontSize: clampFontSize(record.fontSize),
    }
  } catch {
    return DEFAULT_SETTINGS
  }
}

/** Applies the settings to the same DOM fields index.html's bootstrap writes. */
export function applySettings(settings: ThemeSettings): void {
  const systemDark = settings.preference === 'system'
    && typeof matchMedia !== 'undefined'
    && matchMedia('(prefers-color-scheme: dark)').matches
  const dark = settings.preference === 'dark' || systemDark
  document.documentElement.style.colorScheme = dark ? 'dark' : 'light'
  document.body.toggleAttribute('data-ds-dark-theme', dark)
  document.body.style.setProperty('--dsh-content-font-size', `${settings.fontSize}px`)
  document.body.style.setProperty('--dsh-content-font-delta', `${settings.fontSize - 14}px`)
}

export function writeSettings(settings: ThemeSettings): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(settings))
  } catch { /* storage unavailable (private mode): settings stay session-only */ }
  applySettings(settings)
}

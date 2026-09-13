/**
 * Reactive wrapper around `../theme.ts`'s persisted `{preference, fontSize}`
 * pair. The pure read/write/apply functions stay in `theme.ts` (index.html's
 * inline bootstrap needs them dependency-free); this store just gives React
 * components a subscribable view and setters that persist + apply the DOM
 * side effects in one call.
 */
import { create } from 'zustand'
import { applySettings, readSettings, STORAGE_KEY, writeSettings, type ThemePreference } from '../theme'

export interface ThemeStore {
  readonly preference: ThemePreference
  readonly fontSize: number
  setPreference(next: ThemePreference): void
  setFontSize(px: number): void
}

export const useTheme = create<ThemeStore>((set, get) => ({
  ...readSettings(),

  setPreference(next) {
    const settings = { preference: next, fontSize: get().fontSize }
    writeSettings(settings)
    set(settings)
  },

  setFontSize(px) {
    const settings = { preference: get().preference, fontSize: px }
    writeSettings(settings)
    set(settings)
  },
}))

window.addEventListener('storage', (event) => {
  if (event.key !== STORAGE_KEY) return
  const settings = readSettings()
  applySettings(settings)
  useTheme.setState(settings)
})

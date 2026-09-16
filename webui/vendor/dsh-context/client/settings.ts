/**
 * The plugin's user-settings binding (browser half), vendored from
 * dsh-context `client/settings.ts`. Upstream binds a Host-served
 * `dsh-context` settings namespace through `ctx.settingsScope`;
 * claude-code-java has no such scope, so the same preference vocabulary and
 * `ContextSettings` face persist in `localStorage` (the web UI's other
 * per-browser preferences — enter behavior, theme — live there too). The
 * Context tab reads the defaults at mount; the Settings panel's Context
 * section writes them.
 */

import type { DefaultFileSort, DefaultGranularity, DefaultPlacement, DefaultToolSort, DefaultTrendMode, InsightsEntry, SettingsField } from '../shared/types'

// The preference vocabulary is declared once in shared/types.ts; re-exported
// here so client-side consumers keep their canonical import path.
export type { DefaultFileSort, DefaultGranularity, DefaultPlacement, DefaultToolSort, DefaultTrendMode, InsightsEntry, SettingsField } from '../shared/types'

/** The preference snapshot the card renders and the view reads at mount. */
export interface SettingsState {
  /** Always `ready` here: localStorage is synchronous. Kept for the card's shape. */
  status: 'loading' | 'ready' | 'unavailable'
  placement: DefaultPlacement
  granularity: DefaultGranularity
  mode: DefaultTrendMode
  toolSort: DefaultToolSort
  fileSort: DefaultFileSort
  insightsEntry: InsightsEntry
  writable: boolean
}

export interface ContextSettings {
  /** Observable snapshot store (useSyncExternalStore-shaped). */
  store: { subscribe(listener: () => void): () => void; getSnapshot(): SettingsState }
  defaultPlacement(): DefaultPlacement
  defaultGranularity(): DefaultGranularity
  defaultTrendMode(): DefaultTrendMode
  defaultToolSort(): DefaultToolSort
  defaultFileSort(): DefaultFileSort
  insightsEntry(): InsightsEntry
  /** Persist one preference choice. */
  set(field: SettingsField, value: string): void
}

type Prefs = {
  placement?: DefaultPlacement | undefined
  granularity?: DefaultGranularity | undefined
  mode?: DefaultTrendMode | undefined
  toolSort?: DefaultToolSort | undefined
  fileSort?: DefaultFileSort | undefined
  insightsEntry?: InsightsEntry | undefined
}

export function prefsOf(value: unknown): Prefs {
  if (value === null || typeof value !== 'object') return {}
  const v = value as Record<string, unknown>
  return {
    ...(v.defaultPlacement === 'all' || v.defaultPlacement === 'tab' || v.defaultPlacement === 'sidebar' ? { placement: v.defaultPlacement } : {}),
    ...(v.defaultGranularity === 'step' || v.defaultGranularity === 'turn' ? { granularity: v.defaultGranularity } : {}),
    ...(v.defaultTrendMode === 'total' || v.defaultTrendMode === 'delta' ? { mode: v.defaultTrendMode } : {}),
    ...(v.defaultToolSort === 'size' || v.defaultToolSort === 'count' || v.defaultToolSort === 'name' ? { toolSort: v.defaultToolSort } : {}),
    ...(v.defaultFileSort === 'count' || v.defaultFileSort === 'latest' || v.defaultFileSort === 'path' ? { fileSort: v.defaultFileSort } : {}),
    ...(v.insightsEntry === 'show' || v.insightsEntry === 'hide' ? { insightsEntry: v.insightsEntry } : {}),
  }
}

/** The minimal storage face (Web Storage or a test stub). */
export interface SettingsStorageLike {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
}

export const SETTINGS_STORAGE_KEY = 'dsh-context.settings'

const DEFAULTS: SettingsState = {
  status: 'ready', placement: 'all', granularity: 'step', mode: 'total', toolSort: 'count', fileSort: 'count', insightsEntry: 'show', writable: true,
}

function readStored(storage: SettingsStorageLike | undefined): Record<string, unknown> {
  if (storage === undefined) return {}
  try {
    const raw = storage.getItem(SETTINGS_STORAGE_KEY)
    if (raw === null) return {}
    const parsed: unknown = JSON.parse(raw)
    return parsed !== null && typeof parsed === 'object' ? parsed as Record<string, unknown> : {}
  } catch {
    return {}
  }
}

export function createContextSettings(storage: SettingsStorageLike | undefined = defaultStorage()): ContextSettings {
  const stored = readStored(storage)
  const prefs = prefsOf(stored)
  let state: SettingsState = {
    ...DEFAULTS,
    ...(prefs.placement !== undefined ? { placement: prefs.placement } : {}),
    ...(prefs.granularity !== undefined ? { granularity: prefs.granularity } : {}),
    ...(prefs.mode !== undefined ? { mode: prefs.mode } : {}),
    ...(prefs.toolSort !== undefined ? { toolSort: prefs.toolSort } : {}),
    ...(prefs.fileSort !== undefined ? { fileSort: prefs.fileSort } : {}),
    ...(prefs.insightsEntry !== undefined ? { insightsEntry: prefs.insightsEntry } : {}),
    writable: storage !== undefined,
  }
  const listeners = new Set<() => void>()
  const publish = (next: SettingsState): void => {
    if (next.status === state.status && next.placement === state.placement && next.granularity === state.granularity
      && next.mode === state.mode && next.toolSort === state.toolSort && next.fileSort === state.fileSort
      && next.insightsEntry === state.insightsEntry && next.writable === state.writable) return
    state = next
    for (const listener of listeners) listener()
  }
  return {
    store: {
      subscribe(listener) {
        listeners.add(listener)
        return () => { listeners.delete(listener) }
      },
      getSnapshot: () => state,
    },
    defaultPlacement: () => state.placement,
    defaultGranularity: () => state.granularity,
    defaultTrendMode: () => state.mode,
    defaultToolSort: () => state.toolSort,
    defaultFileSort: () => state.fileSort,
    insightsEntry: () => state.insightsEntry,
    set(field, value) {
      const next = prefsOf({ [field]: value })
      if (Object.keys(next).length === 0) return
      publish({
        ...state,
        ...(next.placement !== undefined ? { placement: next.placement } : {}),
        ...(next.granularity !== undefined ? { granularity: next.granularity } : {}),
        ...(next.mode !== undefined ? { mode: next.mode } : {}),
        ...(next.toolSort !== undefined ? { toolSort: next.toolSort } : {}),
        ...(next.fileSort !== undefined ? { fileSort: next.fileSort } : {}),
        ...(next.insightsEntry !== undefined ? { insightsEntry: next.insightsEntry } : {}),
      })
      stored[field] = value
      try {
        storage?.setItem(SETTINGS_STORAGE_KEY, JSON.stringify(stored))
      } catch { /* quota / private mode: the in-memory echo still holds for this page */ }
    },
  }
}

function defaultStorage(): SettingsStorageLike | undefined {
  try {
    return typeof localStorage === 'undefined' ? undefined : localStorage
  } catch {
    return undefined
  }
}

/** The page-lifetime settings instance shared by the tab, the modal, and the Settings panel. */
export const contextSettings: ContextSettings = createContextSettings()

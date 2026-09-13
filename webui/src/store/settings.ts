import { create } from 'zustand'
import {
  addAdditionalDirectories,
  fetchSettingsSnapshot,
  removeAdditionalDirectories,
  replacePermissionRules,
  writePermissionMode,
  writeUserSettingValue,
} from '../api/client'
import type {
  PermissionBehaviorKind,
  SettingsSnapshot,
  SettingsSource,
  SettingsTier,
} from '../api/types'

/**
 * Settings panel state: the effective (merged, tier-attributed) snapshot plus
 * one mutation method per gateway op. Every mutation's response is the
 * refreshed snapshot itself, so a successful write updates the view without a
 * second fetch.
 */

export interface SettingsStore {
  readonly effective: Readonly<Record<string, unknown>> | null
  readonly sources: readonly SettingsSource[]
  readonly loading: boolean
  readonly error: string | null
  refresh(): Promise<void>
  writeUserValue(key: string, value: string | boolean | null): Promise<void>
  writePermissionMode(mode: string | null, tier: SettingsTier): Promise<void>
  replacePermissionRules(
    behavior: PermissionBehaviorKind,
    rules: readonly string[],
    tier: SettingsTier,
  ): Promise<void>
  addDirectories(directories: readonly string[], tier: SettingsTier): Promise<void>
  removeDirectories(directories: readonly string[], tier: SettingsTier): Promise<void>
}

export const useSettings = create<SettingsStore>((set) => ({
  effective: null,
  sources: [],
  loading: false,
  error: null,

  async refresh() {
    set({ loading: true })
    try {
      const snapshot = await fetchSettingsSnapshot()
      applySnapshot(set, snapshot)
    } catch (failure) {
      set({ loading: false, error: messageOf(failure) })
    }
  },

  async writeUserValue(key, value) {
    await mutate(set, () => writeUserSettingValue(key, value))
  },

  async writePermissionMode(mode, tier) {
    await mutate(set, () => writePermissionMode(mode, tier))
  },

  async replacePermissionRules(behavior, rules, tier) {
    await mutate(set, () => replacePermissionRules(behavior, rules, tier))
  },

  async addDirectories(directories, tier) {
    await mutate(set, () => addAdditionalDirectories(directories, tier))
  },

  async removeDirectories(directories, tier) {
    await mutate(set, () => removeAdditionalDirectories(directories, tier))
  },
}))

/** Runs one mutation op; success applies the refreshed snapshot it answers with. */
async function mutate(
  set: (partial: Partial<SettingsStore>) => void,
  op: () => Promise<SettingsSnapshot>,
): Promise<void> {
  try {
    applySnapshot(set, await op())
  } catch (failure) {
    set({ error: messageOf(failure) })
  }
}

function applySnapshot(
  set: (partial: Partial<SettingsStore>) => void,
  snapshot: SettingsSnapshot,
): void {
  set({
    effective: snapshot.effective,
    sources: snapshot.sources,
    loading: false,
    error: null,
  })
}

function messageOf(failure: unknown): string {
  return failure instanceof Error ? failure.message : String(failure)
}

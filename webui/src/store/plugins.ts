import { create } from 'zustand'
import {
  addPluginMarketplace,
  fetchPluginsSnapshot,
  installMarketplacePlugin,
  reloadPluginsRuntime,
  removePluginMarketplace,
  saveInstalledPluginOptions,
  setPluginMarketplaceAutoUpdate,
  toggleInstalledPlugin,
  uninstallInstalledPlugin,
  updateInstalledPlugin,
  updatePluginMarketplace,
} from '../api/client'
import type { PluginInstallScope, PluginsSnapshot } from '../api/types'
import { useComposerCommands } from './commands'
import { useSettings } from './settings'

interface PluginsState {
  snapshot: PluginsSnapshot | null
  loading: boolean
  busyKey: string | null
  error: string | null
  requestedPluginPanelId: string | null
  openPluginSettingsTab: (pluginId: string) => void
  clearRequestedPluginPanel: () => void
  refresh: () => Promise<void>
  reloadRuntime: () => Promise<void>
  addMarketplace: (source: string) => Promise<boolean>
  updateMarketplace: (name?: string) => Promise<boolean>
  removeMarketplace: (name: string) => Promise<boolean>
  setAutoUpdate: (name: string, autoUpdate: boolean) => Promise<boolean>
  installPlugin: (pluginName: string, marketplaceName: string, scope: PluginInstallScope) => Promise<boolean>
  togglePlugin: (pluginId: string, enabled: boolean, scope: PluginInstallScope) => Promise<boolean>
  updatePlugin: (pluginId: string, scope: PluginInstallScope) => Promise<boolean>
  uninstallPlugin: (pluginId: string, scope: PluginInstallScope, deleteData?: boolean) => Promise<boolean>
  saveOptions: (pluginId: string, values: Readonly<Record<string, unknown>>) => Promise<boolean>
}

function syncSiblingStores() {
  void useComposerCommands.getState().refresh()
  void useSettings.getState().refresh()
}

export const usePlugins = create<PluginsState>((set) => {
  async function mutate(key: string, op: () => Promise<PluginsSnapshot>): Promise<boolean> {
    set({ busyKey: key, error: null })
    try {
      const snapshot = await op()
      set({ snapshot, busyKey: null, error: null })
      syncSiblingStores()
      return true
    } catch (failure) {
      set({
        busyKey: null,
        error: failure instanceof Error ? failure.message : String(failure),
      })
      return false
    }
  }

  return {
    snapshot: null,
    loading: false,
    busyKey: null,
    error: null,
    requestedPluginPanelId: null,

    openPluginSettingsTab(pluginId) {
      set({ requestedPluginPanelId: pluginId })
    },

    clearRequestedPluginPanel() {
      set({ requestedPluginPanelId: null })
    },

    async refresh() {
      set({ loading: true, error: null })
      try {
        const snapshot = await fetchPluginsSnapshot()
        set({ snapshot, loading: false, error: null })
      } catch (failure) {
        set({
          loading: false,
          error: failure instanceof Error ? failure.message : String(failure),
        })
      }
    },

    async reloadRuntime() {
      await mutate('reload', () => reloadPluginsRuntime())
    },

    async addMarketplace(source: string) {
      return mutate(`mkt:add:${source}`, () => addPluginMarketplace(source))
    },

    async updateMarketplace(name?: string) {
      return mutate(`mkt:update:${name ?? 'all'}`, () => updatePluginMarketplace(name))
    },

    async removeMarketplace(name: string) {
      return mutate(`mkt:remove:${name}`, () => removePluginMarketplace(name))
    },

    async setAutoUpdate(name: string, autoUpdate: boolean) {
      return mutate(`mkt:auto:${name}`, () => setPluginMarketplaceAutoUpdate(name, autoUpdate))
    },

    async installPlugin(pluginName: string, marketplaceName: string, scope: PluginInstallScope) {
      return mutate(`plugin:install:${pluginName}@${marketplaceName}`, () =>
        installMarketplacePlugin({ pluginName, marketplaceName, scope }))
    },

    async togglePlugin(pluginId: string, enabled: boolean, scope: PluginInstallScope) {
      return mutate(`plugin:toggle:${pluginId}`, () =>
        toggleInstalledPlugin({ pluginId, enabled, scope }))
    },

    async updatePlugin(pluginId: string, scope: PluginInstallScope) {
      return mutate(`plugin:update:${pluginId}`, () =>
        updateInstalledPlugin({ pluginId, scope }))
    },

    async uninstallPlugin(pluginId: string, scope: PluginInstallScope, deleteData = false) {
      return mutate(`plugin:uninstall:${pluginId}`, () =>
        uninstallInstalledPlugin({ pluginId, scope, deleteData }))
    },

    async saveOptions(pluginId: string, values: Readonly<Record<string, unknown>>) {
      return mutate(`plugin:options:${pluginId}`, () =>
        saveInstalledPluginOptions({ pluginId, values }))
    },
  }
})

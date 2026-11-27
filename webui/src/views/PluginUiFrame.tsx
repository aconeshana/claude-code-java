import { useEffect, useState } from 'react'
import { currentToken } from '../api/token'
import type { PluginUiPanel } from '../api/types'
import { useModels } from '../store/models'
import { usePlugins } from '../store/plugins'
import { useLocale } from '../store/locale'
import localCss from './SettingsPanel.module.css'

/**
 * Frameless sandboxed iframe host for plugin-declared Settings pages
 * ({@code /api/plugins/{pluginId}/ui/**} when the plugin opts in with
 * {@code ui.settingsTab: true}). Renders the plugin's own page directly with
 * no redundant chrome when running; only displays status feedback while
 * starting or on error.
 */
export function PluginUiFrame({ panel, height = 'min(660px, 76vh)' }: {
  panel: PluginUiPanel
  height?: string
}) {
  const [reloadNonce, setReloadNonce] = useState(0)
  const refreshModels = useModels((state) => state.refresh)
  const refreshPlugins = usePlugins((state) => state.refresh)
  const locale = useLocale((state) => state.locale)

  useEffect(() => {
    function onMessage(event: MessageEvent) {
      if (event.origin !== window.location.origin) return
      const payload = event.data as { type?: string } | null
      if (payload?.type === 'poco:models-changed') {
        void refreshModels()
      } else if (payload?.type === 'poco:plugins-changed') {
        void refreshPlugins()
        void refreshModels()
      }
    }
    window.addEventListener('message', onMessage)
    return () => { window.removeEventListener('message', onMessage) }
  }, [refreshModels, refreshPlugins])

  const token = currentToken()
  const params = new URLSearchParams()
  if (token) params.set('token', token)
  if (locale) params.set('lang', locale)
  if (reloadNonce > 0) params.set('_r', String(reloadNonce))
  const qs = params.toString()
  const src = qs ? `${panel.url}${panel.url.includes('?') ? '&' : '?'}${qs}` : panel.url

  if (panel.status !== 'running') {
    return (
      <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
        <div className={localCss.error} role="status">
          {panel.status === 'starting'
            ? '插件服务正在启动中…'
            : `插件服务未就绪（${panel.status}）${panel.error ? `：${panel.error}` : ''}`}
        </div>
        <div>
          <button
            type="button"
            className={localCss.button}
            onClick={() => {
              setReloadNonce((n) => n + 1)
              void refreshPlugins()
              void refreshModels()
            }}
          >
            重试
          </button>
        </div>
      </div>
    )
  }

  return (
    <iframe
      key={`${panel.plugin_id}:${reloadNonce}`}
      src={src}
      title={panel.title || panel.plugin_id}
      style={{
        width: '100%',
        height,
        border: 'none',
        borderRadius: 10,
        background: 'transparent',
        display: 'block',
      }}
    />
  )
}

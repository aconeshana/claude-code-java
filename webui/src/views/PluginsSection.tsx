import { useEffect, useMemo, useState } from 'react'
import { Button, Input, Pill, Switch } from '@primitives'
import { usePlugins } from '../store/plugins'
import { toast } from '../store/toast'
import type { InstalledPluginItem, MarketplaceCatalogPlugin, PluginInstallScope } from '../api/types'
import { MenuSelect, SettingsRow } from './SettingsPanel'
import localCss from './SettingsPanel.module.css'
import cardCss from './ModelsSection.module.css'

const SCOPE_OPTIONS: readonly { id: PluginInstallScope; label: string }[] = [
  { id: 'user', label: 'user（全局）' },
  { id: 'project', label: 'project（项目共享）' },
  { id: 'local', label: 'local（仅本机项目）' },
]

function primaryScopeOf(plugin: InstalledPluginItem): PluginInstallScope {
  const raw = plugin.installations[0]?.scope
  if (raw === 'project' || raw === 'local') return raw
  return 'user'
}

export function PluginsSection() {
  const snapshot = usePlugins((state) => state.snapshot)
  const loading = usePlugins((state) => state.loading)
  const busyKey = usePlugins((state) => state.busyKey)
  const error = usePlugins((state) => state.error)
  const refresh = usePlugins((state) => state.refresh)
  const reloadRuntime = usePlugins((state) => state.reloadRuntime)
  const addMarketplace = usePlugins((state) => state.addMarketplace)
  const updateMarketplace = usePlugins((state) => state.updateMarketplace)
  const removeMarketplace = usePlugins((state) => state.removeMarketplace)
  const setAutoUpdate = usePlugins((state) => state.setAutoUpdate)
  const installPlugin = usePlugins((state) => state.installPlugin)
  const togglePlugin = usePlugins((state) => state.togglePlugin)
  const updatePlugin = usePlugins((state) => state.updatePlugin)
  const uninstallPlugin = usePlugins((state) => state.uninstallPlugin)
  const saveOptions = usePlugins((state) => state.saveOptions)

  const [marketplaceInput, setMarketplaceInput] = useState('')
  const [installScope, setInstallScope] = useState<PluginInstallScope>('user')
  const [configuringId, setConfiguringId] = useState<string | null>(null)
  const [confirmUninstallId, setConfirmUninstallId] = useState<string | null>(null)

  useEffect(() => {
    void refresh()
  }, [refresh])

  const installed = snapshot?.installed ?? []
  const marketplaces = snapshot?.marketplaces ?? []
  const diagnostics = snapshot?.errors ?? []
  const summary = snapshot?.summary
  const anyBusy = busyKey != null

  const installedIds = useMemo(
    () => new Set(installed.map((item) => item.plugin_id)),
    [installed],
  )

  const catalogEntries = useMemo(() => {
    const rows: MarketplaceCatalogPlugin[] = []
    for (const mkt of marketplaces) {
      for (const entry of mkt.plugins) {
        rows.push(entry)
      }
    }
    return rows
  }, [marketplaces])

  async function handleAddMarketplace() {
    const trimmed = marketplaceInput.trim()
    if (trimmed === '') return
    const ok = await addMarketplace(trimmed)
    if (ok) {
      setMarketplaceInput('')
      toast.success(`已添加插件市场：${trimmed}`)
    }
  }

  return (
    <>
      <p className={cardCss.intro}>
        与 TUI <code>/plugin</code> 及 <code>claude plugin</code> 命令行共享同一份持久化插件目录（<code>~/.claude/plugins</code>）。安装或启停插件后会原地热刷新技能、斜杠命令、子代理与 MCP 服务。
      </p>

      {error != null && <div className={localCss.error} role="alert">{error}</div>}

      {diagnostics.length > 0 && (
        <div className={localCss.error} role="status">
          {diagnostics.map((diag, idx) => (
            <div key={`${diag.source}-${idx}`}>
              <strong>{diag.source || 'plugin'}：</strong>
              {diag.message}
              {diag.guidance ? `（${diag.guidance}）` : ''}
            </div>
          ))}
        </div>
      )}

      <div className={cardCss.card}>
        <SettingsRow
          title="插件运行时状态"
          description={
            summary == null
              ? (loading ? '正在读取插件状态…' : '尚未加载插件状态')
              : `已加载 ${summary.skill_count} 个技能 · ${summary.command_count} 个斜杠命令 · ${summary.agent_count} 个子代理 · ${summary.mcp_count} 个 MCP 服务`
          }
        >
          <div className={localCss.inline}>
            {marketplaces.length > 0 && (
              <Button
                variant="outline"
                size="sm"
                disabled={anyBusy}
                onClick={() => {
                  void updateMarketplace().then((ok) => {
                    if (ok) toast.success('已同步全部插件市场')
                  })
                }}
              >
                {busyKey === 'mkt:update:all' ? '同步中…' : '同步全部市场'}
              </Button>
            )}
            <Button
              variant="outline"
              size="sm"
              disabled={anyBusy}
              onClick={() => {
                void reloadRuntime().then(() => { toast.success('已热重载插件运行时') })
              }}
            >
              {busyKey === 'reload' ? '重载中…' : '热重载插件'}
            </Button>
          </div>
        </SettingsRow>
      </div>

      <div className={localCss.ruleGroup}>
        <div className={localCss.ruleGroupTitle}>已安装插件（{installed.length}）</div>
        <ul className={cardCss.entryList}>
          {installed.length === 0 && (
            <li className={localCss.empty}>（尚未安装插件，可先在下方添加插件市场后一键安装）</li>
          )}
          {installed.map((plugin) => {
            const scope = primaryScopeOf(plugin)
            const hasConfig = Object.keys(plugin.user_config).length > 0
            const isConfiguring = configuringId === plugin.plugin_id
            const isConfirmingUninstall = confirmUninstallId === plugin.plugin_id
            return (
              <li key={plugin.plugin_id} className={cardCss.card}>
                <div
                  className={cardCss.cardHeader}
                  style={{ flexWrap: 'wrap', alignItems: 'flex-start', marginBottom: isConfiguring ? 12 : 0 }}
                >
                  <div className={cardCss.entryInfo} style={{ minWidth: 200 }}>
                    <div className={cardCss.entryName} style={{ flexWrap: 'wrap' }}>
                      <span>{plugin.name}</span>
                      <Pill>{plugin.plugin_id}</Pill>
                      {plugin.version !== '' && <Pill>{`v${plugin.version}`}</Pill>}
                      <Pill>{`scope: ${scope}`}</Pill>
                    </div>
                    <div
                      className={cardCss.entryMeta}
                      style={{ whiteSpace: 'normal', marginTop: 6, lineHeight: 1.45 }}
                    >
                      {plugin.description || plugin.plugin_id}
                    </div>
                  </div>
                  <div className={localCss.inline} style={{ flexShrink: 0, flexWrap: 'wrap' }}>
                    <Switch
                      checked={plugin.enabled}
                      disabled={anyBusy}
                      label={`启用 ${plugin.plugin_id}`}
                      onChange={(next) => {
                        void togglePlugin(plugin.plugin_id, next, scope).then((ok) => {
                          if (ok) toast.success(`${next ? '已启用' : '已禁用'} ${plugin.name}`)
                        })
                      }}
                    />
                    {hasConfig && (
                      <button
                        type="button"
                        className={localCss.button}
                        disabled={anyBusy}
                        onClick={() => { setConfiguringId(isConfiguring ? null : plugin.plugin_id) }}
                      >
                        {isConfiguring ? '收起配置' : '选项'}
                      </button>
                    )}
                    <button
                      type="button"
                      className={localCss.button}
                      disabled={anyBusy}
                      onClick={() => {
                        void updatePlugin(plugin.plugin_id, scope).then((ok) => {
                          if (ok) toast.success(`已检查并更新 ${plugin.name}`)
                        })
                      }}
                    >
                      {busyKey === `plugin:update:${plugin.plugin_id}` ? '更新中…' : '更新'}
                    </button>
                    {!isConfirmingUninstall ? (
                      <button
                        type="button"
                        className={`${localCss.button} ${cardCss.dangerButton}`}
                        disabled={anyBusy}
                        onClick={() => { setConfirmUninstallId(plugin.plugin_id) }}
                      >
                        卸载
                      </button>
                    ) : (
                      <>
                        <button
                          type="button"
                          className={`${localCss.button} ${cardCss.dangerButton}`}
                          disabled={anyBusy}
                          onClick={() => {
                            void uninstallPlugin(plugin.plugin_id, scope).then((ok) => {
                              if (ok) {
                                setConfirmUninstallId(null)
                                toast.success(`已卸载 ${plugin.name}`)
                              }
                            })
                          }}
                        >
                          确认卸载
                        </button>
                        <button
                          type="button"
                          className={localCss.button}
                          disabled={anyBusy}
                          onClick={() => { setConfirmUninstallId(null) }}
                        >
                          取消
                        </button>
                      </>
                    )}
                  </div>
                </div>
                {isConfiguring && (
                  <PluginOptionsEditor
                    plugin={plugin}
                    busy={anyBusy}
                    onSave={async (values) => {
                      const ok = await saveOptions(plugin.plugin_id, values)
                      if (ok) {
                        setConfiguringId(null)
                        toast.success(`已保存 ${plugin.name} 配置`)
                      }
                    }}
                    onCancel={() => { setConfiguringId(null) }}
                  />
                )}
              </li>
            )
          })}
        </ul>
      </div>

      <div className={localCss.ruleGroup}>
        <div className={localCss.ruleGroupTitle}>发现可安装插件（{catalogEntries.length}）</div>
        {catalogEntries.length > 0 && (
          <SettingsRow title="新插件默认安装层级">
            <MenuSelect
              value={installScope}
              label={SCOPE_OPTIONS.find((opt) => opt.id === installScope)?.label ?? installScope}
              options={SCOPE_OPTIONS}
              onChange={(id) => { setInstallScope(id as PluginInstallScope) }}
              ariaLabel="plugin install scope"
            />
          </SettingsRow>
        )}
        <ul className={cardCss.entryList}>
          {catalogEntries.length === 0 && (
            <li className={localCss.empty}>（暂无市场目录条目，请先在下方添加插件市场，如 aconeshana/poco-plugins）</li>
          )}
          {catalogEntries.map((entry) => {
            const alreadyInstalled = installedIds.has(entry.plugin_id)
            const installingThis = busyKey === `plugin:install:${entry.plugin_id}`
            return (
              <li key={entry.plugin_id} className={cardCss.entryCard}>
                <div className={cardCss.entryInfo}>
                  <div className={cardCss.entryName}>
                    {entry.name}
                    <Pill>{`@${entry.marketplace}`}</Pill>
                    {entry.version !== '' && <Pill>{`v${entry.version}`}</Pill>}
                    {entry.category !== '' && <Pill>{entry.category}</Pill>}
                  </div>
                  <div className={cardCss.entryMeta}>{entry.description || entry.plugin_id}</div>
                </div>
                {alreadyInstalled ? (
                  <Pill>已安装</Pill>
                ) : (
                  <button
                    type="button"
                    className={localCss.button}
                    disabled={anyBusy}
                    onClick={() => {
                      void installPlugin(entry.name, entry.marketplace, installScope).then((ok) => {
                        if (ok) toast.success(`已安装并启用 ${entry.plugin_id}`)
                      })
                    }}
                  >
                    {installingThis ? '安装中…' : '安装'}
                  </button>
                )}
              </li>
            )
          })}
        </ul>
      </div>

      <div className={localCss.ruleGroup}>
        <div className={localCss.ruleGroupTitle}>插件市场源（{marketplaces.length}）</div>
        <div className={localCss.inline}>
          <Input
            className={localCss.grow}
            value={marketplaceInput}
            placeholder="输入 GitHub 仓库（如 aconeshana/poco-plugins）、URL 或本地路径"
            aria-label="plugin marketplace source"
            onChange={(event) => { setMarketplaceInput(event.target.value) }}
            onKeyDown={(event) => {
              if (event.key === 'Enter' && marketplaceInput.trim() !== '' && !anyBusy) {
                event.preventDefault()
                void handleAddMarketplace()
              }
            }}
          />
          <button
            type="button"
            className={localCss.button}
            disabled={anyBusy || marketplaceInput.trim() === ''}
            onClick={() => { void handleAddMarketplace() }}
          >
            {busyKey?.startsWith('mkt:add:') ? '添加中…' : '添加市场'}
          </button>
        </div>

        <ul className={cardCss.entryList} style={{ marginTop: 8 }}>
          {marketplaces.length === 0 && (
            <li className={localCss.empty}>（尚未注册任何插件市场）</li>
          )}
          {marketplaces.map((mkt) => (
            <li key={mkt.name} className={cardCss.entryCard}>
              <div className={cardCss.entryInfo}>
                <div className={cardCss.entryName}>
                  {mkt.name}
                  <Pill>{`${mkt.plugins.length} 个插件`}</Pill>
                </div>
                <div className={cardCss.entryMeta} title={mkt.source}>{mkt.source}</div>
              </div>
              <div className={localCss.inline}>
                <Switch
                  checked={mkt.auto_update}
                  disabled={anyBusy}
                  label={`自动更新 ${mkt.name}`}
                  onChange={(next) => { void setAutoUpdate(mkt.name, next) }}
                />
                <button
                  type="button"
                  className={localCss.button}
                  disabled={anyBusy}
                  onClick={() => {
                    void updateMarketplace(mkt.name).then((ok) => {
                      if (ok) toast.success(`已刷新市场 ${mkt.name}`)
                    })
                  }}
                >
                  {busyKey === `mkt:update:${mkt.name}` ? '刷新中…' : '刷新'}
                </button>
                <button
                  type="button"
                  className={`${localCss.button} ${cardCss.dangerButton}`}
                  disabled={anyBusy}
                  onClick={() => {
                    void removeMarketplace(mkt.name).then((ok) => {
                      if (ok) toast.success(`已移除市场 ${mkt.name}`)
                    })
                  }}
                >
                  移除
                </button>
              </div>
            </li>
          ))}
        </ul>
      </div>
    </>
  )
}

function PluginOptionsEditor({
  plugin,
  busy,
  onSave,
  onCancel,
}: {
  plugin: InstalledPluginItem
  busy: boolean
  onSave: (values: Record<string, unknown>) => Promise<void>
  onCancel: () => void
}) {
  const [draft, setDraft] = useState<Record<string, string | boolean>>(() => {
    const initial: Record<string, string | boolean> = {}
    for (const [key, field] of Object.entries(plugin.user_config)) {
      const existing = plugin.options[key]
      if (field.type === 'boolean') {
        initial[key] = typeof existing === 'boolean' ? existing : Boolean(field.default)
      } else if (field.sensitive) {
        initial[key] = ''
      } else if (existing != null) {
        initial[key] = String(existing)
      } else if (field.default != null) {
        initial[key] = String(field.default)
      } else {
        initial[key] = ''
      }
    }
    return initial
  })

  function handleSubmit() {
    const out: Record<string, unknown> = {}
    for (const [key, field] of Object.entries(plugin.user_config)) {
      const val = draft[key]
      if (field.type === 'boolean') {
        out[key] = Boolean(val)
      } else if (field.type === 'number') {
        const text = String(val ?? '').trim()
        if (text !== '') {
          const parsed = Number(text)
          if (!Number.isNaN(parsed)) out[key] = parsed
        }
      } else {
        const text = String(val ?? '')
        if (field.sensitive && text.trim() === '') continue
        out[key] = text
      }
    }
    void onSave(out)
  }

  return (
    <div style={{ marginTop: 12, paddingTop: 12, borderTop: '1px solid var(--dsw-alias-border-def)' }}>
      {Object.entries(plugin.user_config).map(([key, field]) => (
        <SettingsRow key={key} title={field.title || key} description={field.description}>
          {field.type === 'boolean' ? (
            <Switch
              checked={Boolean(draft[key])}
              disabled={busy}
              label={field.title || key}
              onChange={(next) => { setDraft((prev) => ({ ...prev, [key]: next })) }}
            />
          ) : (
            <Input
              type={field.sensitive ? 'password' : 'text'}
              value={String(draft[key] ?? '')}
              placeholder={field.sensitive && plugin.options[key] ? '已保存密钥（留空保持不变）' : String(field.default ?? '')}
              aria-label={field.title || key}
              onChange={(ev) => { setDraft((prev) => ({ ...prev, [key]: ev.target.value })) }}
            />
          )}
        </SettingsRow>
      ))}
      <div className={localCss.inline} style={{ justifyContent: 'flex-end', marginTop: 8 }}>
        <button type="button" className={localCss.button} disabled={busy} onClick={onCancel}>
          取消
        </button>
        <button type="button" className={localCss.button} disabled={busy} onClick={handleSubmit}>
          保存配置
        </button>
      </div>
    </div>
  )
}

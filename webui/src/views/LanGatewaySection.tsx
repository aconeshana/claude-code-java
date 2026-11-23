import { useEffect, useMemo, useState } from 'react'
import { Input, Pill, Switch } from '@primitives'
import {
  buildClientSnippet,
  useLanGateway,
  type ClientSnippetKind,
  type ShellPlatformKind,
} from '../store/lanGateway'
import type { LanEndpointKind } from '../api/types'
import { MenuSelect, SettingsRow } from './SettingsPanel'
import localCss from './SettingsPanel.module.css'
import cardCss from './ModelsSection.module.css'

const ENDPOINT_KIND_LABELS: Readonly<Record<LanEndpointKind, string>> = {
  lan: '局域网 IP',
  mdns: 'Bonjour 域名',
  tailscale: 'Tailscale',
  public: '公网 IP',
}

const CLIENT_OPTIONS: readonly { id: ClientSnippetKind; label: string }[] = [
  { id: 'poco', label: 'Poco (model.json)' },
  { id: 'claude-code', label: 'Claude Code CLI' },
  { id: 'codex', label: 'Codex CLI (Responses)' },
  { id: 'openai', label: 'OpenAI / Cursor / SDK' },
  { id: 'gemini', label: 'Google Gemini SDK' },
]

const PLATFORM_OPTIONS: readonly { id: ShellPlatformKind; label: string }[] = [
  { id: 'powershell', label: 'Windows PowerShell' },
  { id: 'bash', label: 'Linux / macOS Bash' },
]

const UNSET_STAND_IN = '__none__'

export function LanGatewaySection() {
  const status = useLanGateway((state) => state.status)
  const calls = useLanGateway((state) => state.calls)
  const loading = useLanGateway((state) => state.loading)
  const error = useLanGateway((state) => state.error)
  const refresh = useLanGateway((state) => state.refresh)
  const refreshCalls = useLanGateway((state) => state.refreshCalls)
  const updateSettings = useLanGateway((state) => state.updateSettings)
  const createKey = useLanGateway((state) => state.createKey)
  const updateKey = useLanGateway((state) => state.updateKey)
  const removeKey = useLanGateway((state) => state.removeKey)
  const clearCalls = useLanGateway((state) => state.clearCalls)

  const [newKeyName, setNewKeyName] = useState('')
  const [creatingKey, setCreatingKey] = useState(false)
  const [selectedModelsForNewKey, setSelectedModelsForNewKey] = useState<string[]>([])
  const [restrictModelsForNewKey, setRestrictModelsForNewKey] = useState(false)
  const [revealedKeys, setRevealedKeys] = useState<Record<string, boolean>>({})
  const [copiedId, setCopiedId] = useState<string | null>(null)

  const [snippetEndpoint, setSnippetEndpoint] = useState<string>('')
  const [snippetKeyId, setSnippetKeyId] = useState<string>('')
  const [snippetClient, setSnippetClient] = useState<ClientSnippetKind>('claude-code')
  const [snippetPlatform, setSnippetPlatform] = useState<ShellPlatformKind>('powershell')

  useEffect(() => {
    void refresh()
  }, [refresh])

  const endpoints = status?.endpoints ?? []
  const keys = status?.keys ?? []
  const availableModels = status?.available_models ?? []

  const effectiveEndpointUrl = useMemo(() => {
    if (snippetEndpoint !== '' && endpoints.some((e) => e.url === snippetEndpoint)) {
      return snippetEndpoint
    }
    return endpoints[0]?.url ?? `http://127.0.0.1:${status?.port ?? 19845}`
  }, [endpoints, snippetEndpoint, status?.port])

  const activeKey = useMemo(() => {
    if (snippetKeyId !== '') {
      const match = keys.find((k) => k.id === snippetKeyId)
      if (match != null) return match
    }
    return keys.find((k) => k.enabled) ?? keys[0] ?? null
  }, [keys, snippetKeyId])

  const standInOptions = useMemo(() => [
    { id: UNSET_STAND_IN, label: '不映射（仅精确匹配模型名）' },
    ...availableModels.map((m) => ({ id: m.id, label: m.id })),
  ], [availableModels])

  const snippetText = useMemo(() => buildClientSnippet({
    baseUrl: effectiveEndpointUrl,
    apiKey: activeKey?.secret ?? '',
    modelName: availableModels[0]?.id ?? 'your-model',
    client: snippetClient,
    platform: snippetPlatform,
  }), [effectiveEndpointUrl, activeKey, availableModels, snippetClient, snippetPlatform])

  function copyText(tag: string, value: string) {
    if (typeof navigator !== 'undefined' && navigator.clipboard != null) {
      void navigator.clipboard.writeText(value)
    }
    setCopiedId(tag)
    setTimeout(() => {
      setCopiedId((cur) => (cur === tag ? null : cur))
    }, 1500)
  }

  return (
    <>
      <p className={cardCss.intro}>
        开启后每次启动 Web UI 自动在同端口（{status?.port ?? 19845}）提供无状态 4×4 协议转换网关（Anthropic / OpenAI Chat / Responses / Gemini），供局域网内 Windows / Linux 设备安全复用本机已接入的模型，且与本机会话及工作区严格隔离。
      </p>

      {error != null && <div className={localCss.error} role="alert">{error}</div>}

      <div className={cardCss.card}>
        <SettingsRow
          title="开启局域网模型共享"
          description={
            status?.enabled
              ? `监听中：${status.listening_host}:${status.port}（随 Web UI 启动自动生效）`
              : `当前仅本机回环：${status?.listening_host ?? '127.0.0.1'}:${status?.port ?? 19845}`
          }
        >
          <Switch
            checked={status?.enabled === true}
            onChange={(next) => { void updateSettings({ enabled: next }) }}
            label="enable lan model gateway"
          />
        </SettingsRow>

        <SettingsRow
          title="同时共享本机默认 Provider"
          description="默认仅暴露 model.json 中的自定义模型；开启后允许局域网设备调用本机环境变量或登录态下的默认模型"
        >
          <Switch
            checked={status?.expose_default_provider === true}
            onChange={(next) => { void updateSettings({ exposeDefaultProvider: next }) }}
            label="expose default provider"
          />
        </SettingsRow>

        <SettingsRow
          title="阻止 Mac 空闲休眠"
          description={
            status?.awake_active
              ? 'caffeinate 守护运行中，防止息屏断流'
              : '开启网关时自动阻止 macOS 空闲休眠'
          }
        >
          <Switch
            checked={status?.prevent_mac_sleep !== false}
            onChange={(next) => { void updateSettings({ preventMacSleep: next }) }}
            label="prevent mac sleep"
          />
        </SettingsRow>

        <SettingsRow
          title="Claude 家族兜底映射"
          description="当远端 Claude Code 发送未配置的 claude-haiku/sonnet/opus 辅助请求时，自动路由到该模型"
        >
          <MenuSelect
            value={status?.tier_stand_in_model ? status.tier_stand_in_model : UNSET_STAND_IN}
            label={status?.tier_stand_in_model ? status.tier_stand_in_model : '不映射'}
            options={standInOptions}
            onChange={(id) => {
              void updateSettings({ tierStandInModel: id === UNSET_STAND_IN ? '' : id })
            }}
            ariaLabel="tier stand-in model"
          />
        </SettingsRow>
      </div>

      <div className={cardCss.card}>
        <div className={cardCss.cardHeader}>
          <strong className={cardCss.cardHeaderName} style={{ fontSize: 13 }}>
            局域网可达地址（已自动过滤代理 TUN 虚拟网卡）
          </strong>
          {loading && <span className={localCss.hint}>刷新中…</span>}
        </div>
        <ul className={cardCss.entryList}>
          {endpoints.length === 0 && (
            <li className={localCss.empty}>未检测到局域网网卡地址（本机测试可使用 http://127.0.0.1:{status?.port ?? 19845}）</li>
          )}
          {endpoints.map((ep) => (
            <li key={ep.url} className={cardCss.entryCard}>
              <div className={cardCss.entryInfo}>
                <div className={cardCss.entryName}>
                  <code>{ep.url}</code>
                  <Pill>{ENDPOINT_KIND_LABELS[ep.kind]}</Pill>
                </div>
              </div>
              <button
                type="button"
                className={localCss.button}
                onClick={() => { copyText(`ep-${ep.url}`, ep.url) }}
              >
                {copiedId === `ep-${ep.url}` ? '已复制' : '复制地址'}
              </button>
            </li>
          ))}
        </ul>
      </div>

      <div className={cardCss.card}>
        <div className={cardCss.cardHeader}>
          <strong className={cardCss.cardHeaderName} style={{ fontSize: 13 }}>
            设备访问密钥（sk-poco-...）
          </strong>
        </div>

        <ul className={cardCss.entryList}>
          {keys.length === 0 && (
            <li className={localCss.empty}>暂无设备密钥（开启局域网共享会自动生成一把默认密钥，也可手动创建）</li>
          )}
          {keys.map((key) => {
            const revealed = revealedKeys[key.id] === true
            const scopeLabel = key.allowed_models.length === 0 || key.allowed_models.includes('*')
              ? '全部模型'
              : `限 ${key.allowed_models.length} 个模型`
            return (
              <li key={key.id} className={cardCss.entryCard}>
                <div className={cardCss.entryInfo}>
                  <div className={cardCss.entryName}>
                    {key.name}
                    <Pill>{scopeLabel}</Pill>
                    <Pill>{key.enabled ? '已启用' : '已停用'}</Pill>
                  </div>
                  <div className={cardCss.entryMeta}>
                    <code>{revealed ? key.secret : key.masked_secret}</code>
                    {' · '}请求 {key.total_requests} 次
                    {' · '}输入 {key.input_tokens.toLocaleString()} / 输出 {key.output_tokens.toLocaleString()} tokens
                  </div>
                </div>
                <Switch
                  checked={key.enabled}
                  onChange={(next) => { void updateKey(key.id, { enabled: next }) }}
                  label={`toggle key ${key.name}`}
                />
                <button
                  type="button"
                  className={localCss.button}
                  onClick={() => { setRevealedKeys({ ...revealedKeys, [key.id]: !revealed }) }}
                >
                  {revealed ? '隐藏' : '查看'}
                </button>
                <button
                  type="button"
                  className={localCss.button}
                  onClick={() => { copyText(`key-${key.id}`, key.secret) }}
                >
                  {copiedId === `key-${key.id}` ? '已复制' : '复制密钥'}
                </button>
                <button
                  type="button"
                  className={`${localCss.button} ${cardCss.dangerButton}`}
                  onClick={() => { void removeKey(key.id) }}
                >
                  删除
                </button>
              </li>
            )
          })}
        </ul>

        {creatingKey ? (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginTop: 8 }}>
            <Input
              value={newKeyName}
              onChange={(e) => { setNewKeyName(e.target.value) }}
              placeholder="设备备注名（如：Windows 台式机 / Ubuntu 开发机）"
              aria-label="new gateway key name"
            />
            {availableModels.length > 0 && (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                <SettingsRow title="限制该密钥可调用的模型">
                  <Switch
                    checked={restrictModelsForNewKey}
                    onChange={setRestrictModelsForNewKey}
                    label="restrict key models"
                  />
                </SettingsRow>
                {restrictModelsForNewKey && (
                  <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                    {availableModels.map((m) => {
                      const checked = selectedModelsForNewKey.includes(m.id)
                      return (
                        <button
                          key={m.id}
                          type="button"
                          className={localCss.button}
                          onClick={() => {
                            setSelectedModelsForNewKey(
                              checked
                                ? selectedModelsForNewKey.filter((x) => x !== m.id)
                                : [...selectedModelsForNewKey, m.id],
                            )
                          }}
                        >
                          {checked ? `✓ ${m.id}` : m.id}
                        </button>
                      )
                    })}
                  </div>
                )}
              </div>
            )}
            <div style={{ display: 'flex', gap: 8 }}>
              <button
                type="button"
                className={localCss.button}
                onClick={() => {
                  const allowed = restrictModelsForNewKey && selectedModelsForNewKey.length > 0
                    ? selectedModelsForNewKey
                    : undefined
                  void createKey(newKeyName.trim() || '新设备密钥', allowed)
                  setNewKeyName('')
                  setSelectedModelsForNewKey([])
                  setRestrictModelsForNewKey(false)
                  setCreatingKey(false)
                }}
              >
                生成密钥
              </button>
              <button
                type="button"
                className={localCss.button}
                onClick={() => { setCreatingKey(false) }}
              >
                取消
              </button>
            </div>
          </div>
        ) : (
          <button
            type="button"
            className={cardCss.addTrigger}
            onClick={() => { setCreatingKey(true) }}
          >
            + 新建设备访问密钥
          </button>
        )}
      </div>

      <div className={cardCss.card}>
        <div className={cardCss.cardHeader}>
          <strong className={cardCss.cardHeaderName} style={{ fontSize: 13 }}>
            跨平台客户端一键接入配置（Windows / Linux）
          </strong>
          <button
            type="button"
            className={localCss.button}
            onClick={() => { copyText('snippet', snippetText) }}
          >
            {copiedId === 'snippet' ? '已复制配置' : '一键复制'}
          </button>
        </div>

        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 10 }}>
          {endpoints.length > 1 && (
            <MenuSelect
              value={effectiveEndpointUrl}
              label={effectiveEndpointUrl}
              options={endpoints.map((e) => ({ id: e.url, label: `${e.url} (${ENDPOINT_KIND_LABELS[e.kind]})` }))}
              onChange={setSnippetEndpoint}
              ariaLabel="snippet endpoint"
            />
          )}
          {keys.length > 1 && activeKey != null && (
            <MenuSelect
              value={activeKey.id}
              label={`密钥：${activeKey.name}`}
              options={keys.map((k) => ({ id: k.id, label: `${k.name} (${k.masked_secret})` }))}
              onChange={setSnippetKeyId}
              ariaLabel="snippet key"
            />
          )}
          <MenuSelect
            value={snippetClient}
            label={CLIENT_OPTIONS.find((c) => c.id === snippetClient)?.label ?? snippetClient}
            options={CLIENT_OPTIONS}
            onChange={(id) => { setSnippetClient(id as ClientSnippetKind) }}
            ariaLabel="snippet client"
          />
          {snippetClient !== 'poco' && (
            <MenuSelect
              value={snippetPlatform}
              label={PLATFORM_OPTIONS.find((p) => p.id === snippetPlatform)?.label ?? snippetPlatform}
              options={PLATFORM_OPTIONS}
              onChange={(id) => { setSnippetPlatform(id as ShellPlatformKind) }}
              ariaLabel="snippet platform"
            />
          )}
        </div>

        <pre
          style={{
            margin: 0,
            padding: '10px 12px',
            borderRadius: 8,
            overflowX: 'auto',
            fontSize: 12,
            lineHeight: 1.5,
            background: 'var(--dsw-alias-bg-layer-1)',
            border: '1px solid var(--dsw-alias-border-l2)',
          }}
        >
          <code>{snippetText}</code>
        </pre>
      </div>

      <div className={cardCss.card}>
        <div className={cardCss.cardHeader}>
          <strong className={cardCss.cardHeaderName} style={{ fontSize: 13 }}>
            最近跨设备请求监控（内存最近 100 条）
          </strong>
          <button
            type="button"
            className={localCss.button}
            onClick={() => { void refreshCalls() }}
          >
            刷新
          </button>
          {calls.length > 0 && (
            <button
              type="button"
              className={localCss.button}
              onClick={() => { void clearCalls() }}
            >
              清空
            </button>
          )}
        </div>
        <ul className={cardCss.entryList}>
          {calls.length === 0 && (
            <li className={localCss.empty}>暂无局域网模型请求记录</li>
          )}
          {calls.map((call) => (
            <li key={call.id} className={cardCss.entryCard}>
              <div className={cardCss.entryInfo}>
                <div className={cardCss.entryName}>
                  <span>{call.resolved_model || call.requested_model}</span>
                  <Pill>{call.source_protocol} → {call.target_protocol || '—'}</Pill>
                  <Pill>{call.status}</Pill>
                  {call.streaming && <Pill>SSE</Pill>}
                </div>
                <div className={cardCss.entryMeta}>
                  {call.key_name || '未命名密钥'} ({call.client_ip})
                  {' · '}首字 {call.ttft_ms}ms / 总计 {call.duration_ms}ms
                  {' · '}in {call.input_tokens} / out {call.output_tokens}
                  {call.error != null ? ` · 错误：${call.error}` : ''}
                </div>
              </div>
            </li>
          ))}
        </ul>
      </div>
    </>
  )
}

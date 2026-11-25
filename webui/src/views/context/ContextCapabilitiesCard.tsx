import { useEffect, useMemo, useState, type ChangeEvent, type ReactElement } from 'react'
import clsx from 'clsx'
import { Input, Switch, Tag } from '@primitives'
import { CAT_COLOR } from '@dsh-context/client/categories'
import type { ViewKit } from '@dsh-context/client/viewkit'
import type {
  BuiltInToolCapability,
  McpServerCapability,
  McpToolCapability,
  ModelProtocol,
  SessionCapabilityScope,
  SkillCapability,
} from '../../api/types'
import { useCapabilities } from '../../store/capabilities'
import { useSessionContext } from '../../store/sessionContext'
import css from './ContextCapabilitiesCard.module.css'

type GroupId = 'tool' | 'skill' | 'mcp'

type SelectedItem =
  | { readonly kind: 'tool'; readonly name: string }
  | { readonly kind: 'skill'; readonly name: string }
  | { readonly kind: 'mcp_server'; readonly name: string }
  | { readonly kind: 'mcp_tool'; readonly serverName: string; readonly fullName: string }

interface SchemaProperty {
  readonly name: string
  readonly typeLabel: string
  readonly required: boolean
  readonly description: string
}

function extractSchemaProperties(schema: Readonly<Record<string, unknown>> | undefined): readonly SchemaProperty[] {
  if (schema == null || typeof schema !== 'object') return []
  const propsRaw = schema['properties']
  if (propsRaw == null || typeof propsRaw !== 'object') return []
  const reqRaw = schema['required']
  const reqSet = new Set<string>(
    Array.isArray(reqRaw) ? reqRaw.filter((x): x is string => typeof x === 'string') : [],
  )
  const out: SchemaProperty[] = []
  for (const [propName, val] of Object.entries(propsRaw as Record<string, unknown>)) {
    if (val == null || typeof val !== 'object') continue
    const rec = val as Record<string, unknown>
    const rawType = rec['type']
    const typeLabel = typeof rawType === 'string'
      ? rawType
      : Array.isArray(rawType)
        ? rawType.join(' | ')
        : 'any'
    const desc = typeof rec['description'] === 'string' ? rec['description'] : ''
    out.push({
      name: propName,
      typeLabel,
      required: reqSet.has(propName),
      description: desc,
    })
  }
  return out
}

function protocolLabel(t: ViewKit['t'], protocol: ModelProtocol): string {
  switch (protocol) {
    case 'anthropic': return t('caps.protocol.anthropic')
    case 'chat': return t('caps.protocol.chat')
    case 'responses': return t('caps.protocol.responses')
    case 'gemini': return t('caps.protocol.gemini')
  }
}

export function ContextCapabilitiesCard({
  sessionId,
  kit,
}: {
  sessionId: string | null
  kit: ViewKit
}): ReactElement | null {
  const { t, fmt } = kit
  const key = sessionId ?? ''
  const capabilities = useCapabilities((s) => s.bySession[key] ?? null)
  const pendingKeys = useCapabilities((s) => s.pendingKeys)
  const scope = useCapabilities((s) => s.scope)
  const lastError = useCapabilities((s) => s.lastError)
  const setScope = useCapabilities((s) => s.setScope)
  const refresh = useCapabilities((s) => s.refresh)
  const toggle = useCapabilities((s) => s.toggle)

  // Re-fetch automatically when sessionId or the session's selected model changes.
  const activeSelectedModel = useSessionContext((s) =>
    s.sessionId === sessionId ? (s.selection?.current ?? '') : '')
  useEffect(() => {
    void refresh(sessionId)
  }, [sessionId, activeSelectedModel, refresh])

  const [query, setQuery] = useState('')
  const [openGroups, setOpenGroups] = useState<Readonly<Record<GroupId, boolean>>>({
    tool: false,
    skill: false,
    mcp: false,
  })
  const [openMcpServers, setOpenMcpServers] = useState<Readonly<Record<string, boolean>>>({})
  const [selected, setSelected] = useState<SelectedItem | null>(null)

  const needle = query.trim().toLowerCase()

  const filteredTools = useMemo<readonly BuiltInToolCapability[]>(() => {
    if (capabilities === null) return []
    if (needle === '') return capabilities.tools
    return capabilities.tools.filter((item) =>
      item.name.toLowerCase().includes(needle) || item.description.toLowerCase().includes(needle))
  }, [capabilities, needle])

  const filteredSkills = useMemo<readonly SkillCapability[]>(() => {
    if (capabilities === null) return []
    if (needle === '') return capabilities.skills
    return capabilities.skills.filter((item) =>
      item.name.toLowerCase().includes(needle)
      || item.description.toLowerCase().includes(needle)
      || item.source.toLowerCase().includes(needle))
  }, [capabilities, needle])

  const filteredMcpServers = useMemo<readonly McpServerCapability[]>(() => {
    if (capabilities === null) return []
    if (needle === '') return capabilities.mcp_servers
    const out: McpServerCapability[] = []
    for (const srv of capabilities.mcp_servers) {
      const serverMatch = srv.name.toLowerCase().includes(needle)
        || srv.instructions.toLowerCase().includes(needle)
      const matchingTools = srv.tools.filter((mt) =>
        mt.name.toLowerCase().includes(needle)
        || mt.full_name.toLowerCase().includes(needle)
        || mt.description.toLowerCase().includes(needle))
      if (serverMatch) {
        out.push(srv)
      } else if (matchingTools.length > 0) {
        out.push({ ...srv, tools: matchingTools })
      }
    }
    return out
  }, [capabilities, needle])

  // Auto-select the first available item once capabilities load if nothing is selected yet.
  useEffect(() => {
    if (selected !== null || capabilities === null) return
    if (capabilities.tools.length > 0) {
      setSelected({ kind: 'tool', name: capabilities.tools[0].name })
    } else if (capabilities.skills.length > 0) {
      setSelected({ kind: 'skill', name: capabilities.skills[0].name })
    } else if (capabilities.mcp_servers.length > 0) {
      setSelected({ kind: 'mcp_server', name: capabilities.mcp_servers[0].name })
    }
  }, [capabilities, selected])

  if (capabilities === null) return null

  const effectiveChecked = (sessionEnabled: boolean, defaultEnabled: boolean): boolean =>
    scope === 'default' ? defaultEnabled : sessionEnabled

  const isPending = (kind: string, name: string, serverName?: string): boolean =>
    pendingKeys[`${key}:${kind}:${serverName ?? ''}:${name}`] === true

  const toggleGroup = (g: GroupId): void => {
    setOpenGroups((prev) => ({ ...prev, [g]: !prev[g] }))
  }

  const toggleMcpExpand = (serverName: string): void => {
    setOpenMcpServers((prev) => ({ ...prev, [serverName]: !(prev[serverName] ?? true) }))
  }

  const toolEnabledCount = capabilities.tools.filter((x) => x.session_enabled).length
  const toolTotalTokens = capabilities.tools.reduce((acc, x) => acc + x.tokens, 0)
  const skillEnabledCount = capabilities.skills.filter((x) => x.session_enabled).length
  const skillTotalTokens = capabilities.skills.reduce((acc, x) => acc + x.tokens, 0)
  const mcpEnabledCount = capabilities.mcp_servers.filter((x) => x.session_enabled).length
  const mcpTotalTokens = capabilities.mcp_servers.reduce((acc, x) => acc + x.tokens, 0)

  const renderScopeToggle = (nextScope: SessionCapabilityScope, labelKey: string, hintKey: string): ReactElement => (
    <button
      type="button"
      className={'lc-gran-btn' + (scope === nextScope ? ' lc-gran-on' : '')}
      title={t(hintKey)}
      data-testid={`caps-scope-${nextScope}`}
      onClick={() => { setScope(nextScope) }}
    >
      {t(labelKey)}
    </button>
  )

  const renderDetailPane = (): ReactElement => {
    if (selected === null) {
      return <div className={css.detailEmpty}>{t('caps.detail.empty')}</div>
    }

    if (selected.kind === 'tool') {
      const item = capabilities.tools.find((x) => x.name === selected.name)
      if (item === undefined) return <div className={css.detailEmpty}>{t('caps.detail.empty')}</div>
      const params = extractSchemaProperties(item.input_schema)
      const checked = effectiveChecked(item.session_enabled, item.default_enabled)
      const overridden = item.session_enabled !== item.default_enabled
      return (
        <>
          <div className="lc-ts-card" data-testid="caps-detail-card">
            <div className="lc-ts-card-head">
              <b className="lc-ts-call-name" title={item.name}>{item.name}</b>
              <span className="lc-ts-card-right">
                <span className="lc-ts-card-meta">~{fmt(item.tokens)} tokens</span>
                <Switch
                  checked={checked}
                  disabled={item.kernel || isPending('tool', item.name)}
                  label={`${item.name} (${scope})`}
                  title={item.kernel ? t('caps.kernelLock') : undefined}
                  onChange={(next) => {
                    void toggle({ sessionId, kind: 'tool', name: item.name, enabled: next })
                  }}
                />
              </span>
            </div>
            <div className={css.detailHeaderMeta}>
              <Tag tone={item.kernel ? 'solid' : item.delivery_mode === 'prefix_eager' ? 'info' : item.delivery_mode === 'progressive_deferred' ? 'success' : 'neutral'}>
                {item.kernel
                  ? t('caps.badge.kernel')
                  : item.delivery_mode === 'prefix_eager'
                    ? t('caps.badge.eager')
                    : item.delivery_mode === 'progressive_deferred'
                      ? t('caps.badge.deferred')
                      : t('caps.badge.disabled')}
              </Tag>
              {overridden ? <Tag tone="warning">{t('caps.badge.overridden')}</Tag> : null}
              <span>
                {t('caps.detail.defaultState', {
                  state: item.default_enabled ? t('caps.state.on') : t('caps.state.off'),
                })}
              </span>
            </div>
            <pre className="lc-ts-desc-body">
              {item.description !== '' ? item.description : t('caps.detail.noDesc')}
            </pre>
          </div>
          <div className="lc-ts-card">
            <div className="lc-ts-card-head">
              <b>{t('caps.detail.params')}</b>
              <span className="lc-ts-card-count">{params.length}</span>
            </div>
            {params.length === 0
              ? <div className="lc-ts-params-empty">{t('caps.detail.noParams')}</div>
              : params.map((p) => (
                <div key={p.name} className="lc-ts-param-row">
                  <span className="lc-ts-param-name" title={p.name}>{p.name}</span>
                  <span className="lc-ts-param-type">{p.typeLabel}</span>
                  <span className={p.required ? 'lc-ts-param-req' : 'lc-ts-param-req-off'}>
                    {p.required ? '✓' : '·'}
                  </span>
                  {p.description !== '' ? <span className="lc-ts-param-desc">{p.description}</span> : null}
                </div>
              ))}
          </div>
        </>
      )
    }

    if (selected.kind === 'skill') {
      const item = capabilities.skills.find((x) => x.name === selected.name)
      if (item === undefined) return <div className={css.detailEmpty}>{t('caps.detail.empty')}</div>
      const checked = effectiveChecked(item.session_enabled, item.default_enabled)
      const overridden = item.session_enabled !== item.default_enabled
      return (
        <div className="lc-ts-card" data-testid="caps-detail-card">
          <div className="lc-ts-card-head">
            <b className="lc-ts-call-name" title={item.name}>{item.name}</b>
            <span className="lc-ts-card-right">
              <span className="lc-ts-card-meta">~{fmt(item.tokens)} tokens</span>
              <Switch
                checked={checked}
                disabled={isPending('skill', item.name)}
                label={`${item.name} (${scope})`}
                onChange={(next) => {
                  void toggle({ sessionId, kind: 'skill', name: item.name, enabled: next })
                }}
              />
            </span>
          </div>
          <div className={css.detailHeaderMeta}>
            <Tag tone="info">{item.source}</Tag>
            {!item.protocol_visible ? <Tag tone="neutral">{t('caps.badge.hidden')}</Tag> : null}
            {overridden ? <Tag tone="warning">{t('caps.badge.overridden')}</Tag> : null}
            <span>
              {t('caps.detail.defaultState', {
                state: item.default_enabled ? t('caps.state.on') : t('caps.state.off'),
              })}
            </span>
          </div>
          <pre className="lc-ts-desc-body">
            {item.description !== '' ? item.description : t('caps.detail.noDesc')}
          </pre>
        </div>
      )
    }

    if (selected.kind === 'mcp_server') {
      const srv = capabilities.mcp_servers.find((x) => x.name === selected.name)
      if (srv === undefined) return <div className={css.detailEmpty}>{t('caps.detail.empty')}</div>
      const checked = effectiveChecked(srv.session_enabled, srv.default_enabled)
      const overridden = srv.session_enabled !== srv.default_enabled
      return (
        <>
          <div className="lc-ts-card" data-testid="caps-detail-card">
            <div className="lc-ts-card-head">
              <b className="lc-ts-call-name" title={srv.name}>{srv.name}</b>
              <span className="lc-ts-card-right">
                <span className="lc-ts-card-meta">~{fmt(srv.tokens)} tokens</span>
                <Switch
                  checked={checked}
                  disabled={!srv.manageable || isPending('mcp_server', srv.name)}
                  label={`${srv.name} (${scope})`}
                  onChange={(next) => {
                    void toggle({ sessionId, kind: 'mcp_server', name: srv.name, enabled: next })
                  }}
                />
              </span>
            </div>
            <div className={css.detailHeaderMeta}>
              <Tag tone={srv.status === 'connected' ? 'success' : srv.status === 'failed' ? 'danger' : 'neutral'}>
                {srv.status}
              </Tag>
              <Tag tone="outline">{srv.transport}</Tag>
              <Tag tone="outline">{srv.scope}</Tag>
              {overridden ? <Tag tone="warning">{t('caps.badge.overridden')}</Tag> : null}
              <span>
                {t('caps.detail.defaultState', {
                  state: srv.default_enabled ? t('caps.state.on') : t('caps.state.off'),
                })}
              </span>
            </div>
            <pre className="lc-ts-desc-body">
              {srv.instructions !== ''
                ? srv.instructions
                : `${srv.name} (${srv.transport}) · ${srv.tools.length} tools`}
            </pre>
          </div>
          {srv.tools.length > 0 ? (
            <div className="lc-ts-card">
              <div className="lc-ts-card-head">
                <b>{t('caps.detail.mcpTools')}</b>
                <span className="lc-ts-card-count">{srv.tools.length}</span>
              </div>
              {srv.tools.map((mt) => (
                <div key={mt.full_name} className="lc-ts-param-row">
                  <span className="lc-ts-param-name" title={mt.name}>{mt.name}</span>
                  <span className="lc-ts-param-type">~{fmt(mt.tokens)}t</span>
                  <span className={mt.session_enabled ? 'lc-ts-param-req' : 'lc-ts-param-req-off'}>
                    {mt.session_enabled ? 'ON' : 'OFF'}
                  </span>
                  {mt.description !== '' ? <span className="lc-ts-param-desc">{mt.description}</span> : null}
                </div>
              ))}
            </div>
          ) : null}
        </>
      )
    }

    // mcp_tool
    const parentSrv = capabilities.mcp_servers.find((x) => x.name === selected.serverName)
    const mt: McpToolCapability | undefined = parentSrv?.tools.find((x) => x.full_name === selected.fullName)
    if (mt === undefined) return <div className={css.detailEmpty}>{t('caps.detail.empty')}</div>
    const params = extractSchemaProperties(mt.input_schema)
    const checked = effectiveChecked(mt.session_enabled, mt.default_enabled)
    const overridden = mt.session_enabled !== mt.default_enabled
    return (
      <>
        <div className="lc-ts-card" data-testid="caps-detail-card">
          <div className="lc-ts-card-head">
            <b className="lc-ts-call-name" title={mt.full_name}>{mt.full_name}</b>
            <span className="lc-ts-card-right">
              <span className="lc-ts-card-meta">~{fmt(mt.tokens)} tokens</span>
              <Switch
                checked={checked}
                disabled={isPending('mcp_tool', mt.full_name, mt.server_name)}
                label={`${mt.full_name} (${scope})`}
                onChange={(next) => {
                  void toggle({
                    sessionId,
                    kind: 'mcp_tool',
                    name: mt.full_name,
                    serverName: mt.server_name,
                    enabled: next,
                  })
                }}
              />
            </span>
          </div>
          <div className={css.detailHeaderMeta}>
            <Tag tone="info">{mt.server_name}</Tag>
            <Tag tone="success">{t('caps.badge.deferred')}</Tag>
            {overridden ? <Tag tone="warning">{t('caps.badge.overridden')}</Tag> : null}
            <span>
              {t('caps.detail.defaultState', {
                state: mt.default_enabled ? t('caps.state.on') : t('caps.state.off'),
              })}
            </span>
          </div>
          <pre className="lc-ts-desc-body">
            {mt.description !== '' ? mt.description : t('caps.detail.noDesc')}
          </pre>
        </div>
        <div className="lc-ts-card">
          <div className="lc-ts-card-head">
            <b>{t('caps.detail.params')}</b>
            <span className="lc-ts-card-count">{params.length}</span>
          </div>
          {params.length === 0
            ? <div className="lc-ts-params-empty">{t('caps.detail.noParams')}</div>
            : params.map((p) => (
              <div key={p.name} className="lc-ts-param-row">
                <span className="lc-ts-param-name" title={p.name}>{p.name}</span>
                <span className="lc-ts-param-type">{p.typeLabel}</span>
                <span className={p.required ? 'lc-ts-param-req' : 'lc-ts-param-req-off'}>
                  {p.required ? '✓' : '·'}
                </span>
                {p.description !== '' ? <span className="lc-ts-param-desc">{p.description}</span> : null}
              </div>
            ))}
        </div>
      </>
    )
  }

  const toolGroupOpen = openGroups.tool || needle !== ''
  const skillGroupOpen = openGroups.skill || needle !== ''
  const mcpGroupOpen = openGroups.mcp || needle !== ''

  return (
    <div className={clsx('lc-card', css.card)} data-testid="context-capabilities-card">
      <div className="lc-card-title">
        <span className="lc-card-title-text">{t('caps.title')}</span>
        <Tag tone="info" className="lc-cap-proto">
          {protocolLabel(t, capabilities.protocol)}
          {capabilities.model !== '' ? ` · ${capabilities.model}` : ''}
        </Tag>
        {capabilities.saved_tokens > 0 ? (
          <Tag tone="success">{t('caps.saved', { n: fmt(capabilities.saved_tokens) })}</Tag>
        ) : null}
        <span className="lc-gran" role="group">
          {renderScopeToggle('session', 'caps.scope.session', 'caps.scope.sessionHint')}
          {renderScopeToggle('default', 'caps.scope.default', 'caps.scope.defaultHint')}
        </span>
      </div>

      <div className={css.subtitleRow}>
        <span>{t('caps.subtitle')}</span>
      </div>

      {lastError !== null ? <div className={css.errorBanner}>{lastError}</div> : null}

      <div className={css.toolbarRow}>
        <Input
          className={css.searchInput}
          value={query}
          placeholder={t('caps.search')}
          aria-label={t('caps.search')}
          onChange={(ev: ChangeEvent<HTMLInputElement>) => { setQuery(ev.target.value) }}
        />
      </div>

      <div className={css.splitPane}>
        <div className={clsx('lc-br-cats', css.leftTree)} style={{ marginTop: 0 }}>
          {/* 1. Built-in Tools Group */}
          <div className={clsx('lc-br-cat', filteredTools.length === 0 && 'lc-br-cat-empty')}>
            <button
              type="button"
              className={clsx('lc-br-cat-row', toolGroupOpen && 'lc-br-cat-open')}
              data-testid="caps-group-tool"
              onClick={() => { toggleGroup('tool') }}
            >
              <span className={clsx('lc-br-chev', toolGroupOpen && 'lc-br-chev-on')} />
              <i style={{ backgroundColor: CAT_COLOR.tool }} />
              <span className="lc-br-count-grp">
                <span className="lc-br-cat-label">{t('caps.group.tool')}</span>
                <span className="lc-br-cat-count">{toolEnabledCount}/{capabilities.tools.length}</span>
              </span>
              <span className="lc-br-tokens">~{fmt(toolTotalTokens)}</span>
            </button>
            {toolGroupOpen && filteredTools.length > 0 ? (
              <div className="lc-br-body">
                {filteredTools.map((tool) => {
                  const isSel = selected?.kind === 'tool' && selected.name === tool.name
                  const checked = effectiveChecked(tool.session_enabled, tool.default_enabled)
                  const overridden = tool.session_enabled !== tool.default_enabled
                  return (
                    <div
                      key={tool.name}
                      className={clsx(css.itemRow, isSel && css.itemRowSelected)}
                      data-testid={`caps-tool-row-${tool.name}`}
                    >
                      <button
                        type="button"
                        className={css.itemSelectBtn}
                        onClick={() => { setSelected({ kind: 'tool', name: tool.name }) }}
                      >
                        <span className={clsx(css.itemName, !checked && css.itemNameMuted)} title={tool.name}>
                          {tool.name}
                        </span>
                        {tool.kernel ? <Tag tone="quiet">{t('caps.badge.kernel')}</Tag> : null}
                        {overridden ? <Tag tone="warning">{t('caps.badge.overridden')}</Tag> : null}
                      </button>
                      <span className={css.itemMeta}>
                        <span className={css.tokensLabel}>~{fmt(tool.tokens)}</span>
                        <Switch
                          checked={checked}
                          disabled={tool.kernel || isPending('tool', tool.name)}
                          label={`${tool.name} (${scope})`}
                          title={tool.kernel ? t('caps.kernelLock') : undefined}
                          onChange={(next) => {
                            setSelected({ kind: 'tool', name: tool.name })
                            void toggle({ sessionId, kind: 'tool', name: tool.name, enabled: next })
                          }}
                        />
                      </span>
                    </div>
                  )
                })}
              </div>
            ) : null}
          </div>

          {/* 2. Skills Group */}
          <div className={clsx('lc-br-cat', filteredSkills.length === 0 && 'lc-br-cat-empty')}>
            <button
              type="button"
              className={clsx('lc-br-cat-row', skillGroupOpen && 'lc-br-cat-open')}
              data-testid="caps-group-skill"
              onClick={() => { toggleGroup('skill') }}
            >
              <span className={clsx('lc-br-chev', skillGroupOpen && 'lc-br-chev-on')} />
              <i style={{ backgroundColor: CAT_COLOR.skill }} />
              <span className="lc-br-count-grp">
                <span className="lc-br-cat-label">{t('caps.group.skill')}</span>
                <span className="lc-br-cat-count">{skillEnabledCount}/{capabilities.skills.length}</span>
              </span>
              <span className="lc-br-tokens">~{fmt(skillTotalTokens)}</span>
            </button>
            {skillGroupOpen && filteredSkills.length > 0 ? (
              <div className="lc-br-body">
                {filteredSkills.map((sk) => {
                  const isSel = selected?.kind === 'skill' && selected.name === sk.name
                  const checked = effectiveChecked(sk.session_enabled, sk.default_enabled)
                  const overridden = sk.session_enabled !== sk.default_enabled
                  return (
                    <div
                      key={sk.name}
                      className={clsx(css.itemRow, isSel && css.itemRowSelected)}
                      data-testid={`caps-skill-row-${sk.name}`}
                    >
                      <button
                        type="button"
                        className={css.itemSelectBtn}
                        onClick={() => { setSelected({ kind: 'skill', name: sk.name }) }}
                      >
                        <span className={clsx(css.itemName, !checked && css.itemNameMuted)} title={sk.name}>
                          {sk.name}
                        </span>
                        <Tag tone="quiet">{sk.source}</Tag>
                        {overridden ? <Tag tone="warning">{t('caps.badge.overridden')}</Tag> : null}
                      </button>
                      <span className={css.itemMeta}>
                        <span className={css.tokensLabel}>~{fmt(sk.tokens)}</span>
                        <Switch
                          checked={checked}
                          disabled={isPending('skill', sk.name)}
                          label={`${sk.name} (${scope})`}
                          onChange={(next) => {
                            setSelected({ kind: 'skill', name: sk.name })
                            void toggle({ sessionId, kind: 'skill', name: sk.name, enabled: next })
                          }}
                        />
                      </span>
                    </div>
                  )
                })}
              </div>
            ) : null}
          </div>

          {/* 3. MCP Servers & Tools Group */}
          <div className={clsx('lc-br-cat', filteredMcpServers.length === 0 && 'lc-br-cat-empty')}>
            <button
              type="button"
              className={clsx('lc-br-cat-row', mcpGroupOpen && 'lc-br-cat-open')}
              data-testid="caps-group-mcp"
              onClick={() => { toggleGroup('mcp') }}
            >
              <span className={clsx('lc-br-chev', mcpGroupOpen && 'lc-br-chev-on')} />
              <i style={{ backgroundColor: CAT_COLOR.inject }} />
              <span className="lc-br-count-grp">
                <span className="lc-br-cat-label">{t('caps.group.mcp')}</span>
                <span className="lc-br-cat-count">{mcpEnabledCount}/{capabilities.mcp_servers.length}</span>
              </span>
              <span className="lc-br-tokens">~{fmt(mcpTotalTokens)}</span>
            </button>
            {mcpGroupOpen && filteredMcpServers.length > 0 ? (
              <div className="lc-br-body">
                {filteredMcpServers.map((srv) => {
                  const isSrvSel = selected?.kind === 'mcp_server' && selected.name === srv.name
                  const srvChecked = effectiveChecked(srv.session_enabled, srv.default_enabled)
                  const srvOverridden = srv.session_enabled !== srv.default_enabled
                  const srvOpen = openMcpServers[srv.name] ?? true
                  return (
                    <div key={srv.name}>
                      <div
                        className={clsx(css.itemRow, isSrvSel && css.itemRowSelected)}
                        data-testid={`caps-mcp-server-row-${srv.name}`}
                      >
                        {srv.tools.length > 0 ? (
                          <button
                            type="button"
                            className={clsx('lc-br-chev', srvOpen && 'lc-br-chev-on')}
                            style={{ border: 0, background: 'transparent', padding: 0, cursor: 'pointer' }}
                            aria-label={`Expand ${srv.name}`}
                            onClick={() => { toggleMcpExpand(srv.name) }}
                          />
                        ) : null}
                        <button
                          type="button"
                          className={css.itemSelectBtn}
                          onClick={() => { setSelected({ kind: 'mcp_server', name: srv.name }) }}
                        >
                          <span className={clsx(css.itemName, !srvChecked && css.itemNameMuted)} title={srv.name}>
                            {srv.name}
                          </span>
                          <Tag tone={srv.status === 'connected' ? 'success' : 'neutral'}>{srv.status}</Tag>
                          {srvOverridden ? <Tag tone="warning">{t('caps.badge.overridden')}</Tag> : null}
                        </button>
                        <span className={css.itemMeta}>
                          <span className={css.tokensLabel}>
                            {srv.tools.length > 0 ? `${srv.tools.length} · ` : ''}~{fmt(srv.tokens)}
                          </span>
                          <Switch
                            checked={srvChecked}
                            disabled={!srv.manageable || isPending('mcp_server', srv.name)}
                            label={`${srv.name} (${scope})`}
                            onChange={(next) => {
                              setSelected({ kind: 'mcp_server', name: srv.name })
                              void toggle({ sessionId, kind: 'mcp_server', name: srv.name, enabled: next })
                            }}
                          />
                        </span>
                      </div>
                      {srvOpen && srv.tools.length > 0 ? (
                        <div className={css.nestedMcpTools}>
                          {srv.tools.map((mt) => {
                            const isMtSel = selected?.kind === 'mcp_tool' && selected.fullName === mt.full_name
                            const mtChecked = effectiveChecked(mt.session_enabled, mt.default_enabled)
                            const mtOverridden = mt.session_enabled !== mt.default_enabled
                            return (
                              <div
                                key={mt.full_name}
                                className={clsx(css.itemRow, isMtSel && css.itemRowSelected)}
                                data-testid={`caps-mcp-tool-row-${mt.full_name}`}
                              >
                                <button
                                  type="button"
                                  className={css.itemSelectBtn}
                                  onClick={() => {
                                    setSelected({
                                      kind: 'mcp_tool',
                                      serverName: srv.name,
                                      fullName: mt.full_name,
                                    })
                                  }}
                                >
                                  <span className={clsx(css.itemName, !mtChecked && css.itemNameMuted)} title={mt.name}>
                                    {mt.name}
                                  </span>
                                  {mtOverridden ? <Tag tone="warning">{t('caps.badge.overridden')}</Tag> : null}
                                </button>
                                <span className={css.itemMeta}>
                                  <span className={css.tokensLabel}>~{fmt(mt.tokens)}</span>
                                  <Switch
                                    checked={mtChecked}
                                    disabled={isPending('mcp_tool', mt.full_name, srv.name)}
                                    label={`${mt.full_name} (${scope})`}
                                    onChange={(next) => {
                                      setSelected({
                                        kind: 'mcp_tool',
                                        serverName: srv.name,
                                        fullName: mt.full_name,
                                      })
                                      void toggle({
                                        sessionId,
                                        kind: 'mcp_tool',
                                        name: mt.full_name,
                                        serverName: srv.name,
                                        enabled: next,
                                      })
                                    }}
                                  />
                                </span>
                              </div>
                            )
                          })}
                        </div>
                      ) : null}
                    </div>
                  )
                })}
              </div>
            ) : null}
          </div>
        </div>

        <div className={css.rightDetail}>
          {renderDetailPane()}
        </div>
      </div>
    </div>
  )
}

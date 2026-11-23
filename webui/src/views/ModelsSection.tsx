import { useEffect, useMemo, useState } from 'react'
import { Input, Pill } from '@primitives'
import { IconChevronDownOutline14, IconChevronUpOutline14, IconCloseOutline16 } from '@primitives'
import { discoverProviderModels } from '../api/client'
import { useModels } from '../store/models'
import type { CustomModelEntry, DiscoveredModelEntry, ModelProtocol } from '../api/types'
import { MenuSelect, SettingsRow } from './SettingsPanel'
import localCss from './SettingsPanel.module.css'
import cardCss from './ModelsSection.module.css'

/**
 * The `/model` command's custom model catalogue (`CustomModelCatalog`), as a
 * flat list rather than dsh's provider-grouped `ModelsSection` — our backend
 * has no provider grouping concept, only one record per model name. Card
 * layout mirrors dsh's `ModelsSection` visually; headers are edited as
 * key/value row pairs and are only assembled into a `Record<string,string>`
 * when the save button actually calls the models API.
 *
 * The gateway rebuilds the whole `CustomModelConfig` on every save (only
 * `api_key` has real missing/null/string tri-state semantics — headers and
 * context_window are replaced wholesale), so the edit form always resends
 * the current headers and context window rather than leaving them out.
 */

const PROTOCOL_LABELS: Readonly<Record<ModelProtocol, string>> = {
  anthropic: 'Anthropic Messages',
  chat: 'Chat Completions',
  responses: 'Responses',
  gemini: 'Gemini',
}
const PROTOCOL_OPTIONS: readonly { id: string; label: string }[] = (
  Object.keys(PROTOCOL_LABELS) as ModelProtocol[]
).map((id) => ({ id, label: PROTOCOL_LABELS[id] }))

/**
 * The multimodal flag's three states, matching the TUI CustomModelDialog's
 * cycle: unconfigured (assume multimodal, the legacy model.json default),
 * explicitly multimodal, text-only (image content routes to the configured
 * image model).
 */
type MultimodalChoice = 'unset' | 'yes' | 'no'

const MULTIMODAL_OPTIONS: readonly { id: MultimodalChoice; label: string }[] = [
  { id: 'unset', label: '未设置（默认多模态）' },
  { id: 'yes', label: '多模态' },
  { id: 'no', label: '纯文本（图片走图片模型）' },
]

function multimodalChoiceOf(value: boolean | null | undefined): MultimodalChoice {
  if (value === false) return 'no'
  if (value === true) return 'yes'
  return 'unset'
}

function multimodalValueOf(choice: MultimodalChoice): boolean | null {
  if (choice === 'no') return false
  if (choice === 'yes') return true
  return null
}

export function ModelsSection() {
  const models = useModels((state) => state.models)
  const refresh = useModels((state) => state.refresh)
  const remove = useModels((state) => state.remove)

  const [editing, setEditing] = useState<CustomModelEntry | null | undefined>(undefined)
  const [discovering, setDiscovering] = useState(false)

  useEffect(() => {
    void refresh()
  }, [refresh])

  const existingNames = useMemo(
    () => new Set(models.map((m) => m.model_name)),
    [models],
  )

  return (
    <>
      <p className={cardCss.intro}>与 /model 命令共享同一份目录（~/.claude/model.json），支持单条添加或通过 /models 协议一次导入同一端点下的多个模型。</p>
      {editing !== undefined && (
        <ModelForm entry={editing} onDone={() => { setEditing(undefined) }} />
      )}
      {discovering && (
        <DiscoverModelsForm
          existingNames={existingNames}
          onDone={() => { setDiscovering(false) }}
        />
      )}
      <ul className={cardCss.entryList}>
        {models.length === 0 && <li className={localCss.empty}>（无自定义模型）</li>}
        {models.map((entry) => (
          <li key={entry.model_name} className={cardCss.entryCard}>
            <div className={cardCss.entryInfo}>
              <div className={cardCss.entryName}>
                {entry.model_name}
                <Pill>{PROTOCOL_LABELS[entry.protocol]}</Pill>
                <Pill>{entry.has_api_key ? '已配置密钥' : '未配置密钥'}</Pill>
              </div>
              <div className={cardCss.entryMeta} title={entry.base_url}>{entry.base_url}</div>
            </div>
            <button type="button" className={localCss.button} onClick={() => { setDiscovering(false); setEditing(entry) }}>
              编辑
            </button>
            <button
              type="button"
              className={`${localCss.button} ${cardCss.dangerButton}`}
              onClick={() => { void remove(entry.model_name) }}
            >
              删除
            </button>
          </li>
        ))}
      </ul>
      {editing === undefined && !discovering && (
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          <button type="button" className={cardCss.addTrigger} onClick={() => { setEditing(null) }}>
            + 添加单个模型
          </button>
          <button type="button" className={cardCss.addTrigger} onClick={() => { setDiscovering(true) }}>
            + 通过 /models 批量导入
          </button>
        </div>
      )}
    </>
  )
}

interface HeaderRow { key: string; value: string }

function headerRowsFromRecord(headers: Readonly<Record<string, string>>): HeaderRow[] {
  return Object.entries(headers).map(([key, value]) => ({ key, value }))
}

/** Assembled into a `Record<string,string>` only here, at save time — never on keystroke. */
function headerRowsToRecord(rows: readonly HeaderRow[]): Record<string, string> {
  const headers: Record<string, string> = {}
  for (const row of rows) {
    const key = row.key.trim()
    if (key === '') continue
    headers[key] = row.value
  }
  return headers
}

/** Add/edit form: card layout with a collapsible advanced section for less-common fields. */
function ModelForm({ entry, onDone }: {
  entry: CustomModelEntry | null
  onDone: () => void
}) {
  const save = useModels((state) => state.save)

  const [modelName, setModelName] = useState(entry?.model_name ?? '')
  const [protocol, setProtocol] = useState<ModelProtocol>(entry?.protocol ?? 'anthropic')
  const [baseUrl, setBaseUrl] = useState(entry?.base_url ?? '')
  const [apiKeyDraft, setApiKeyDraft] = useState('')
  const [apiKeyTouched, setApiKeyTouched] = useState(false)
  const [contextWindowText, setContextWindowText] = useState(
    entry?.context_window != null ? String(entry.context_window) : '')
  const [headerRows, setHeaderRows] = useState<HeaderRow[]>(
    () => headerRowsFromRecord(entry?.headers ?? {}))
  const [multimodalChoice, setMultimodalChoice] = useState<MultimodalChoice>(
    () => multimodalChoiceOf(entry?.multimodal))
  const [advancedOpen, setAdvancedOpen] = useState(true)
  const error = useModels((state) => state.error)

  const canSave = modelName.trim() !== '' && baseUrl.trim() !== ''

  return (
    <div className={cardCss.card}>
      <div className={cardCss.cardHeader}>
        <Input
          className={cardCss.cardHeaderName}
          value={modelName}
          disabled={entry != null}
          placeholder="如 my-custom-model"
          aria-label="model name"
          onChange={(event) => { setModelName(event.target.value) }}
        />
        <MenuSelect
          value={protocol}
          label={PROTOCOL_LABELS[protocol]}
          options={PROTOCOL_OPTIONS}
          onChange={(id) => { setProtocol(id as ModelProtocol) }}
          ariaLabel="model protocol"
        />
      </div>
      {error != null && <div className={localCss.error} role="alert">{error}</div>}
      <SettingsRow title="API Key">
        <Input
          className={localCss.grow}
          type="password"
          value={apiKeyDraft}
          placeholder={entry?.has_api_key === true ? '留空则保留原值' : '(optional)'}
          aria-label="api key"
          onChange={(event) => { setApiKeyDraft(event.target.value); setApiKeyTouched(true) }}
        />
      </SettingsRow>
      <button
        type="button"
        className={cardCss.advancedToggle}
        onClick={() => { setAdvancedOpen(!advancedOpen) }}
      >
        {advancedOpen ? <IconChevronUpOutline14 size={9} /> : <IconChevronDownOutline14 size={9} />}
        自定义设置
      </button>
      {advancedOpen && (
        <div className={cardCss.advancedBody}>
          <SettingsRow title="Base URL">
            <Input
              className={localCss.grow}
              value={baseUrl}
              placeholder="https://api.example.com/v1"
              aria-label="base url"
              onChange={(event) => { setBaseUrl(event.target.value) }}
            />
          </SettingsRow>
          <SettingsRow title="Context Window">
            <Input
              className={localCss.grow}
              value={contextWindowText}
              placeholder="(optional; model default)"
              aria-label="context window"
              onChange={(event) => { setContextWindowText(event.target.value) }}
            />
          </SettingsRow>
          <SettingsRow
            title="图片支持"
            description="纯文本端点的图片内容会先交给图片处理模型转写为文字描述"
          >
            <MenuSelect
              value={multimodalChoice}
              label={MULTIMODAL_OPTIONS.find((option) => option.id === multimodalChoice)?.label
                ?? multimodalChoice}
              options={MULTIMODAL_OPTIONS}
              onChange={(id) => { setMultimodalChoice(id as MultimodalChoice) }}
              ariaLabel="multimodal"
            />
          </SettingsRow>
          <SettingsRow title="Headers">
            <div className={cardCss.headerRows}>
              {headerRows.map((row, index) => (
                // eslint-disable-next-line react/no-array-index-key -- rows have no stable id until saved
                <div key={index} className={cardCss.headerRow}>
                  <Input
                    className={localCss.grow}
                    value={row.key}
                    placeholder="Name"
                    aria-label="header name"
                    onChange={(event) => {
                      const next = [...headerRows]
                      next[index] = { ...row, key: event.target.value }
                      setHeaderRows(next)
                    }}
                  />
                  <Input
                    className={localCss.grow}
                    value={row.value}
                    placeholder="Value"
                    aria-label="header value"
                    onChange={(event) => {
                      const next = [...headerRows]
                      next[index] = { ...row, value: event.target.value }
                      setHeaderRows(next)
                    }}
                  />
                  <button
                    type="button"
                    className={`${localCss.iconButton} ${cardCss.headerRemove}`}
                    aria-label="remove header"
                    onClick={() => { setHeaderRows(headerRows.filter((_, i) => i !== index)) }}
                  >
                    <IconCloseOutline16 size={12} />
                  </button>
                </div>
              ))}
              <button
                type="button"
                className={cardCss.addHeaderRow}
                onClick={() => { setHeaderRows([...headerRows, { key: '', value: '' }]) }}
              >
                + 添加请求头
              </button>
            </div>
          </SettingsRow>
        </div>
      )}
      <div className={localCss.inline}>
        <button
          type="button"
          className={localCss.button}
          disabled={!canSave}
          onClick={() => {
            const contextWindow = contextWindowText.trim() === ''
              ? null : Number.parseInt(contextWindowText, 10)
            void save({
              modelName: modelName.trim(),
              protocol,
              baseUrl: baseUrl.trim(),
              ...(apiKeyTouched ? { apiKey: apiKeyDraft } : {}),
              headers: headerRowsToRecord(headerRows),
              contextWindow,
              multimodal: multimodalValueOf(multimodalChoice),
            }).then(onDone)
          }}
        >
          保存
        </button>
        <button type="button" className={localCss.button} onClick={onDone}>
          取消
        </button>
      </div>
    </div>
  )
}

/**
 * Single-provider multi-model onboarding over the standard `/models` discovery
 * protocol. Discovered models are persisted as standard flat entries in
 * `~/.claude/model.json`, preserving 100% compatibility with `/model` and
 * existing single-model configurations.
 */
function DiscoverModelsForm({ existingNames, onDone }: {
  existingNames: ReadonlySet<string>
  onDone: () => void
}) {
  const saveBatch = useModels((state) => state.saveBatch)

  const [protocol, setProtocol] = useState<ModelProtocol>('chat')
  const [baseUrl, setBaseUrl] = useState('')
  const [apiKey, setApiKey] = useState('')
  const [headerRows, setHeaderRows] = useState<HeaderRow[]>([])
  const [advancedOpen, setAdvancedOpen] = useState(false)
  const [defaultContextWindowText, setDefaultContextWindowText] = useState('')
  const [multimodalChoice, setMultimodalChoice] = useState<MultimodalChoice>('unset')

  const [fetching, setFetching] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [endpointUsed, setEndpointUsed] = useState<string | null>(null)
  const [discovered, setDiscovered] = useState<readonly DiscoveredModelEntry[] | null>(null)
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set())
  const [filter, setFilter] = useState('')

  const filtered = useMemo(() => {
    if (discovered == null) return []
    const q = filter.trim().toLowerCase()
    if (q === '') return discovered
    return discovered.filter((m) =>
      m.id.toLowerCase().includes(q)
      || (m.display_name?.toLowerCase().includes(q) ?? false)
      || (m.owned_by?.toLowerCase().includes(q) ?? false))
  }, [discovered, filter])

  const fetchList = (): void => {
    const trimmedUrl = baseUrl.trim()
    if (trimmedUrl === '') return
    setFetching(true)
    setError(null)
    void discoverProviderModels({
      protocol,
      baseUrl: trimmedUrl,
      ...(apiKey.trim() === '' ? {} : { apiKey: apiKey.trim() }),
      headers: headerRowsToRecord(headerRows),
    })
      .then((res) => {
        setEndpointUsed(res.endpoint)
        setDiscovered(res.models)
        // Default-select newly discovered models (not already in model.json);
        // if all already exist, start unselected so user chooses explicitly.
        const fresh = res.models.filter((m) => !existingNames.has(m.id)).map((m) => m.id)
        setSelected(new Set(fresh))
        if (res.models.length === 0) {
          setError(`端点 ${res.endpoint} 返回了空的模型列表`)
        }
      })
      .catch((failure: unknown) => {
        setError(failure instanceof Error ? failure.message : '拉取 /models 列表失败')
        setDiscovered(null)
        setSelected(new Set())
      })
      .finally(() => {
        setFetching(false)
      })
  }

  const toggleId = (id: string): void => {
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  const importSelected = (): void => {
    if (discovered == null || selected.size === 0) return
    setSaving(true)
    setError(null)
    const defaultCw = defaultContextWindowText.trim() === ''
      ? null : Number.parseInt(defaultContextWindowText, 10)
    const picked = discovered
      .filter((m) => selected.has(m.id))
      .map((m) => ({
        model_name: m.id,
        ...(m.context_window != null && m.context_window > 0 ? { context_window: m.context_window } : {}),
      }))
    void saveBatch({
      protocol,
      baseUrl: baseUrl.trim(),
      ...(apiKey.trim() === '' ? {} : { apiKey: apiKey.trim() }),
      headers: headerRowsToRecord(headerRows),
      contextWindow: defaultCw,
      multimodal: multimodalValueOf(multimodalChoice),
      models: picked,
    })
      .then(() => { onDone() })
      .catch((failure: unknown) => {
        setError(failure instanceof Error ? failure.message : '批量导入失败')
      })
      .finally(() => {
        setSaving(false)
      })
  }

  return (
    <div className={cardCss.formCard}>
      <p className={cardCss.formTitle}>通过 /models 协议批量接入（单 Provider 多模型）</p>
      <SettingsRow title="接口协议" description="导入的模型将统一使用此协议，并分别写入 model.json">
        <MenuSelect
          value={protocol}
          label={PROTOCOL_LABELS[protocol]}
          options={PROTOCOL_OPTIONS}
          onChange={(next) => { setProtocol(next as ModelProtocol) }}
          ariaLabel="protocol"
        />
      </SettingsRow>
      <SettingsRow title="接口地址" description="填写 Base URL（如 https://api.deepseek.com/v1），将自动探测 /models">
        <Input
          className={localCss.input}
          value={baseUrl}
          placeholder="https://api.example.com/v1"
          onChange={(e) => { setBaseUrl(e.target.value) }}
        />
      </SettingsRow>
      <SettingsRow title="API Key" description="拉取列表与导入模型共用此密钥">
        <Input
          className={localCss.input}
          type="password"
          value={apiKey}
          placeholder="sk-…"
          onChange={(e) => { setApiKey(e.target.value) }}
        />
      </SettingsRow>
      <button
        type="button"
        className={cardCss.advancedToggle}
        aria-expanded={advancedOpen}
        onClick={() => { setAdvancedOpen((was) => !was) }}
      >
        {advancedOpen ? <IconChevronUpOutline14 /> : <IconChevronDownOutline14 />}
        高级选项（默认上下文窗口 / 多模态 / 自定义请求头）
      </button>
      {advancedOpen && (
        <div className={cardCss.advancedBody}>
          <SettingsRow title="默认上下文窗口" description="上游未返回 context_window 时使用此值（可选）">
            <Input
              className={localCss.input}
              inputMode="numeric"
              value={defaultContextWindowText}
              placeholder="200000"
              onChange={(e) => { setDefaultContextWindowText(e.target.value) }}
            />
          </SettingsRow>
          <SettingsRow title="多模态支持" description="纯文本模型收到图片时会自动转交已配置的图片模型">
            <MenuSelect
              value={multimodalChoice}
              label={MULTIMODAL_OPTIONS.find((o) => o.id === multimodalChoice)?.label ?? ''}
              options={MULTIMODAL_OPTIONS}
              onChange={(next) => { setMultimodalChoice(next as MultimodalChoice) }}
              ariaLabel="multimodal"
            />
          </SettingsRow>
          <SettingsRow title="自定义请求头" description="拉取 /models 与后续模型请求都会携带这些请求头">
            <div className={cardCss.headerEditor}>
              {headerRows.map((row, index) => (
                <div key={index} className={cardCss.headerPair}>
                  <Input
                    className={cardCss.headerKey}
                    value={row.key}
                    placeholder="Header"
                    onChange={(e) => {
                      const next = headerRows.slice()
                      next[index] = { ...row, key: e.target.value }
                      setHeaderRows(next)
                    }}
                  />
                  <Input
                    className={cardCss.headerVal}
                    value={row.value}
                    placeholder="Value"
                    onChange={(e) => {
                      const next = headerRows.slice()
                      next[index] = { ...row, value: e.target.value }
                      setHeaderRows(next)
                    }}
                  />
                  <button
                    type="button"
                    className={`${localCss.iconButton} ${cardCss.headerRemove}`}
                    aria-label="remove header"
                    onClick={() => { setHeaderRows(headerRows.filter((_, i) => i !== index)) }}
                  >
                    <IconCloseOutline16 size={12} />
                  </button>
                </div>
              ))}
              <button
                type="button"
                className={cardCss.addHeaderRow}
                onClick={() => { setHeaderRows([...headerRows, { key: '', value: '' }]) }}
              >
                + 添加请求头
              </button>
            </div>
          </SettingsRow>
        </div>
      )}

      <div className={localCss.inline}>
        <button
          type="button"
          className={localCss.button}
          disabled={fetching || baseUrl.trim() === ''}
          onClick={fetchList}
        >
          {fetching ? '正在拉取…' : '拉取模型列表'}
        </button>
        <button type="button" className={localCss.button} onClick={onDone}>
          取消
        </button>
      </div>

      {error != null && (
        <div role="alert" style={{ fontSize: 12, color: 'var(--dsw-alias-state-error, #d93025)' }}>
          {error}
        </div>
      )}

      {discovered != null && discovered.length > 0 && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginTop: 4 }}>
          <div style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 8,
            flexWrap: 'wrap',
            fontSize: 12,
            color: 'var(--dsw-alias-label-secondary)',
          }}>
            <span title={endpointUsed ?? undefined}>
              共发现 {discovered.length} 个模型，已选 {selected.size} 个
            </span>
            <div style={{ display: 'flex', gap: 6 }}>
              <button
                type="button"
                className={localCss.button}
                onClick={() => { setSelected(new Set(filtered.map((m) => m.id))) }}
              >
                全选当前
              </button>
              <button
                type="button"
                className={localCss.button}
                onClick={() => {
                  setSelected(new Set(filtered.filter((m) => !existingNames.has(m.id)).map((m) => m.id)))
                }}
              >
                仅选新模型
              </button>
              <button
                type="button"
                className={localCss.button}
                onClick={() => { setSelected(new Set()) }}
              >
                清空
              </button>
            </div>
          </div>

          <Input
            className={localCss.input}
            value={filter}
            placeholder="筛选模型 ID 或名称…"
            onChange={(e) => { setFilter(e.target.value) }}
          />

          <div style={{
            maxHeight: 220,
            overflowY: 'auto',
            border: '1px solid var(--dsw-alias-border-l2)',
            borderRadius: 10,
            padding: 4,
            display: 'flex',
            flexDirection: 'column',
            gap: 2,
          }}>
            {filtered.length === 0 && (
              <div style={{ padding: 10, fontSize: 12, color: 'var(--dsw-alias-label-tertiary)' }}>
                没有匹配的模型
              </div>
            )}
            {filtered.map((m) => {
              const checked = selected.has(m.id)
              const exists = existingNames.has(m.id)
              return (
                <label
                  key={m.id}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 8,
                    padding: '6px 8px',
                    borderRadius: 8,
                    cursor: 'pointer',
                    fontSize: 13,
                    background: checked ? 'var(--dsw-alias-interactive-bg-hover)' : 'transparent',
                  }}
                >
                  <input
                    type="checkbox"
                    checked={checked}
                    onChange={() => { toggleId(m.id) }}
                  />
                  <span style={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {m.id}
                    {m.display_name != null && m.display_name !== m.id && (
                      <span style={{ marginLeft: 6, color: 'var(--dsw-alias-label-tertiary)', fontSize: 12 }}>
                        {m.display_name}
                      </span>
                    )}
                  </span>
                  {m.context_window != null && (
                    <Pill>{Math.round(m.context_window / 1000)}K</Pill>
                  )}
                  {exists && <Pill>已存在（将覆盖）</Pill>}
                </label>
              )
            })}
          </div>

          <div className={localCss.inline}>
            <button
              type="button"
              className={localCss.button}
              disabled={saving || selected.size === 0}
              onClick={importSelected}
            >
              {saving ? '正在导入…' : `一键导入已选 (${selected.size})`}
            </button>
          </div>
        </div>
      )}
    </div>
  )
}

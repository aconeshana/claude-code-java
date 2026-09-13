import { useEffect, useState } from 'react'
import { Input, Pill } from '@primitives'
import { IconChevronDownOutline14, IconChevronUpOutline14, IconCloseOutline16 } from '@primitives'
import { useModels } from '../store/models'
import type { CustomModelEntry, ModelProtocol } from '../api/types'
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

  useEffect(() => {
    void refresh()
  }, [refresh])

  return (
    <>
      <p className={cardCss.intro}>与 /model 命令共享同一份目录，此处新增或编辑的条目在两处均立即生效。</p>
      {editing !== undefined && (
        <ModelForm entry={editing} onDone={() => { setEditing(undefined) }} />
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
            <button type="button" className={localCss.button} onClick={() => { setEditing(entry) }}>
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
      {editing === undefined && (
        <button type="button" className={cardCss.addTrigger} onClick={() => { setEditing(null) }}>
          + 添加模型
        </button>
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

import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import clsx from 'clsx'
import {
  Button,
  IconApiOutline14, IconChecklistOutline14, IconChevronDownOutline14, IconChevronUpOutline14,
  IconClockOutline16, IconCloseOutline16, IconDarkOutline16, IconFolderOpenOutline16,
  IconFollowsystemOutline16, IconLightOutline16, IconRefreshOutline16, IconSettingsOutline16,
  Input, Menu, Pill, Switch,
} from '@primitives'
import {
  fetchKeepaliveState,
  restartGatewayRuntime,
  setKeepaliveEnabled,
  type RuntimeKeepaliveState,
} from '../api/client'
import { useModels } from '../store/models'
import { useSchedule } from '../store/schedule'
import { useSessions } from '../store/sessions'
import { useComposerCommands } from '../store/commands'
import css from '@chat-styles/SettingsRoot.module.css'
import sectionCss from '@chat-styles/GeneralSection.module.css'
import rowCss from '@chat-styles/PermissionRow.module.css'
import appearanceCss from '@chat-styles/AppearanceRow.module.css'
import fontSizeCss from '@chat-styles/FontSizeRow.module.css'
import { useSettings } from '../store/settings'
import { useTheme } from '../store/theme'
import { useTranscriptView } from '../store/transcriptView'
import { useEnterBehavior } from '../store/enterBehavior'
import { useLocale } from '../store/locale'
import { FONT_SIZE_MAX, FONT_SIZE_MIN, THEME_PREFERENCES, type ThemePreference } from '../theme'
import { LOCALES, type LocaleId } from '../i18n/types'
import { useTranslate } from '../i18n/useTranslate'
import { SETTINGS_NS, settingsDicts } from '../i18n/dictionaries/settings'
import type { PermissionBehaviorKind, SettingsTier } from '../api/types'
import localCss from './SettingsPanel.module.css'
import { ContextSettingsSection } from './context/ContextSettingsSection'
import { ContextIcon } from '@dsh-context/client/icon'
import { ModelsSection } from './ModelsSection'
import { ScheduleSection } from './SchedulePanel'

/**
 * Settings dialog: reads the effective (merged, tier-attributed) settings
 * snapshot and offers the v1 write subset the gateway ops expose — the
 * alwaysThinkingEnabled user-tier scalar, permissions.defaultMode per tier,
 * the allow/deny/ask rule arrays plus additionalDirectories per tier, and
 * (via `ModelsSection`) the `/model` command's custom model catalogue.
 * Every write's response is the refreshed snapshot, so the view updates
 * without a second fetch.
 *
 * Chrome (overlay/mask/nav-rail panel/header/options scroll) is vendored
 * verbatim from dsh's `SettingsRoot.module.css`; field rows reuse
 * `PermissionRow.module.css`'s row/selector pattern. Four real differences
 * from dsh's own Settings panel: (1) the section list here is
 * general/permissions/directories/models/schedule, not dsh's
 * models/agent-presets/plugins, because that's the write surface
 * `SettingsEditor` actually exposes; (2) the permission section edits raw
 * allow/deny/ask rule arrays per tier rather than dsh's three named presets
 * — `SettingsEditor` has no preset concept, only `replacePermissionRules`,
 * so a literal port of dsh's `PermissionRow` preset picker is not possible
 * without a backend change; (3) the models section is a flat
 * `CustomModelConfig` list, not dsh's provider-grouped `ModelsSection` with
 * per-provider `ProviderEditor` cards — our backend catalogue has no
 * provider grouping concept; (4) the scheduled-task section exposes the
 * gateway schedule port's add/remove round-trips (dsh's own schedule surface
 * is a conversation-header read-only catalog — "creating and deleting
 * reminders remain with the Schedule tools", its README).
 */

type SectionId = 'general' | 'permissions' | 'directories' | 'models' | 'schedule' | 'context'

const SECTIONS: readonly { id: SectionId; label: string; icon: ReactNode }[] = [
  { id: 'general', label: '常规', icon: <IconSettingsOutline16 className={css.navIcon} size={16} /> },
  { id: 'permissions', label: '权限', icon: <IconChecklistOutline14 className={css.navIcon} size={16} /> },
  { id: 'directories', label: '目录', icon: <IconFolderOpenOutline16 className={css.navIcon} size={16} /> },
  { id: 'models', label: '模型', icon: <IconApiOutline14 className={css.navIcon} size={16} /> },
  { id: 'schedule', label: '定时任务', icon: <IconClockOutline16 className={css.navIcon} size={16} /> },
  { id: 'context', label: '上下文', icon: <ContextIcon className={css.navIcon} size={16} /> },
]

const TIERS: readonly SettingsTier[] = ['user', 'project', 'local']
const MODES: readonly string[] = ['default', 'plan', 'acceptEdits', 'auto', 'dontAsk']
const UNSET_MODE = '__unset__'
const BEHAVIORS: readonly { key: PermissionBehaviorKind; label: string }[] = [
  { key: 'allow', label: '允许' },
  { key: 'deny', label: '拒绝' },
  { key: 'ask', label: '询问' },
]

const THEME_CUBE_ICONS: Readonly<Record<ThemePreference, typeof IconLightOutline16>> = {
  light: IconLightOutline16, dark: IconDarkOutline16, system: IconFollowsystemOutline16,
}

export function SettingsPanel({ open, onClose }: {
  open: boolean
  onClose: () => void
}) {
  const t = useTranslate(SETTINGS_NS, settingsDicts)
  const effective = useSettings((state) => state.effective)
  const sources = useSettings((state) => state.sources)
  const loading = useSettings((state) => state.loading)
  const error = useSettings((state) => state.error)
  const refresh = useSettings((state) => state.refresh)
  const writeUserValue = useSettings((state) => state.writeUserValue)
  const writePermissionMode = useSettings((state) => state.writePermissionMode)
  const replacePermissionRules = useSettings((state) => state.replacePermissionRules)
  const addDirectories = useSettings((state) => state.addDirectories)
  const removeDirectories = useSettings((state) => state.removeDirectories)
  const transcriptViewMode = useTranscriptView((state) => state.mode)
  const setTranscriptViewMode = useTranscriptView((state) => state.setMode)
  const enterBehavior = useEnterBehavior((state) => state.behavior)
  const setEnterBehavior = useEnterBehavior((state) => state.setBehavior)

  const [activeSection, setActiveSection] = useState<SectionId>('general')

  const [modeTier, setModeTier] = useState<SettingsTier>('project')
  const [rulesTier, setRulesTier] = useState<SettingsTier>('project')
  const [dirTier, setDirTier] = useState<SettingsTier>('project')
  const [newRule, setNewRule] = useState<Record<PermissionBehaviorKind, string>>({
    allow: '', deny: '', ask: '',
  })
  const [newDirectory, setNewDirectory] = useState('')

  useEffect(() => {
    if (!open) return
    void refresh()
  }, [open, refresh])

  if (!open) return null

  const permissions = effectiveObject(effective?.permissions)
  const defaultMode = typeof permissions.defaultMode === 'string'
    ? permissions.defaultMode : ''
  const additionalDirectories = stringArray(permissions.additionalDirectories)

  const navLabelOf = (id: SectionId): string => {
    switch (id) {
      case 'general': return t('nav.general')
      case 'permissions': return t('nav.permissions')
      case 'directories': return t('nav.directories')
      case 'models': return t('nav.models')
      case 'schedule': return t('nav.schedule')
      case 'context': return t('nav.context')
    }
  }
  const transcriptOptions = [
    { id: 'normal', label: t('transcript.normal') },
    { id: 'compact', label: t('transcript.compact') },
  ]
  const enterBehaviorOptions = [
    { id: 'queue', label: t('enterBehavior.queue') },
    { id: 'steer', label: t('enterBehavior.steer') },
  ]

  return (
    <div className={css.overlay} role="presentation">
      <div className={css.mask} aria-hidden="true" onClick={onClose} />
      <div className={css.panel} role="dialog" aria-modal="true" aria-label={t('panel.title')}>
        <nav className={css.nav}>
          <div className={css.navTitle}>{t('panel.title')}</div>
          <div className={css.navList}>
            {SECTIONS.map((section) => (
              <button
                key={section.id}
                type="button"
                className={clsx(css.navCell, section.id === activeSection && css.active)}
                aria-current={section.id === activeSection ? 'true' : undefined}
                onClick={() => { setActiveSection(section.id) }}
              >
                {section.icon}
                <span className={css.navLabel}>{navLabelOf(section.id)}</span>
              </button>
            ))}
          </div>
        </nav>
        <div className={css.content}>
          <div className={css.header}>
            <div className={css.actions}>
              {loading && <span className={localCss.hint}>{t('panel.loading')}</span>}
              <button
                type="button"
                className={localCss.iconButton}
                aria-label={t('panel.reload')}
                onClick={() => { void refresh() }}
              >
                <IconRefreshOutline16 size={14} />
              </button>
            </div>
            <button type="button" className={css.close} onClick={onClose}>
              <IconCloseOutline16 size={14} />
              <span className={css.hiddenLabel}>{t('panel.close')}</span>
            </button>
          </div>
          <div className={css.options}>
            {error != null && <div className={localCss.error} role="alert">{error}</div>}

            {activeSection === 'general' && (
              <div className={sectionCss.section}>
                <LanguageRow />
                <AppearanceRow />
                <FontSizeRow />
                <SettingsRow title={t('transcript.title')} description={t('transcript.desc')}>
                  <MenuSelect
                    value={transcriptViewMode}
                    label={transcriptViewMode === 'normal' ? t('transcript.normal') : t('transcript.compact')}
                    options={transcriptOptions}
                    onChange={(id) => { setTranscriptViewMode(id as 'normal' | 'compact') }}
                    ariaLabel="transcript view mode"
                  />
                </SettingsRow>
                <SettingsRow title={t('enterBehavior.title')} description={t('enterBehavior.desc')}>
                  <MenuSelect
                    value={enterBehavior}
                    label={enterBehavior === 'queue' ? t('enterBehavior.queue') : t('enterBehavior.steer')}
                    options={enterBehaviorOptions}
                    onChange={(id) => { setEnterBehavior(id as 'queue' | 'steer') }}
                    ariaLabel="busy enter behavior"
                  />
                </SettingsRow>
                <SettingsRow
                  title={t('alwaysThinking.title')}
                  description={<SourceBadge sources={sources} field="alwaysThinkingEnabled" />}
                >
                  <Switch
                    checked={effective?.alwaysThinkingEnabled === true}
                    onChange={(next) => { void writeUserValue('alwaysThinkingEnabled', next) }}
                    label="alwaysThinkingEnabled"
                  />
                </SettingsRow>
                <KeepaliveRow />
                <RestartRuntimeRow onReloadAll={() => {
                  void refresh()
                  void useSessions.getState().refresh()
                  void useModels.getState().refresh()
                  void useSchedule.getState().refresh()
                  void useComposerCommands.getState().refresh()
                }} />
              </div>
            )}

            {activeSection === 'permissions' && (
              <div className={sectionCss.section}>
                <SettingsRow title="默认模式写入层级">
                  <TierMenu value={modeTier} onChange={setModeTier} ariaLabel="permission mode tier" />
                </SettingsRow>
                <SettingsRow title="默认权限模式" description={`当前生效：${defaultMode || 'default'}`}>
                  <ModeMenu
                    value={defaultMode}
                    onChange={(mode) => { void writePermissionMode(mode === UNSET_MODE ? null : mode, modeTier) }}
                  />
                </SettingsRow>
                <SettingsRow title="规则写入层级">
                  <TierMenu value={rulesTier} onChange={setRulesTier} ariaLabel="rules tier" />
                </SettingsRow>
                {BEHAVIORS.map(({ key, label }) => (
                  <RuleList
                    key={key}
                    label={label}
                    rules={stringArray(permissions[key])}
                    draft={newRule[key]}
                    onDraftChange={(value) => { setNewRule({ ...newRule, [key]: value }) }}
                    onAdd={() => {
                      const rule = newRule[key].trim()
                      if (rule === '') return
                      void replacePermissionRules(key, [...stringArray(permissions[key]), rule], rulesTier)
                      setNewRule({ ...newRule, [key]: '' })
                    }}
                    onRemove={(rule) => {
                      void replacePermissionRules(
                        key, stringArray(permissions[key]).filter((entry) => entry !== rule), rulesTier)
                    }}
                  />
                ))}
              </div>
            )}

            {activeSection === 'directories' && (
              <div className={sectionCss.section}>
                <SettingsRow title="写入层级">
                  <TierMenu value={dirTier} onChange={setDirTier} ariaLabel="directories tier" />
                </SettingsRow>
                <ul className={localCss.list}>
                  {additionalDirectories.length === 0 && (
                    <li className={localCss.empty}>（无）</li>
                  )}
                  {additionalDirectories.map((directory) => (
                    <li key={directory} className={localCss.listRow}>
                      <span className={localCss.listLabel} title={directory}>{directory}</span>
                      <button
                        type="button"
                        className={localCss.button}
                        onClick={() => { void removeDirectories([directory], dirTier) }}
                      >
                        移除
                      </button>
                    </li>
                  ))}
                </ul>
                <div className={localCss.inline}>
                  <Input
                    className={localCss.grow}
                    value={newDirectory}
                    placeholder="/absolute/path"
                    aria-label="new directory"
                    onChange={(event) => { setNewDirectory(event.target.value) }}
                  />
                  <button
                    type="button"
                    className={localCss.button}
                    disabled={newDirectory.trim() === ''}
                    onClick={() => {
                      void addDirectories([newDirectory.trim()], dirTier)
                      setNewDirectory('')
                    }}
                  >
                    添加
                  </button>
                </div>
              </div>
            )}

            {activeSection === 'models' && (
              <div className={sectionCss.section}>
                <ModelsSection />
              </div>
            )}

            {activeSection === 'context' && <ContextSettingsSection />}

            {activeSection === 'schedule' && (
              <div className={sectionCss.section}>
                <ScheduleSection />
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}

const LANGUAGE_OPTIONS: readonly { id: string; label: string }[] = LOCALES.map((l) => ({ id: l.id, label: l.label }))

/** Language preference: title (via i18n) + a 2-option locale selector, vendored from dsh's `LanguageRow`. */
function LanguageRow() {
  const t = useTranslate(SETTINGS_NS, settingsDicts)
  const locale = useLocale((state) => state.locale)
  const setLocale = useLocale((state) => state.setLocale)
  const active = LOCALES.find((l) => l.id === locale)
  return (
    <SettingsRow title={t('language.title')}>
      <MenuSelect
        value={locale}
        label={active?.label ?? locale}
        options={LANGUAGE_OPTIONS}
        onChange={(id) => { setLocale(id as LocaleId) }}
        ariaLabel="language"
      />
    </SettingsRow>
  )
}

/** Appearance preference: title + three theme cubes, vendored from dsh's `AppearanceRow`. */
function AppearanceRow() {
  const t = useTranslate(SETTINGS_NS, settingsDicts)
  const preference = useTheme((state) => state.preference)
  const setPreference = useTheme((state) => state.setPreference)
  const themeLabel = (id: ThemePreference): string => {
    if (id === 'light') return t('appearance.light')
    if (id === 'dark') return t('appearance.dark')
    return t('appearance.system')
  }
  return (
    <div className={appearanceCss.group}>
      <div className={appearanceCss.title}>{t('appearance.title')}</div>
      <div className={appearanceCss.cubeRow}>
        {THEME_PREFERENCES.map((id) => {
          const Icon = THEME_CUBE_ICONS[id]
          return (
            <button
              key={id}
              type="button"
              className={clsx(appearanceCss.themeCube, preference === id && appearanceCss.selected)}
              aria-pressed={preference === id}
              onClick={() => { setPreference(id) }}
            >
              <Icon />
              {themeLabel(id)}
            </button>
          )
        })}
      </div>
    </div>
  )
}

function KeepaliveRow() {
  const t = useTranslate(SETTINGS_NS, settingsDicts)
  const [state, setState] = useState<RuntimeKeepaliveState | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let active = true
    void fetchKeepaliveState().then((res) => {
      if (active) setState(res)
    }).catch(() => { /* endpoint unavailable on older gateways */ })
    return () => { active = false }
  }, [])

  const supported = state?.supported !== false
  return (
    <SettingsRow
      title={t('keepalive.title')}
      description={supported ? t('keepalive.desc') : t('keepalive.unsupported')}
    >
      <Switch
        checked={state?.enabled === true}
        disabled={busy || !supported}
        onChange={(next) => {
          setBusy(true)
          void setKeepaliveEnabled(next).then((res) => {
            setState(res)
          }).finally(() => { setBusy(false) })
        }}
        label={t('keepalive.title')}
      />
    </SettingsRow>
  )
}

function RestartRuntimeRow({ onReloadAll }: { onReloadAll: () => void }) {
  const t = useTranslate(SETTINGS_NS, settingsDicts)
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)

  async function handleRestart() {
    if (busy) return
    setBusy(true)
    setNotice(null)
    try {
      const res = await restartGatewayRuntime()
      setNotice(
        t('restart.done')
          .replace('{tasks}', String(res.reloaded_tasks))
          .replace('{models}', String(res.reloaded_models)),
      )
      onReloadAll()
    } finally {
      setBusy(false)
    }
  }

  return (
    <SettingsRow title={t('restart.title')} description={notice ?? t('restart.desc')}>
      <Button variant="outline" size="sm" disabled={busy} onClick={() => { void handleRestart() }}>
        {busy ? t('restart.busy') : t('restart.action')}
      </Button>
    </SettingsRow>
  )
}

/** Font-size preference: title/description + stepper pill, vendored from dsh's `FontSizeRow`. */
function FontSizeRow() {
  const t = useTranslate(SETTINGS_NS, settingsDicts)
  const fontSize = useTheme((state) => state.fontSize)
  const setFontSize = useTheme((state) => state.setFontSize)
  return (
    <div className={fontSizeCss.row}>
      <div className={fontSizeCss.rowText}>
        <div className={fontSizeCss.title}>{t('fontSize.title')}</div>
        <div className={fontSizeCss.desc}>调整对话内容的文字大小</div>
      </div>
      <div className={fontSizeCss.control}>
        <div className={fontSizeCss.stepper}>
          <span className={fontSizeCss.value}>{fontSize}</span>
          <span className={fontSizeCss.arrows}>
            <button
              type="button"
              className={fontSizeCss.arrow}
              aria-label="增大字号"
              disabled={fontSize >= FONT_SIZE_MAX}
              onClick={() => { setFontSize(fontSize + 1) }}
            >
              <IconChevronUpOutline14 size={9} />
            </button>
            <button
              type="button"
              className={fontSizeCss.arrow}
              aria-label="减小字号"
              disabled={fontSize <= FONT_SIZE_MIN}
              onClick={() => { setFontSize(fontSize - 1) }}
            >
              <IconChevronDownOutline14 size={9} />
            </button>
          </span>
        </div>
        <span className={fontSizeCss.unit}>px</span>
      </div>
    </div>
  )
}

/** One field row: title/description on the left, a control on the right — dsh's `PermissionRow` shape. */
export function SettingsRow({ title, description, children }: {
  title: string
  description?: ReactNode
  children: ReactNode
}) {
  return (
    <div className={rowCss.row}>
      <div className={rowCss.rowText}>
        <div className={rowCss.title}>{title}</div>
        {description !== undefined && <div className={rowCss.desc}>{description}</div>}
      </div>
      {children}
    </div>
  )
}

/** The pill-with-chevron selector button dsh's `PermissionRow` opens its preset `Menu` from. */
export function MenuSelect({ value, label, options, onChange, ariaLabel }: {
  value: string
  label: string
  options: readonly { id: string; label: string }[]
  onChange: (id: string) => void
  ariaLabel: string
}) {
  const [open, setOpen] = useState(false)
  return (
    <Menu
      open={open}
      onClose={() => { setOpen(false) }}
      items={options}
      selectedId={value}
      onSelect={(id) => { setOpen(false); onChange(id) }}
      align="end"
      portal
      anchor={(
        <button
          type="button"
          className={rowCss.selector}
          aria-haspopup="menu"
          aria-expanded={open}
          aria-label={ariaLabel}
          onClick={() => { setOpen((current) => !current) }}
        >
          {label}
          <IconChevronDownOutline14 className={rowCss.chevron} />
        </button>
      )}
    />
  )
}

function TierMenu({ value, onChange, ariaLabel }: {
  value: SettingsTier
  onChange: (tier: SettingsTier) => void
  ariaLabel: string
}) {
  const options = TIERS.map((tier) => ({ id: tier, label: tierLabel(tier) }))
  return (
    <MenuSelect
      value={value}
      label={tierLabel(value)}
      options={options}
      onChange={(id) => { onChange(id as SettingsTier) }}
      ariaLabel={ariaLabel}
    />
  )
}

function ModeMenu({ value, onChange }: {
  value: string
  onChange: (mode: string) => void
}) {
  const options = [
    { id: UNSET_MODE, label: '（未设置 → default）' },
    ...MODES.map((mode) => ({ id: mode, label: mode })),
  ]
  return (
    <MenuSelect
      value={value === '' ? UNSET_MODE : value}
      label={value === '' ? '（未设置 → default）' : value}
      options={options}
      onChange={onChange}
      ariaLabel="permission default mode"
    />
  )
}

function RuleList({ label, rules, draft, onDraftChange, onAdd, onRemove }: {
  label: string
  rules: readonly string[]
  draft: string
  onDraftChange: (value: string) => void
  onAdd: () => void
  onRemove: (rule: string) => void
}) {
  return (
    <div className={localCss.ruleGroup}>
      <div className={localCss.ruleGroupTitle}>{label}</div>
      <ul className={localCss.list}>
        {rules.length === 0 && <li className={localCss.empty}>（无）</li>}
        {rules.map((rule) => (
          <li key={rule} className={localCss.listRow}>
            <code className={localCss.listLabel} title={rule}>{rule}</code>
            <button type="button" className={localCss.button} onClick={() => { onRemove(rule) }}>
              移除
            </button>
          </li>
        ))}
      </ul>
      <div className={localCss.inline}>
        <Input
          className={localCss.grow}
          value={draft}
          placeholder="如 Bash(git status:*)"
          aria-label={`new ${label} rule`}
          onChange={(event) => { onDraftChange(event.target.value) }}
        />
        <button
          type="button"
          className={localCss.button}
          disabled={draft.trim() === ''}
          onClick={onAdd}
        >
          添加
        </button>
      </div>
    </div>
  )
}

/**
 * Marks which tier a field's effective value came from, when the snapshot's
 * source rows attribute it.
 */
function SourceBadge({ sources, field }: {
  sources: readonly { source: string; settings: Readonly<Record<string, unknown>> }[]
  field: string
}) {
  for (let i = sources.length - 1; i >= 0; i--) {
    // Later tiers override earlier ones, so the last row defining the field
    // owns the effective value.
    if (field in sources[i].settings) {
      return <Pill>{tierName(sources[i].source)}</Pill>
    }
  }
  return null
}

function tierName(source: string): string {
  switch (source) {
    case 'userSettings': return 'user'
    case 'projectSettings': return 'project'
    case 'localSettings': return 'local'
    case 'policySettings': return 'policy'
    case 'flagSettings': return 'flag'
    default: return source
  }
}

function tierLabel(tier: SettingsTier): string {
  switch (tier) {
    case 'user': return 'user'
    case 'project': return 'project'
    case 'local': return 'local'
  }
}

function effectiveObject(value: unknown): Readonly<Record<string, unknown>> {
  return value != null && typeof value === 'object' && !Array.isArray(value)
    ? value as Readonly<Record<string, unknown>>
    : {}
}

function stringArray(value: unknown): readonly string[] {
  return Array.isArray(value) && value.every((entry) => typeof entry === 'string')
    ? value as readonly string[]
    : []
}

/**
 * The settings dialog's "Context" section — claude-code-java seat of
 * dsh-context's `settingsCard.tsx`: the Context tab's default view
 * preferences (Dashboard entry, trend granularity/mode, tool and file sort),
 * persisted by the vendored `contextSettings` store in localStorage (this
 * app has no per-plugin settings scope). Rendered as rows of the host
 * dialog (SettingsRow + MenuSelect) rather than upstream's fold-out card so
 * it reads like the sibling sections. The placement row is not offered:
 * the tab is the only Context surface this shell mounts.
 */

import { useEffect, useState, type ReactElement } from 'react'
import { MenuSelect, SettingsRow } from '../SettingsPanel'
import { useContextKit } from './useContextKit'
import { contextSettings, type SettingsField } from '@dsh-context/client/settings'
import sectionCss from '@chat-styles/GeneralSection.module.css'

export function ContextSettingsSection(): ReactElement {
  const { kit } = useContextKit()
  const { t } = kit
  const [snap, setSnap] = useState(() => contextSettings.store.getSnapshot())
  useEffect(() => contextSettings.store.subscribe(() => { setSnap(contextSettings.store.getSnapshot()) }), [])
  const write = (field: SettingsField) => (id: string): void => { contextSettings.set(field, id) }

  const rows: { field: SettingsField; label: string; value: string; options: { id: string; label: string }[] }[] = [
    {
      field: 'insightsEntry', label: t('settings.insightsEntry'), value: snap.insightsEntry,
      options: [{ id: 'show', label: t('insightsEntry.show') }, { id: 'hide', label: t('insightsEntry.hide') }],
    },
    {
      field: 'defaultGranularity', label: t('settings.gran'), value: snap.granularity,
      options: [{ id: 'step', label: t('gran.step') }, { id: 'turn', label: t('gran.turn') }],
    },
    {
      field: 'defaultTrendMode', label: t('settings.mode'), value: snap.mode,
      options: [{ id: 'total', label: t('gran.total') }, { id: 'delta', label: t('gran.delta') }],
    },
    {
      field: 'defaultToolSort', label: t('settings.toolSort'), value: snap.toolSort,
      options: [
        { id: 'size', label: t('tool.sort.size') },
        { id: 'count', label: t('tool.sort.count') },
        { id: 'name', label: t('tool.sort.name') },
      ],
    },
    {
      field: 'defaultFileSort', label: t('settings.fileSort'), value: snap.fileSort,
      options: [
        { id: 'count', label: t('files.sort.count') },
        { id: 'latest', label: t('files.sort.latest') },
        { id: 'path', label: t('files.sort.path') },
      ],
    },
  ]

  return (
    <div className={sectionCss.section}>
      <SettingsRow title={t('shell.settings.title')} description={t('shell.settings.desc')}>
        <span />
      </SettingsRow>
      {rows.map((row) => (
        <SettingsRow key={row.field} title={row.label}>
          <MenuSelect
            value={row.value}
            label={row.options.find((option) => option.id === row.value)?.label ?? row.value}
            options={row.options}
            onChange={write(row.field)}
            ariaLabel={row.label}
          />
        </SettingsRow>
      ))}
    </div>
  )
}

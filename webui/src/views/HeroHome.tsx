import { useCallback, useMemo, useState } from 'react'
import {
  IconChevronDownOutline14,
  IconFolderOpenOutline16,
  IconPlusOutline16,
  Menu,
} from '@primitives'
import type { MenuEntry } from '@primitives'
import css from '@chat-styles/HeroShell.module.css'
import { DirectoryBrowser } from '@vendor/ui-directory-picker-browse/DirectoryBrowser'
import {
  DIRECTORY_BROWSER_NS,
  directoryBrowserDicts,
} from '@vendor/ui-directory-picker-browse/locales'
import {
  createDirectory,
  listDirectory,
  openHeadlessSession,
  submitTurn,
} from '../api/client'
import type { UserContentBlock } from '../api/types'
import { UI_NS, uiDicts } from '../i18n/dictionaries/ui'
import { useTranslate } from '../i18n/useTranslate'
import { useSessions } from '../store/sessions'
import { useWorkspacePrefs } from '../store/workspacePrefs'
import { toast } from '../store/toast'
import { CodeOrbMark } from './brand/CodeOrbMark'
import { InputBar } from './InputBar'
import { stripScheduledSessions } from './scheduledSessions'

const ADD_WORKSPACE_ID = '__add_workspace__'
const DEFAULT_WORKSPACE_ID = '__default_workspace__'

function basenameOf(path: string): string {
  const trimmed = path.replace(/[\\/]+$/, '')
  if (trimmed === '') return path
  const slash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'))
  return slash >= 0 ? trimmed.slice(slash + 1) : trimmed
}

export interface HeroHomeProps {
  readonly onClientCommand?: ((command: string) => boolean) | undefined
}

/**
 * Default landing hero (vendored dsh HeroShell.module.css): brand mark + title,
 * workspace chip selector backed by the vendored Miller-column DirectoryBrowser
 * (supports browsing host directories and creating new project folders), and
 * centered InputBar. Submitting the first message creates a fresh headless
 * session in the selected workspace and transitions smoothly into the
 * conversation view.
 */
export function HeroHome({ onClientCommand }: HeroHomeProps) {
  const projects = useSessions((state) => state.projects)
  const addedWorkspaces = useWorkspacePrefs((state) => state.addedWorkspaces)
  const activeHeroWorkspace = useWorkspacePrefs((state) => state.activeHeroWorkspace)
  const addWorkspace = useWorkspacePrefs((state) => state.addWorkspace)
  const setActiveHeroWorkspace = useWorkspacePrefs((state) => state.setActiveHeroWorkspace)
  const uiT = useTranslate(UI_NS, uiDicts)
  const dirT = useTranslate(DIRECTORY_BROWSER_NS, directoryBrowserDicts)

  const { regularProjects } = useMemo(
    () => stripScheduledSessions(projects),
    [projects],
  )

  const [menuOpen, setMenuOpen] = useState(false)
  const [pickerOpen, setPickerOpen] = useState(false)
  const [submitting, setSubmitting] = useState(false)

  const effectiveWorkspace = activeHeroWorkspace
    ?? addedWorkspaces[0]
    ?? regularProjects[0]?.project_path
    ?? null

  const workspaceLabel = useMemo(() => {
    if (effectiveWorkspace == null) return '默认工作区'
    const matched = regularProjects.find((p) => p.project_path === effectiveWorkspace)
    return matched?.project_name || basenameOf(effectiveWorkspace)
  }, [effectiveWorkspace, regularProjects])

  const menuItems = useMemo<readonly MenuEntry[]>(() => {
    const seen = new Set<string>()
    const items: MenuEntry[] = []

    for (const path of addedWorkspaces) {
      if (seen.has(path)) continue
      seen.add(path)
      const matched = regularProjects.find((p) => p.project_path === path)
      items.push({
        id: path,
        label: matched?.project_name || basenameOf(path),
        icon: <IconFolderOpenOutline16 size={14} />,
      })
    }

    for (const project of regularProjects) {
      if (seen.has(project.project_path)) continue
      seen.add(project.project_path)
      items.push({
        id: project.project_path,
        label: project.project_name || basenameOf(project.project_path),
        icon: <IconFolderOpenOutline16 size={14} />,
      })
    }

    if (items.length === 0) {
      items.push({
        id: DEFAULT_WORKSPACE_ID,
        label: '默认工作区',
        icon: <IconFolderOpenOutline16 size={14} />,
      })
    }
    return items
  }, [regularProjects, addedWorkspaces])

  const menuFooter = useMemo<readonly MenuEntry[]>(() => ([
    {
      id: ADD_WORKSPACE_ID,
      label: '添加工作区',
      icon: <IconPlusOutline16 size={14} />,
    },
  ]), [])

  const handleSelectWorkspace = useCallback((id: string) => {
    setMenuOpen(false)
    if (id === ADD_WORKSPACE_ID) {
      setPickerOpen(true)
      return
    }
    if (id === DEFAULT_WORKSPACE_ID) {
      setActiveHeroWorkspace(null)
      return
    }
    setActiveHeroWorkspace(id)
  }, [setActiveHeroWorkspace])

  const handlePickedDirectory = useCallback((pickedPath: string) => {
    addWorkspace(pickedPath)
    setPickerOpen(false)
    toast.success(`已选择工作区「${basenameOf(pickedPath)}」`)
  }, [addWorkspace])

  const handleSubmit = useCallback((content: string | readonly UserContentBlock[]) => {
    if (submitting) return
    setSubmitting(true)
    const run = async () => {
      const opened = await openHeadlessSession(null, effectiveWorkspace)
      const sessions = useSessions.getState()
      await sessions.refresh()
      await sessions.selectByUser(opened.session_id)
      await submitTurn(opened.session_id, content)
    }
    void run()
      .catch((failure: unknown) => {
        console.error('hero session start failed', failure)
        toast.error(failure instanceof Error ? `启动会话失败：${failure.message}` : '启动会话失败')
      })
      .finally(() => {
        setSubmitting(false)
      })
  }, [effectiveWorkspace, submitting])

  return (
    <div className={css.root} data-testid="hero-home">
      <div className={css.stack}>
        <h1 className={css.headline}>
          <span className={css.fishHitbox}>
            <CodeOrbMark size={32} className={css.fish} />
          </span>
          <span className={css.titleGroup}>
            <span>Pokemon Code</span>
          </span>
        </h1>
        <div className={css.body}>
          <div className={css.workspaceRow}>
            <Menu
              open={menuOpen}
              onClose={() => { setMenuOpen(false) }}
              onSelect={handleSelectWorkspace}
              selectedId={effectiveWorkspace ?? DEFAULT_WORKSPACE_ID}
              items={menuItems}
              footer={menuFooter}
              anchor={(
                <button
                  type="button"
                  className={css.workspace}
                  aria-expanded={menuOpen}
                  aria-label="选择工作区"
                  data-testid="hero-workspace-button"
                  title={effectiveWorkspace ?? '默认工作区'}
                  onClick={() => { setMenuOpen((prev) => !prev) }}
                >
                  <IconFolderOpenOutline16 size={16} className={css.folder} />
                  <span className={css.workspaceLabel}>{workspaceLabel}</span>
                  <IconChevronDownOutline14 size={14} className={css.chevron} />
                </button>
              )}
            />
          </div>
          <InputBar
            hero
            disabled={submitting}
            busy={submitting}
            placeholder={uiT('composer.placeholder')}
            onSubmit={handleSubmit}
            sessionId={null}
            onClientCommand={onClientCommand}
          />
        </div>
      </div>

      <DirectoryBrowser
        open={pickerOpen}
        listDirectory={listDirectory}
        createDirectory={createDirectory}
        onOpen={handlePickedDirectory}
        onClose={() => { setPickerOpen(false) }}
        busy={false}
        t={dirT}
      />
    </div>
  )
}

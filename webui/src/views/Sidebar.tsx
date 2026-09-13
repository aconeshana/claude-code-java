import { useEffect, useMemo, useState } from 'react'
import clsx from 'clsx'
import { IconClockOutline16, IconNewChatOutline16, IconRefreshOutline16, IconSettingsOutline16, Pill } from '@primitives'
import css from '@chat-styles/SidebarRoot.module.css'
import browserCss from '@chat-styles/WorkspaceBrowser.module.css'
import triggerCss from '@chat-styles/SettingsRoot.module.css'
import localCss from './Sidebar.module.css'
import { useSessions } from '../store/sessions'
import { useTranslate } from '../i18n/useTranslate'
import { WORKSPACE_NS, workspaceDicts } from '../i18n/dictionaries/workspace'
import { ProjectRowItem, SessionNodeItem } from './SessionRows'
import type { CatalogProject, CatalogSession } from '../api/types'
import { SettingsPanel } from './SettingsPanel'
import { SchedulePanel } from './SchedulePanel'

/** Session rows visible per project before the local overflow control (upstream's COLLAPSED_SESSION_LIMIT). */
const COLLAPSED_SESSION_LIMIT = 5

/** How often the rows' relative-time labels re-derive their "now". */
const NOW_TICK_MS = 30_000

/**
 * Session sidebar: the /resume project→session tree over the vendored dsh
 * sidebar shell, ported from dsh SidebarRoot.tsx + ui-workspace's
 * WorkspaceBrowser.tsx by subtraction. Upstream coverage:
 * - SidebarRoot.tsx's `.newSession` bar — mints a session via
 *   `POST /api/sessions/open` with no `session_id` (backed capability; not
 *   dropped) — and the `.regionArea` seat that hosts the browser.
 * - WorkspaceBrowser.tsx's own `.root` wrapper (the session-list edge inset
 *   that coordinates with `.regionArea`'s canceling margin) and the grouped
 *   SessionTree: section header, 34px project rows, 32px session rows,
 *   per-group 5-row collapse + overflow button, bottom fade — plus
 *   Rows.tsx's row grammar, see SessionRows.tsx.
 * Dropped, with no backend surface: search (local + Host content search —
 * `localCss.headerActions`'s `margin-left: auto` reproduces the search
 * slot's title/actions split without the search machinery), the flat "In
 * one list" view and its view-options menu, workspace CRUD
 * (add/rename/delete/pick flow), session rename/fork/archive menus, and
 * drag-and-drop ordering (both rows and workspace groups). The refresh
 * affordance in the section header is this port's own (upstream's list is
 * live-pushed; ours is a request/response catalog). The settings and
 * scheduled-task panel toggles stay in the sidebar foot per the vendored
 * `SidebarRoot.module.css` `.footArea` structure ("additive actions stack
 * above Settings"): the schedule trigger occupies `.footerActions` while the
 * settings trigger gets its own `.settingsArea` seat, both reusing
 * `SettingsRoot.module.css`'s `triggerRow` geometry. The seats are not
 * interchangeable — `.footerActions` is a horizontal flex and a `triggerRow`
 * is `flex: none; width: calc(100% + 4px)`, so two rows in one seat push the
 * second past the clipped sidebar column (upstream seats them separately:
 * `renderSlot('sidebar.footer.action')` over `renderSlot('sidebar.settings')`).
 */
export function Sidebar() {
  const projects = useSessions((state) => state.projects)
  const selectedId = useSessions((state) => state.selectedSessionId)
  const select = useSessions((state) => state.select)
  const openSession = useSessions((state) => state.openSession)
  const createSession = useSessions((state) => state.createSession)
  const closeSession = useSessions((state) => state.closeSession)
  const refresh = useSessions((state) => state.refresh)
  const growPerPage = useSessions((state) => state.growPerPage)
  const loading = useSessions((state) => state.loading)
  const error = useSessions((state) => state.error)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [scheduleOpen, setScheduleOpen] = useState(false)
  const t = useTranslate(WORKSPACE_NS, workspaceDicts)

  // Relative-time labels re-derive their bucket on a slow tick, the same
  // "now" upstream's rows receive per render.
  const [now, setNow] = useState(Date.now())
  useEffect(() => {
    const timer = window.setInterval(() => { setNow(Date.now()) }, NOW_TICK_MS)
    return () => { window.clearInterval(timer) }
  }, [])

  const sorted = useMemo(
    () => [...projects].sort((a, b) => a.project_name.localeCompare(b.project_name)),
    [projects],
  )

  // Groups auto-expand to reveal the selected session (upstream's
  // groupExpansion effect on the current group).
  const selectedProject = useMemo(
    () => sorted.find((project) => project.sessions.some((session) => session.id === selectedId)),
    [sorted, selectedId],
  )
  const [collapsedGroups, setCollapsedGroups] = useState<string[]>([])
  useEffect(() => {
    if (selectedProject == null) return
    setCollapsedGroups((keys) => keys.filter((key) => key !== selectedProject.project_path))
  }, [selectedProject])

  return (
    <div className={css.root}>
      <button
        type="button"
        className={css.newSession}
        onClick={() => { void createSession(selectedProject?.project_path ?? null) }}
      >
        <IconNewChatOutline16 />
        <span className={css.newSessionLabel}>{t('newSession')}</span>
      </button>
      <div className={css.regionArea}>
        <div className={browserCss.root}>
          <div className={browserCss.sectionHeader}>
            <span className={browserCss.sectionLabel}>{t('section.sessions')}</span>
            <div className={clsx(browserCss.headerActions, localCss.headerActions)}>
              <button
                type="button"
                className={clsx(localCss.refresh, loading && localCss.refreshBusy)}
                onClick={() => { void refresh() }}
                aria-label={t('refresh')}
                title={t('refresh')}
              >
                <IconRefreshOutline16 />
              </button>
            </div>
          </div>
          {error != null && <div className={localCss.error}>{error}</div>}
          <div className={browserCss.listArea}>
            <div className={browserCss.treeBody}>
              <div className={browserCss.list} role="tree" aria-label={t('section.sessions')}>
                {sorted.length === 0 && (
                  <div className={browserCss.empty}>{t('empty.none')}</div>
                )}
                {sorted.map((project) => (
                  <GroupSection
                    key={project.project_path}
                    project={project}
                    expanded={!collapsedGroups.includes(project.project_path)}
                    selectedId={selectedId}
                    now={now}
                    containsCurrent={project.project_path === selectedProject?.project_path}
                    onToggle={() => {
                      setCollapsedGroups((keys) => (
                        keys.includes(project.project_path)
                          ? keys.filter((key) => key !== project.project_path)
                          : [...keys, project.project_path]
                      ))
                    }}
                    onOpen={(session) => {
                      // A TUI-history session that isn't live yet needs the
                      // headless open round-trip before its snapshot/mirror
                      // frames exist; active or already-open sessions just
                      // switch straight to their snapshot.
                      void (session.active || session.headless_open
                        ? select(session.id)
                        : openSession(session.id, project.project_path))
                    }}
                    onClose={(session) => { void closeSession(session.id) }}
                    onGrow={() => {
                      // One "load more" grows every project's page by one
                      // step; the gateway pages listings per project.
                      void growPerPage(currentPageSize(projects) + COLLAPSED_SESSION_LIMIT)
                    }}
                    t={t}
                  />
                ))}
              </div>
              <span className={browserCss.fade} />
            </div>
          </div>
        </div>
      </div>
      <div className={css.footArea}>
        <div className={css.footerActions}>
          <div className={triggerCss.triggerRow}>
            <button
              type="button"
              className={triggerCss.trigger}
              onClick={() => { setScheduleOpen(true) }}
              aria-haspopup="dialog"
              aria-expanded={scheduleOpen}
              aria-label="打开定时任务面板"
            >
              <IconClockOutline16 size={16} />
              <span className={triggerCss.triggerLabel}>定时任务</span>
            </button>
          </div>
        </div>
        <div className={css.settingsArea}>
          <div className={triggerCss.triggerRow}>
            <button
              type="button"
              className={triggerCss.trigger}
              onClick={() => { setSettingsOpen(true) }}
              aria-haspopup="dialog"
              aria-expanded={settingsOpen}
              aria-label="打开设置面板"
            >
              <IconSettingsOutline16 size={16} />
              <span className={triggerCss.triggerLabel}>设置</span>
            </button>
          </div>
        </div>
        <Pill>Claude Code</Pill>
      </div>
      <SettingsPanel open={settingsOpen} onClose={() => { setSettingsOpen(false) }} />
      <SchedulePanel open={scheduleOpen} onClose={() => { setScheduleOpen(false) }} />
    </div>
  )
}

/** Fold one project without charging hidden rows against the visible limit (upstream collapsedSessionRows). */
function collapsedSessionRows(sessions: readonly CatalogSession[]): {
  rows: readonly CatalogSession[]
  hiddenCount: number
} {
  return { rows: sessions.slice(0, COLLAPSED_SESSION_LIMIT), hiddenCount: Math.max(0, sessions.length - COLLAPSED_SESSION_LIMIT) }
}

/** The largest per-project page the current listing already carries. */
function currentPageSize(projects: readonly CatalogProject[]): number {
  return projects.reduce((max, project) => Math.max(max, project.sessions.length), 0)
}

/** One project group: the 34px header row plus its 32px session rows. */
function GroupSection({ project, expanded, selectedId, now, containsCurrent, onToggle, onOpen, onClose, onGrow, t }: {
  project: CatalogProject
  expanded: boolean
  selectedId: string | null
  now: number
  containsCurrent: boolean
  onToggle: () => void
  onOpen: (session: CatalogSession) => void
  onClose: (session: CatalogSession) => void
  /** Grows the per-project page by one step (the gateway pages listings). */
  onGrow: () => void
  t: ReturnType<typeof useTranslate>
}) {
  const collapsed = collapsedSessionRows(project.sessions)
  const rows = expanded && collapsed.hiddenCount === 0
    ? project.sessions
    : collapsed.rows
  const hiddenCount = expanded ? project.session_count - rows.length : 0
  return (
    <div className={browserCss.groupSection}>
      <ProjectRowItem
        label={project.project_name}
        expanded={expanded}
        containsCurrent={containsCurrent}
        onToggle={onToggle}
      />
      {rows.map((session) => (
        <SessionNodeItem
          key={session.id}
          session={session}
          selected={session.id === selectedId}
          now={now}
          onOpen={onOpen}
          onClose={onClose}
          t={t}
        />
      ))}
      {hiddenCount > 0 && (
        <button
          type="button"
          className={browserCss.sessionOverflowButton}
          onClick={onGrow}
        >
          {t('sessions.expand', { n: hiddenCount })}
        </button>
      )}
    </div>
  )
}

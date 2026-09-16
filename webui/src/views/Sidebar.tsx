import { useEffect, useMemo, useState } from 'react'
import clsx from 'clsx'
import { IconNewChatOutline16, IconRefreshOutline16, IconSettingsOutline16, Pill } from '@primitives'
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
import { ContextDashboardButton } from './context/ContextDashboard'

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
 *   per-group 5-row collapse + the overflow button as a LOCAL fold toggle
 *   (upstream's expandedSessionGroups + toggled(): expanded shows every row
 *   and flips the button to "Show less", aria-expanded carries the state),
 *   bottom fade — plus Rows.tsx's row grammar, see SessionRows.tsx.
 *   Deviation: upstream's account holds every row client-side, while this
 *   port's rows are a gateway page (?per_project=, session_count keeps the
 *   total) — so expanding ALSO grows the page one step while the listing
 *   still truncates, and the paged-out remainder counts in the button's n.
 * Dropped, with no backend surface: search (local + Host content search —
 * `localCss.headerActions`'s `margin-left: auto` reproduces the search
 * slot's title/actions split without the search machinery), the flat "In
 * one list" view and its view-options menu, workspace CRUD
 * (add/rename/delete/pick flow), session rename/fork/archive menus, and
 * drag-and-drop ordering (both rows and workspace groups). The refresh
 * affordance in the section header is this port's own (upstream's list is
 * live-pushed; ours is a request/response catalog). The sidebar foot's
 * `.footerActions` seat (vendored `SidebarRoot.module.css` `.footArea`) holds
 * the vendored dsh-context Context Dashboard entry (ContextDashboard.tsx);
 * Settings sits below it. The scheduled-task surface moved into the settings
 * dialog as its own nav section (see SchedulePanel.tsx) — upstream's own
 * schedule surface is a read-only conversation-header catalog, so no foot
 * trigger corresponds to it.
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
  const t = useTranslate(WORKSPACE_NS, workspaceDicts)

  // Relative-time labels re-derive their bucket on a slow tick, the same
  // "now" upstream's rows receive per render.
  const [now, setNow] = useState(Date.now())
  useEffect(() => {
    const timer = window.setInterval(() => { setNow(Date.now()) }, NOW_TICK_MS)
    return () => { window.clearInterval(timer) }
  }, [])

  // Alphabetical by project name, then the selected session's project is
  // lifted to the top (upstream's activity promotion in compareSessionRecency
  // hoists the current session's account; this catalog has no per-session
  // recency order to restore behind it, so the rest keeps the name order).
  const selectedProject = useMemo(
    () => projects.find((project) => project.sessions.some((session) => session.id === selectedId)),
    [projects, selectedId],
  )
  const sorted = useMemo(
    () => [...projects].sort((a, b) => {
      const aCurrent = a.project_path === selectedProject?.project_path
      const bCurrent = b.project_path === selectedProject?.project_path
      if (aCurrent !== bCurrent) return aCurrent ? -1 : 1
      return a.project_name.localeCompare(b.project_name)
    }),
    [projects, selectedProject],
  )

  // Groups auto-expand to reveal the selected session (upstream's
  // groupExpansion effect on the current group).
  const [collapsedGroups, setCollapsedGroups] = useState<string[]>([])
  useEffect(() => {
    if (selectedProject == null) return
    setCollapsedGroups((keys) => keys.filter((key) => key !== selectedProject.project_path))
  }, [selectedProject])
  // Local overflow expansion per project (upstream's expandedSessionGroups):
  // one list of project paths whose rows are NOT folded to the 5-row limit.
  const [expandedGroups, setExpandedGroups] = useState<string[]>([])

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
                    overflowExpanded={expandedGroups.includes(project.project_path)}
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
                    onOverflowToggle={() => {
                      // Upstream's overflow control is a local fold toggle —
                      // expanded shows every row the account holds and flips
                      // the button to "Show less". This port's account rows
                      // are a gateway page, so expanding also grows the page
                      // one step when the listing still truncates
                      // (session_count > the rows it carried).
                      setExpandedGroups((keys) => toggled(keys, project.project_path))
                      if (project.session_count > project.sessions.length) {
                        void growPerPage(currentPageSize(projects) + COLLAPSED_SESSION_LIMIT)
                      }
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
          <ContextDashboardButton />
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

/** Immutable membership toggle for the local overflow-expansion array (upstream's toggled). */
function toggled(list: readonly string[], key: string): string[] {
  return list.includes(key) ? list.filter(k => k !== key) : [...list, key]
}

/** One project group: the 34px header row plus its 32px session rows. */
function GroupSection({ project, expanded, overflowExpanded, selectedId, now, containsCurrent, onToggle, onOpen, onClose, onOverflowToggle, t }: {
  project: CatalogProject
  expanded: boolean
  /** Rows unfolded past the 5-row limit (upstream's sessionsExpanded). */
  overflowExpanded: boolean
  selectedId: string | null
  now: number
  containsCurrent: boolean
  onToggle: () => void
  onOpen: (session: CatalogSession) => void
  onClose: (session: CatalogSession) => void
  /** Toggles this group's overflow fold (upstream's overflow-button onClick). */
  onOverflowToggle: () => void
  t: ReturnType<typeof useTranslate>
}) {
  // The selected session lifts to the head of its group before the fold is
  // taken, so it is never hidden behind the 5-row limit or the paged
  // remainder (upstream's activity promotion hoists the current session in
  // compareSessionRecency; the rest keeps the listing's order).
  const ordered = useMemo(
    () => containsCurrent
      ? [...project.sessions].sort((a, b) => {
          if (a.id === b.id) return 0
          return a.id === selectedId ? -1 : b.id === selectedId ? 1 : 0
        })
      : project.sessions,
    [project.sessions, containsCurrent, selectedId],
  )
  const collapsed = collapsedSessionRows(ordered)
  // The page may still truncate (session_count > sessions.length): the folded
  // view hides the paged-out remainder too, so it counts against hiddenCount.
  const hiddenCount = overflowExpanded
    ? Math.max(0, project.session_count - ordered.length)
    : collapsed.hiddenCount + Math.max(0, project.session_count - ordered.length)
  const rows = expanded && (overflowExpanded || collapsed.hiddenCount === 0)
    ? ordered
    : collapsed.rows
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
          aria-expanded={overflowExpanded}
          onClick={onOverflowToggle}
        >
          {overflowExpanded
            ? t('sessions.collapse')
            : t('sessions.expand', { n: hiddenCount })}
        </button>
      )}
    </div>
  )
}

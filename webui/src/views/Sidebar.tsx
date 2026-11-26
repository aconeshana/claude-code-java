import { useEffect, useMemo, useRef, useState } from 'react'
import clsx from 'clsx'
import {
  Button, IconCloseFill14, IconListPenOutline16, IconNewChatOutline16,
  IconPanelLeftOutline16, IconPlusOutline16, IconRefreshOutline16, IconSearchOutline16, IconSettingsOutline16,
  Input, Menu, Modal, Pill, RiskConfirmation, StateDot, Tooltip,
} from '@primitives'
import type { MenuEntry } from '@primitives'
import css from '@chat-styles/SidebarRoot.module.css'
import browserCss from '@chat-styles/WorkspaceBrowser.module.css'
import rowCss from '@chat-styles/WorkspaceRows.module.css'
import triggerCss from '@chat-styles/SettingsRoot.module.css'
import localCss from './Sidebar.module.css'
import { useLayout } from '@vendor/ui-layout'
import { SIDEBAR_WIDTH } from './AppFrame'
import { useSessions } from '../store/sessions'
import type { SessionActivity } from '../store/sessions'
import { pendingInteractions, useApprovals } from '../store/approvals'
import type { PendingInteraction } from '../store/approvals'
import { useSidebarCollapse } from '../store/sidebarCollapse'
import {
  filterSidebarProjects,
  sortFlatSidebarEntries,
  sortSidebarProjects,
  useWorkspacePrefs,
} from '../store/workspacePrefs'
import { toast } from '../store/toast'
import { useTranslate } from '../i18n/useTranslate'
import { WORKSPACE_NS, workspaceDicts } from '../i18n/dictionaries/workspace'
import { UI_NS, uiDicts } from '../i18n/dictionaries/ui'
import { displayTitle, PinGlyph, ProjectRowItem, SessionNodeItem, sessionStatus } from './SessionRows'
import { stripScheduledSessions } from './scheduledSessions'
import { ScheduledTasksNavButton } from './ScheduledTasksWorkspace'
import { useScheduleView } from '../store/scheduleView'
import type { CatalogProject, CatalogSession } from '../api/types'
import { SettingsPanel } from './SettingsPanel'
import { ContextDashboardButton } from './context/ContextDashboard'
import { CodeOrbMark } from './brand/CodeOrbMark'
import { PocoWordmark } from './brand/PocoWordmark'
import { CreateProjectModal } from './CreateProjectModal'
import { projectDisplayName, useProjects } from '../store/projects'

/** Session rows visible per project before the local overflow control (upstream's COLLAPSED_SESSION_LIMIT). */
const COLLAPSED_SESSION_LIMIT = 5

/** How often the rows' relative-time labels re-derive their "now". */
const NOW_TICK_MS = 30_000

/** Wide-content unmount delay; matches the 150ms wide-content fade-out (upstream's COLLAPSE_SETTLE_MS). */
const COLLAPSE_SETTLE_MS = 150

/** How long the column's scrollbars stay drawn after the pointer leaves it (upstream's SCROLLBAR_LINGER_MS). */
const SCROLLBAR_LINGER_MS = 2000

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
 * (add/rename/delete/pick flow), and drag-and-drop ordering (both rows and
 * workspace groups). The session row's own rename/fork/archive menu (plus
 * this project's delete, no upstream counterpart) lives in this file as the
 * rename dialog and delete confirmation — see SessionRows.tsx for the menu
 * itself and webui/UPSTREAM.md for provenance. The refresh affordance in the
 * section header is this port's own (upstream's list is live-pushed; ours is
 * a request/response catalog). The sidebar foot's
 * `.footerActions` seat (vendored `SidebarRoot.module.css` `.footArea`) holds
 * the vendored dsh-context Context Dashboard entry (ContextDashboard.tsx);
 * Settings sits below it. The scheduled-task surface moved into the settings
 * dialog as its own nav section (see SchedulePanel.tsx) — upstream's own
 * schedule surface is a read-only conversation-header catalog, so no foot
 * trigger corresponds to it.
 *
 * Collapse (2026-09-18): ported SidebarRoot.tsx's rail state machine — the
 * settle timer that unmounts wide content 150ms after a live collapse
 * (`wide`/`settled`), the frozen-width fade (`lastWideWidth`) that lets the
 * AppFrame grid track slide without reflowing content, the `everWide` guard
 * that keeps a cold collapsed render static (no `.railIn` crossfade), and the
 * scrollbar pointer-linger (`SCROLLBAR_LINGER_MS`) — all kept verbatim
 * against the already-vendored `SidebarRoot.module.css` classes. Collapse
 * state lives in `store/sidebarCollapse.ts`, a persisted local preference
 * standing in for upstream's cross-slot `ui-layout` service (same pattern as
 * `store/theme.ts`/`store/transcriptView.ts`). Product-scope cuts, recorded
 * in webui/UPSTREAM.md: the session browser (`regionArea`) unmounts entirely
 * while collapsed instead of degrading to upstream's rail icon column, since
 * this port's tree has no search/grouping affordances to represent there.
 *
 * Brand (2026-09-20): the compound brand+New-Session button and the rail's
 * hover-swap mark are ported, against the same vendored classes. Upstream
 * fills both the mark and the name through `sidebar.brand.*` slots (falling
 * back to a local-build badge when nothing registers); the slot plumbing is
 * cut per the vendoring rules, so this port mounts its own art directly —
 * `CodeOrbMark` and `PocoWordmark`, not upstream's whale and `deepseek`
 * wordmark. Only the size contract (24px) is inherited.
 */
export function Sidebar() {
  const projects = useSessions((state) => state.projects)
  const selectedId = useSessions((state) => state.selectedSessionId)
  const selectByUser = useSessions((state) => state.selectByUser)
  const clearSelection = useSessions((state) => state.clearSelection)
  const openSession = useSessions((state) => state.openSession)
  const createSession = useSessions((state) => state.createSession)
  const closeSession = useSessions((state) => state.closeSession)
  const renameSession = useSessions((state) => state.renameSession)
  const forkSession = useSessions((state) => state.forkSession)
  const archiveSession = useSessions((state) => state.archiveSession)
  const deleteSession = useSessions((state) => state.deleteSession)
  const deleteProject = useSessions((state) => state.deleteProject)
  const refresh = useSessions((state) => state.refresh)
  const growPerPage = useSessions((state) => state.growPerPage)
  const loading = useSessions((state) => state.loading)
  const error = useSessions((state) => state.error)
  const [settingsOpen, setSettingsOpen] = useState(false)
  // The rename dialog and the delete confirmation are dialogs over ONE row
  // at a time; each row's menu opens the dialog by setting its target here
  // rather than calling the store action directly (fork/archive have no
  // confirmation step and call straight through).
  const [renameTarget, setRenameTarget] = useState<CatalogSession | null>(null)
  const [renameValue, setRenameValue] = useState('')
  const [deleteTarget, setDeleteTarget] = useState<CatalogSession | null>(null)
  const [deleteAcknowledged, setDeleteAcknowledged] = useState(false)
  const [deleteProjectTarget, setDeleteProjectTarget] = useState<CatalogProject | null>(null)
  const [deleteProjectAcknowledged, setDeleteProjectAcknowledged] = useState(false)
  const t = useTranslate(WORKSPACE_NS, workspaceDicts)
  const uiT = useTranslate(UI_NS, uiDicts)
  const webProjects = useProjects((state) => state.projects)
  const refreshWebProjects = useProjects((state) => state.refresh)

  useEffect(() => {
    void refreshWebProjects()
  }, [refreshWebProjects])

  const collapsed = useSidebarCollapse((state) => state.collapsed)
  const toggleCollapsed = useSidebarCollapse((state) => state.toggle)
  const viewMode = useWorkspacePrefs((state) => state.viewMode)
  const pinnedSessionIds = useWorkspacePrefs((state) => state.pinnedSessionIds)
  const setViewMode = useWorkspacePrefs((state) => state.setViewMode)
  const togglePin = useWorkspacePrefs((state) => state.togglePin)
  const addWorkspace = useWorkspacePrefs((state) => state.addWorkspace)
  const removeWorkspace = useWorkspacePrefs((state) => state.removeWorkspace)
  const hiddenProjectPaths = useWorkspacePrefs((state) => state.hiddenProjectPaths)
  const hideProject = useWorkspacePrefs((state) => state.hideProject)
  const [workspacePickerOpen, setWorkspacePickerOpen] = useState(false)
  const [searchOpen, setSearchOpen] = useState(false)
  const [searchQuery, setSearchQuery] = useState('')
  const [viewMenuOpen, setViewMenuOpen] = useState(false)
  const searchInputRef = useRef<HTMLInputElement>(null)
  // Wide content stays mounted while the collapse animates (fading via
  // .fading), unmounts at settle, and remounts right away on expand
  // (upstream's settled/wide pair).
  const [settled, setSettled] = useState(collapsed)
  useEffect(() => {
    if (!collapsed) { setSettled(false); return }
    const timer = window.setTimeout(() => { setSettled(true) }, COLLAPSE_SETTLE_MS)
    return () => { window.clearTimeout(timer) }
  }, [collapsed])
  const storedSidebarWidth = useLayout((state) => state.sidebar || SIDEBAR_WIDTH)
  const wide = !collapsed || !settled
  // Freeze the content at its expanded width while it fades out: the AppFrame
  // grid track then slides/clips it instead of reflowing it.
  const lastWideWidth = useRef(storedSidebarWidth)
  if (!collapsed) lastWideWidth.current = storedSidebarWidth
  // Rail-in only crossfades a live collapse: a refresh straight into the
  // collapsed state renders the rail statically (no delay-hidden controls).
  const everWide = useRef(!collapsed)
  if (!collapsed) everWide.current = true

  // Scrollbars in the column follow the pointer (.quietBars rebinds them
  // away): drawn while it is inside, and for SCROLLBAR_LINGER_MS after it
  // leaves. A pointer that returns within that window cancels the pending
  // hide rather than restarting from a hidden bar.
  const column = useRef<HTMLDivElement>(null)
  const [pointerInside, setPointerInside] = useState(false)
  const lingerTimer = useRef<number | undefined>(undefined)
  const armLinger = (): void => {
    if (lingerTimer.current !== undefined) return
    lingerTimer.current = window.setTimeout(() => {
      lingerTimer.current = undefined
      setPointerInside(false)
    }, SCROLLBAR_LINGER_MS)
  }
  const cancelLinger = (): void => {
    window.clearTimeout(lingerTimer.current)
    lingerTimer.current = undefined
  }
  // Leaving is decided by the column's BOX, not by DOM containment, and only
  // while the bars are drawn: the settings panel renders as a fixed-position
  // descendant of this column, so a pointer moved onto it fires no
  // `pointerleave` here.
  useEffect(() => {
    if (!pointerInside) return
    const onMove = (event: PointerEvent): void => {
      const rect = column.current?.getBoundingClientRect()
      if (rect === undefined) return
      const inside = event.clientX >= rect.left && event.clientX < rect.right
        && event.clientY >= rect.top && event.clientY < rect.bottom
      if (inside) cancelLinger()
      else armLinger()
    }
    document.addEventListener('pointermove', onMove)
    return () => {
      document.removeEventListener('pointermove', onMove)
      cancelLinger()
    }
  }, [pointerInside])

  // Relative-time labels re-derive their bucket on a slow tick, the same
  // "now" upstream's rows receive per render.
  const [now, setNow] = useState(Date.now())
  useEffect(() => {
    const timer = window.setInterval(() => { setNow(Date.now()) }, NOW_TICK_MS)
    return () => { window.clearInterval(timer) }
  }, [])

  const closeScheduleView = useScheduleView((state) => state.closeView)
  const { regularProjects } = useMemo(
    () => stripScheduledSessions(projects),
    [projects],
  )
  const visibleProjects = useMemo(() => {
    const hiddenSet = new Set(hiddenProjectPaths)
    const byPath = new Map<string, CatalogProject>()
    for (const project of regularProjects) {
      if (hiddenSet.has(project.project_path)
        && !project.sessions.some((session) => session.tui_attached === true)) {
        continue
      }
      byPath.set(project.project_path, {
        ...project,
        project_name: projectDisplayName(project.project_path, webProjects),
      })
    }
    for (const entry of webProjects) {
      if (hiddenSet.has(entry.path) || byPath.has(entry.path)) continue
      byPath.set(entry.path, {
        project_path: entry.path,
        project_name: projectDisplayName(entry.path, webProjects),
        session_count: 0,
        sessions: [],
      })
    }
    return [...byPath.values()]
  }, [regularProjects, hiddenProjectPaths, webProjects])

  const selectedProject = useMemo(
    () => visibleProjects.find((project) => project.sessions.some((session) => session.id === selectedId)),
    [visibleProjects, selectedId],
  )
  const sorted = useMemo(
    () => sortSidebarProjects(visibleProjects, selectedProject?.project_path ?? null),
    [visibleProjects, selectedProject],
  )

  const pinnedIdSet = useMemo(() => new Set(pinnedSessionIds), [pinnedSessionIds])

  const filtered = useMemo(
    () => filterSidebarProjects(sorted, {
      query: searchQuery,
      pinnedIds: pinnedSessionIds,
    }),
    [sorted, searchQuery, pinnedSessionIds],
  )

  const handleTogglePin = (session: CatalogSession): void => {
    const pinnedNow = togglePin(session.id)
    toast.info(pinnedNow ? `已置顶会话「${displayTitle(session)}」` : `已取消置顶「${displayTitle(session)}」`)
  }

  const searching = searchQuery.trim() !== ''

  const flatEntries = useMemo(() => {
    const entries: { session: CatalogSession; projectPath: string; projectName: string }[] = []
    for (const project of filtered.projects) {
      for (const session of project.sessions) {
        entries.push({ session, projectPath: project.project_path, projectName: project.project_name })
      }
    }
    return sortFlatSidebarEntries(entries)
  }, [filtered.projects])

  const viewMenuItems = useMemo<readonly MenuEntry[]>(() => [
    { type: 'label', id: 'lbl-view', text: '视图方式' },
    { id: 'view:grouped', label: '按工作区分组' },
    { id: 'view:flat', label: '平铺列表' },
  ], [])

  const openSearch = (): void => {
    setSearchOpen(true)
    window.requestAnimationFrame(() => { searchInputRef.current?.focus() })
  }

  const closeSearch = (): void => {
    setSearchQuery('')
    setSearchOpen(false)
  }

  // The rows' pending-interaction dots. The mirror stream carries every
  // session's asks, so a session waiting on the user stays visible here even
  // though its card only renders under its own composer — that split is the
  // point: nothing is hidden, and nothing hijacks the session you are in.
  const asks = useApprovals((state) => state.asks)
  const pending = useMemo(() => pendingInteractions(asks), [asks])

  // Live per-session turn state for the rows' status dots. Unlike `pending`,
  // this arrives already keyed by session id — the store folds the mirror
  // frames itself (see `applyTurnActivity`), so there is nothing to derive
  // here beyond reading it out of the store.
  const activity = useSessions((state) => state.activity)

  // Groups auto-expand to reveal the selected session, and also expand once
  // when a live TUI session is newly discovered under a collapsed project.
  const [collapsedGroups, setCollapsedGroups] = useState<string[]>([])
  const seenTuiProjectsRef = useRef<Set<string>>(new Set())
  useEffect(() => {
    if (selectedProject == null) return
    setCollapsedGroups((keys) => keys.filter((key) => key !== selectedProject.project_path))
  }, [selectedProject])
  useEffect(() => {
    const activeTuiPaths = new Set<string>()
    for (const project of regularProjects) {
      if (project.sessions.some((session) => session.tui_attached === true)) {
        activeTuiPaths.add(project.project_path)
      }
    }
    const newlyDiscovered: string[] = []
    for (const path of activeTuiPaths) {
      if (!seenTuiProjectsRef.current.has(path)) {
        newlyDiscovered.push(path)
      }
    }
    seenTuiProjectsRef.current = activeTuiPaths
    if (newlyDiscovered.length > 0) {
      setCollapsedGroups((keys) => keys.filter((key) => !newlyDiscovered.includes(key)))
    }
  }, [regularProjects])
  // Local overflow expansion per project (upstream's expandedSessionGroups):
  // one list of project paths whose rows are NOT folded to the 5-row limit.
  const [expandedGroups, setExpandedGroups] = useState<string[]>([])

  return (
    <div
      ref={column}
      className={clsx(
        css.root, !wide && css.collapsed, !wide && everWide.current && css.railIn,
        collapsed && wide && css.fading, !pointerInside && css.quietBars,
      )}
      style={wide ? { width: collapsed ? lastWideWidth.current : storedSidebarWidth } : undefined}
      onPointerEnter={() => {
        cancelLinger()
        setPointerInside(true)
      }}
      onPointerLeave={() => { armLinger() }}
    >
      <div className={css.logoRow}>
        {/* Expanded, the brand doubles as a New Session shortcut; collapsed,
            that role falls to the toggle below, whose resting state is the
            same mark. The identity is aria-hidden because the button already
            carries the label — the art itself says nothing a reader needs. */}
        {wide && (
          <button
            type="button"
            className={clsx(css.brand, css.wide)}
            aria-label={t('newSession')}
            onClick={() => {
              closeScheduleView()
              clearSelection()
            }}
          >
            <span className={css.brandIdentity} aria-hidden="true">
              <span className={css.brandMark}>
                <CodeOrbMark size={24} />
              </span>
              <span className={css.brandName}>
                <PocoWordmark size={24} />
              </span>
            </span>
          </button>
        )}
        <Tooltip label={collapsed ? t('toggle.open') : t('toggle.collapse')} delayMs={500}>
          <button
            type="button"
            className={clsx(css.iconButton, css.toggle)}
            aria-label={collapsed ? t('toggle.open') : t('toggle.collapse')}
            onClick={() => { toggleCollapsed() }}
          >
            {/* Rail resting state is the brand mark; hovering swaps in the
                panel icon (the expand affordance). Expanded it is a plain
                panel icon. The .panelIcon class is what the vendored
                `.collapsed .toggle` hover rules key off — without it the
                swap silently never happens. */}
            {!wide && (
              <span className={css.railMark} aria-hidden="true">
                <CodeOrbMark size={24} />
              </span>
            )}
            <IconPanelLeftOutline16 className={css.panelIcon} size={wide ? 16 : 18} />
          </button>
        </Tooltip>
      </div>
      <Tooltip label={t('newSession')} delayMs={500} disabled={wide}>
        <button
          type="button"
          className={css.newSession}
          aria-label={t('newSession')}
          onClick={() => {
            closeScheduleView()
            clearSelection()
          }}
        >
          <IconNewChatOutline16 size={wide ? 14 : 18} />
          {wide && <span className={clsx(css.newSessionLabel, css.wide)}>{t('newSession')}</span>}
        </button>
      </Tooltip>
      <div className={css.regionArea}>
        {wide && (
        <div className={browserCss.root}>
          <div className={browserCss.sectionHeader}>
            <span className={clsx(browserCss.sectionLabel, searchOpen && browserCss.sectionLabelHidden)}>
              {t('section.sessions')}
            </span>
            <div className={clsx(browserCss.searchSlot, searchOpen && browserCss.searchSlotExpanded)}>
              <div
                className={clsx(browserCss.search, searchOpen && browserCss.searchExpanded)}
                onClick={() => { if (!searchOpen) openSearch() }}
              >
                <button
                  type="button"
                  className={browserCss.searchButton}
                  data-testid="sidebar-search-toggle"
                  aria-label="搜索会话"
                  title="搜索会话"
                  onClick={(e) => {
                    e.stopPropagation()
                    if (searchOpen) searchInputRef.current?.focus()
                    else openSearch()
                  }}
                >
                  <IconSearchOutline16 size={16} />
                </button>
                <input
                  ref={searchInputRef}
                  type="text"
                  className={browserCss.searchInput}
                  data-testid="sidebar-search-input"
                  value={searchQuery}
                  onChange={(e) => { setSearchQuery(e.currentTarget.value) }}
                  onKeyDown={(e) => {
                    if (e.key === 'Escape') {
                      e.stopPropagation()
                      closeSearch()
                    }
                  }}
                  onBlur={() => {
                    if (searchQuery.trim() === '') setSearchOpen(false)
                  }}
                  placeholder="搜索会话..."
                  aria-label="搜索会话"
                />
                {searchOpen && (
                  <button
                    type="button"
                    className={browserCss.clearButton}
                    data-testid="sidebar-search-clear"
                    aria-label="关闭搜索"
                    onMouseDown={(e) => { e.preventDefault() }}
                    onClick={(e) => {
                      e.stopPropagation()
                      if (searchQuery !== '') {
                        setSearchQuery('')
                        searchInputRef.current?.focus()
                      } else {
                        closeSearch()
                      }
                    }}
                  >
                    <IconCloseFill14 size={14} />
                  </button>
                )}
              </div>
            </div>
            <div className={clsx(browserCss.headerActions, searchOpen && browserCss.headerActionsHidden)}>
              <Menu
                open={viewMenuOpen}
                onClose={() => { setViewMenuOpen(false) }}
                portal
                align="end"
                dense
                items={viewMenuItems}
                selectedId={`view:${viewMode}`}
                onSelect={(id) => {
                  setViewMenuOpen(false)
                  if (id === 'view:grouped') setViewMode('grouped')
                  else if (id === 'view:flat') setViewMode('flat')
                }}
                anchor={(
                  <button
                    type="button"
                    className={browserCss.iconButton}
                    data-testid="sidebar-view-menu-btn"
                    aria-label="视图选项"
                    aria-haspopup="menu"
                    aria-expanded={viewMenuOpen}
                    title="视图选项"
                    onClick={() => { setViewMenuOpen((open) => !open) }}
                  >
                    <IconListPenOutline16 size={16} />
                  </button>
                )}
              />
              <button
                type="button"
                className={browserCss.iconButton}
                data-testid="sidebar-add-workspace"
                onClick={() => { setWorkspacePickerOpen(true) }}
                aria-label="添加工作区"
                title="添加工作区（选择或新建项目目录）"
              >
                <IconPlusOutline16 size={16} />
              </button>
              <button
                type="button"
                className={clsx(browserCss.iconButton, loading && localCss.refreshBusy)}
                onClick={() => { void refresh() }}
                aria-label={t('refresh')}
                title={t('refresh')}
              >
                <IconRefreshOutline16 size={16} />
              </button>
            </div>
          </div>
          {error != null && <div className={localCss.error}>{error}</div>}
          <div className={browserCss.listArea}>
            <div className={browserCss.treeBody}>
              <div
                className={clsx(
                  browserCss.list,
                  searching && browserCss.searchTree,
                  !searching && viewMode === 'flat' && browserCss.flatList,
                )}
                role="tree"
                aria-label={t('section.sessions')}
              >
                {searching ? (
                  filtered.searchResults.length === 0 ? (
                    <div className={browserCss.searchStatus} data-testid="sidebar-empty-filtered">
                      未找到匹配「{searchQuery.trim()}」的会话
                    </div>
                  ) : (
                    filtered.searchResults.map(({ session, projectPath, projectName, snippet }) => (
                      <button
                        key={`search-${session.id}`}
                        type="button"
                        role="treeitem"
                        className={clsx(rowCss.searchResultRow, session.id === selectedId && rowCss.selected)}
                        data-testid={`sidebar-search-row-${session.id}`}
                        onClick={() => {
                          closeScheduleView()
                          void (session.active || session.headless_open
                            ? selectByUser(session.id)
                            : openSession(session.id, projectPath))
                        }}
                      >
                        <div className={rowCss.searchResultHeading}>
                          <span className={rowCss.slot} aria-hidden="true">
                            {(() => {
                              const status = sessionStatus(activity[session.id], pending.get(session.id))
                              return status.state !== 'idle'
                                ? <StateDot state={status.state} className={rowCss.dot} />
                                : null
                            })()}
                          </span>
                          <span className={rowCss.searchResultTitle}>{displayTitle(session)}</span>
                        </div>
                        <div className={rowCss.searchResultMeta}>
                          <span className={rowCss.searchResultWorkspace}>{projectName}</span>
                          {snippet && <span className={rowCss.searchResultSnippet}>{snippet}</span>}
                        </div>
                      </button>
                    ))
                  )
                ) : (
                  <>
                    {filtered.pinned.length > 0 && (
                      <div className={localCss.pinnedSection} data-testid="sidebar-pinned-section">
                        <div className={localCss.pinnedHeader}>
                          <span className={localCss.pinnedHeaderIcon} aria-hidden="true">
                            <PinGlyph filled />
                          </span>
                          <span>已置顶</span>
                        </div>
                        {filtered.pinned.map(({ session, projectPath }) => (
                          <SessionNodeItem
                            key={`pinned-${session.id}`}
                            session={session}
                            selected={session.id === selectedId}
                            pinned
                            now={now}
                            pending={pending.get(session.id)}
                            activity={activity[session.id]}
                            rowTestId={`sidebar-pinned-row-${session.id}`}
                            pinTestId={`sidebar-unpin-${session.id}`}
                            onOpen={(s) => {
                              closeScheduleView()
                              void (s.active || s.headless_open
                                ? selectByUser(s.id)
                                : openSession(s.id, projectPath))
                            }}
                            onClose={(s) => { void closeSession(s.id) }}
                            onRename={(s) => {
                              setRenameTarget(s)
                              setRenameValue(displayTitle(s))
                            }}
                            onFork={(s) => { void forkSession(s.id) }}
                            onArchive={(s) => { void archiveSession(s.id) }}
                            onDelete={(s) => {
                              setDeleteTarget(s)
                              setDeleteAcknowledged(false)
                            }}
                            onTogglePin={handleTogglePin}
                            t={t}
                          />
                        ))}
                      </div>
                    )}
                    {filtered.projects.length === 0 ? (
                      <div className={browserCss.empty}>{t('empty.none')}</div>
                    ) : viewMode === 'flat' ? (
                      flatEntries.map(({ session, projectPath }) => (
                        <SessionNodeItem
                          key={`flat-${session.id}`}
                          session={session}
                          selected={session.id === selectedId}
                          pinned={pinnedIdSet.has(session.id)}
                          now={now}
                          pending={pending.get(session.id)}
                          activity={activity[session.id]}
                          onOpen={(s) => {
                            closeScheduleView()
                            void (s.active || s.headless_open
                              ? selectByUser(s.id)
                              : openSession(s.id, projectPath))
                          }}
                          onClose={(s) => { void closeSession(s.id) }}
                          onRename={(s) => {
                            setRenameTarget(s)
                            setRenameValue(displayTitle(s))
                          }}
                          onFork={(s) => { void forkSession(s.id) }}
                          onArchive={(s) => { void archiveSession(s.id) }}
                          onDelete={(s) => {
                            setDeleteTarget(s)
                            setDeleteAcknowledged(false)
                          }}
                          onTogglePin={handleTogglePin}
                          t={t}
                        />
                      ))
                    ) : (
                      filtered.projects.map((project) => (
                        <GroupSection
                          key={project.project_path}
                          project={project}
                          expanded={!collapsedGroups.includes(project.project_path)}
                          overflowExpanded={expandedGroups.includes(project.project_path)}
                          selectedId={selectedId}
                          pinnedIds={pinnedIdSet}
                          now={now}
                          pending={pending}
                          activity={activity}
                          containsCurrent={project.project_path === selectedProject?.project_path}
                          onToggle={() => {
                            setCollapsedGroups((keys) => (
                              keys.includes(project.project_path)
                                ? keys.filter((key) => key !== project.project_path)
                                : [...keys, project.project_path]
                            ))
                          }}
                          onNewSession={() => {
                            closeScheduleView()
                            void createSession(project.project_path)
                          }}
                          onHideProject={() => {
                            hideProject(project.project_path)
                            if (selectedProject?.project_path === project.project_path) {
                              clearSelection()
                            }
                            toast.info(`已隐藏项目「${project.project_name}」（保留全部会话记录）`)
                          }}
                          onDeleteProject={() => {
                            setDeleteProjectTarget(project)
                            setDeleteProjectAcknowledged(false)
                          }}
                          onOpen={(session) => {
                            closeScheduleView()
                            void (session.active || session.headless_open
                              ? selectByUser(session.id)
                              : openSession(session.id, project.project_path))
                          }}
                          onClose={(session) => { void closeSession(session.id) }}
                          onRename={(session) => {
                            setRenameTarget(session)
                            setRenameValue(displayTitle(session))
                          }}
                          onFork={(session) => { void forkSession(session.id) }}
                          onArchive={(session) => { void archiveSession(session.id) }}
                    onDelete={(session) => {
                      setDeleteTarget(session)
                      setDeleteAcknowledged(false)
                    }}
                    onTogglePin={handleTogglePin}
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
                      ))
                    )}
                  </>
                )}
              </div>
              <span className={browserCss.fade} />
            </div>
          </div>
        </div>
        )}
      </div>
      <div className={css.footArea}>
        <div className={css.footerActions}>
          <ScheduledTasksNavButton wide={wide} />
        </div>
        <div className={css.footerActions}>
          <ContextDashboardButton wide={wide} />
        </div>
        <div className={css.settingsArea}>
          <div className={clsx(triggerCss.triggerRow, !wide && triggerCss.railRow)}>
            <button
              type="button"
              className={clsx(triggerCss.trigger, !wide && triggerCss.rail)}
              onClick={() => { setSettingsOpen(true) }}
              aria-haspopup="dialog"
              aria-expanded={settingsOpen}
              aria-label={uiT('sidebar.openSettings')}
            >
              <IconSettingsOutline16 size={16} />
              {wide && <span className={triggerCss.triggerLabel}>{uiT('sidebar.settings')}</span>}
            </button>
          </div>
        </div>
        {wide && <Pill>Pokemon Code</Pill>}
      </div>
      <SettingsPanel open={settingsOpen} onClose={() => { setSettingsOpen(false) }} />
      <CreateProjectModal
        open={workspacePickerOpen}
        onClose={() => { setWorkspacePickerOpen(false) }}
        onCreated={(created) => {
          addWorkspace(created.path)
          closeScheduleView()
          clearSelection()
          const extraCount = created.additionalDirectories.length
          toast.success(extraCount > 0
            ? `已创建项目「${created.name}」（含 ${extraCount} 个附加目录）`
            : `已创建项目「${created.name}」`)
        }}
      />
      <Modal
        open={renameTarget != null}
        onClose={() => { setRenameTarget(null) }}
        title={t('rename.title')}
        closeLabel={t('dialog.close.aria')}
        footer={(
          <>
            <Button variant="outline" onClick={() => { setRenameTarget(null) }}>
              {t('rename.cancel')}
            </Button>
            <Button
              variant="primary"
              disabled={renameValue.trim() === ''}
              onClick={() => {
                const target = renameTarget
                if (target == null) return
                setRenameTarget(null)
                void renameSession(target.id, renameValue.trim())
              }}
            >
              {t('rename.confirm')}
            </Button>
          </>
        )}
      >
        <Input
          value={renameValue}
          placeholder={t('rename.placeholder')}
          autoFocus
          onChange={(e) => { setRenameValue(e.currentTarget.value) }}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && renameValue.trim() !== '' && renameTarget != null) {
              const target = renameTarget
              setRenameTarget(null)
              void renameSession(target.id, renameValue.trim())
            }
          }}
        />
      </Modal>
      <RiskConfirmation
        open={deleteTarget != null}
        title={t('delete.title')}
        description={t('delete.description')}
        acknowledgeLabel={t('delete.acknowledge')}
        cancelLabel={t('delete.cancel')}
        closeLabel={t('dialog.close.aria')}
        confirmLabel={t('delete.confirm')}
        acknowledged={deleteAcknowledged}
        onAcknowledgedChange={setDeleteAcknowledged}
        onCancel={() => { setDeleteTarget(null) }}
        onConfirm={() => {
          const target = deleteTarget
          if (target == null) return
          setDeleteTarget(null)
          void deleteSession(target.id)
        }}
      />
      <RiskConfirmation
        open={deleteProjectTarget != null}
        title={t('deleteProject.title')}
        description={deleteProjectTarget != null
          ? t('deleteProject.description', {
              name: deleteProjectTarget.project_name,
              count: deleteProjectTarget.session_count || deleteProjectTarget.sessions.length,
            })
          : ''}
        acknowledgeLabel={t('delete.acknowledge')}
        cancelLabel={t('delete.cancel')}
        closeLabel={t('dialog.close.aria')}
        confirmLabel={t('delete.confirm')}
        acknowledged={deleteProjectAcknowledged}
        onAcknowledgedChange={setDeleteProjectAcknowledged}
        onCancel={() => { setDeleteProjectTarget(null) }}
        onConfirm={() => {
          const target = deleteProjectTarget
          if (target == null) return
          setDeleteProjectTarget(null)
          removeWorkspace(target.project_path)
          void deleteProject(target)
        }}
      />
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
function GroupSection({ project, expanded, overflowExpanded, selectedId, pinnedIds, now, pending, activity, containsCurrent, onToggle, onNewSession, onHideProject, onDeleteProject, onOpen, onClose, onRename, onFork, onArchive, onDelete, onTogglePin, onOverflowToggle, t }: {
  project: CatalogProject
  expanded: boolean
  /** Rows unfolded past the 5-row limit (upstream's sessionsExpanded). */
  overflowExpanded: boolean
  selectedId: string | null
  pinnedIds: ReadonlySet<string>
  now: number
  /** Session id → the interaction it is waiting on, for the rows' status dots. */
  pending: ReadonlyMap<string, PendingInteraction>
  /** Session id → its live turn state, for the rows' status dots. */
  activity: Readonly<Record<string, SessionActivity>>
  containsCurrent: boolean
  onToggle: () => void
  onNewSession: () => void
  onHideProject: () => void
  onDeleteProject: () => void
  onOpen: (session: CatalogSession) => void
  onClose: (session: CatalogSession) => void
  onRename: (session: CatalogSession) => void
  onFork: (session: CatalogSession) => void
  onArchive: (session: CatalogSession) => void
  onDelete: (session: CatalogSession) => void
  onTogglePin: (session: CatalogSession) => void
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
        onNewSession={onNewSession}
        onHideProject={onHideProject}
        onDeleteProject={onDeleteProject}
        t={t}
      />
      {rows.map((session) => (
        <SessionNodeItem
          key={session.id}
          session={session}
          selected={session.id === selectedId}
          pinned={pinnedIds.has(session.id)}
          now={now}
          pending={pending.get(session.id)}
          activity={activity[session.id]}
          onOpen={onOpen}
          onClose={onClose}
          onRename={onRename}
          onFork={onFork}
          onArchive={onArchive}
          onDelete={onDelete}
          onTogglePin={onTogglePin}
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

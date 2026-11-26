/**
 * Session-tree row components, ported from dsh ui-workspace's Rows.tsx by
 * subtraction. Upstream coverage (TS files: ui-workspace/src/client/rows/Rows.tsx):
 * - `ProjectRowItem` — the 34px project header row: folder glyph that swaps to
 *   the expand chevron on hover, active-tinted folder when the group holds the
 *   current session, row action (new session) revealed on hover.
 * - `SessionNodeItem` — the 32px session row: 16px status slot, ellipsized
 *   title, trailing relative time that swaps to row actions on hover, and the
 *   "..." row menu (rename/fork/archive/delete — see below) ported from
 *   upstream's `sessionMenuItems`.
 * - `sessionStatuses` — the pending-interaction-first status ordering, for the
 *   two kinds this gateway produces (approval, question). `plan-review` and the
 *   subagent counts have no wire equivalent; `running` and `completed` do, via
 *   {@link SessionActivity} — see `sessionStatus` and `showSessionStatus`.
 * - `timeLabel`/`displayTitle` helpers (search-result and hover-card variants
 *   dropped — no search, no HoverCard in this port).
 * Dropped by subtraction (no backend surface): workspace rename/delete menus,
 * drag-and-drop reorder wiring, HoverCard previews, schedule indicator,
 * subagent statuses, the visually-hidden status labels.
 * Close-headless is the one row action this gateway has that upstream
 * expresses as a menu item instead — it stays a bare trailing icon, not a
 * menu entry. Delete is this project's own extension (no upstream
 * counterpart): see webui/UPSTREAM.md.
 */
import { useRef, useState } from 'react'
import clsx from 'clsx'
import {
  IconArchiveOutline20, IconBranchOutline16, IconEditOutline16, IconEllipsisOutline16,
  IconFolderCloseRegular, IconFolderOpenRegular, IconPlusOutline16, IconTrashOutline16,
  IconTriangleRightFillRegular, MarqueeText, Menu, relativeTime, StateDot, useHoverMarquee,
} from '@primitives'
import type { MenuEntry, StateDotState } from '@primitives'
import type { CatalogSession } from '../api/types'
import type { PendingInteraction } from '../store/approvals'
import type { SessionActivity } from '../store/sessions'
import { useCcConnect } from '../store/ccConnect'
import css from '@chat-styles/WorkspaceRows.module.css'
import localCss from './Sidebar.module.css'

export type RowTranslate = (key: string, params?: Readonly<Record<string, string | number>>) => string

/** Row display title: custom title, then summary, then first prompt, then the id head. */
export function displayTitle(session: CatalogSession): string {
  return session.custom_title ?? session.summary ?? session.first_prompt ?? session.id.slice(0, 8)
}

/** Localized compact relative time ("刚刚"/"5分钟" in zh, "now"/"5min" in en). */
export function timeLabel(updatedAt: number, now: number, t: RowTranslate): string {
  const { unit, n } = relativeTime(updatedAt, now)
  return unit === 'now' ? t('time.now') : t(`time.${unit}`, { n })
}

/**
 * Session status presentation, reduced from upstream's `sessionStatuses`.
 *
 * The precedence is upstream's, in upstream's order: a pending interaction
 * outranks everything (a session waiting on the user is the one state you must
 * not have to hunt for), then a turn in flight, then an unacknowledged
 * completion, then idle.
 *
 * The `completed` green dot is NOT "this session finished a task" — it is
 * upstream's `completionUnread`, a "ran to completion while you were looking
 * elsewhere, come see it" reminder that the act of opening the session clears.
 * That is why it comes from {@link SessionActivity} rather than from a catalog
 * field: only a client watching the stream can see the transition that creates
 * it, and only the client knows whether the user has since looked.
 *
 * `active` (the TUI's current session) deliberately does NOT map to a status:
 * being the TUI's session says nothing about whether a turn is running. TUI
 * ownership is already surfaced via the dedicated `TUI` badge rather than a
 * dot tint.
 */
export function sessionStatus(
  activity: SessionActivity | undefined,
  pending?: PendingInteraction,
): { state: StateDotState; labelKey: string } {
  if (pending === 'approval') return { state: 'warning', labelKey: 'status.waitingApproval' }
  if (pending === 'question') return { state: 'warning', labelKey: 'status.waitingAnswer' }
  if (activity?.running === true) return { state: 'ongoing', labelKey: 'status.running' }
  if (activity?.completed === true) return { state: 'done', labelKey: 'status.completed' }
  return { state: 'idle', labelKey: 'status.idle' }
}

/**
 * Whether the row draws its status dot at all.
 *
 * Matches upstream: hide the dot when the primary status is idle
 * (`primaryStatus.state !== 'idle'`), leaving the 16px slot blank rather than
 * showing a grey dot. Active signals use StateDot's native semantics:
 * amber (`warning`) for pending approval/question, blue chase (`ongoing`) for
 * running, and green (`done`) for an unread completion.
 */
export function showSessionStatus(
  _session: CatalogSession,
  activity: SessionActivity | undefined,
  pending?: PendingInteraction,
): boolean {
  return sessionStatus(activity, pending).state !== 'idle'
}

/**
 * Project (workspace) header row, ported from upstream ProjectRowItem:
 * folder glyph swapping to expand chevron on hover, active-tinted folder when
 * the group holds the current session, and trailing hover actions (`...` menu
 * for project management/deletion and `+` to create a new session in this
 * workspace).
 */
export function ProjectRowItem({ label, expanded, containsCurrent, onToggle, onNewSession, onHideProject, onDeleteProject, t }: {
  label: string
  expanded: boolean
  /** The group holds the current session (active folder tint). */
  containsCurrent: boolean
  onToggle: () => void
  /** Creates a fresh headless session directly in this project. */
  onNewSession?: (() => void) | undefined
  /** Hides this project from the sidebar list while keeping all session transcripts intact. */
  onHideProject?: (() => void) | undefined
  /** Opens the delete-project confirmation dialog for this workspace. */
  onDeleteProject?: (() => void) | undefined
  t?: RowTranslate | undefined
}) {
  const active = expanded && containsCurrent
  const [menuOpen, setMenuOpen] = useState(false)
  const hasActions = onNewSession !== undefined || onHideProject !== undefined || onDeleteProject !== undefined
  const topItems: MenuEntry[] = [
    ...(onNewSession !== undefined
      ? [{
          id: 'new-session',
          label: t ? t('actions.newSession') : '新建会话',
          icon: <IconPlusOutline16 />,
        }]
      : []),
    ...(onHideProject !== undefined
      ? [{
          id: 'hide-project',
          label: t ? t('actions.hideProject') : '从列表隐藏',
          icon: <IconArchiveOutline20 size={16} />,
        }]
      : []),
  ]
  const menuItems: readonly MenuEntry[] = [
    ...topItems,
    ...(topItems.length > 0 && onDeleteProject !== undefined
      ? [{ type: 'separator' as const, id: 'sep' }]
      : []),
    ...(onDeleteProject !== undefined
      ? [{
          id: 'delete-project',
          label: t ? t('actions.deleteProject') : '删除项目会话',
          icon: <IconTrashOutline16 />,
          danger: true,
        }]
      : []),
  ]
  const newSessionLabel = t ? t('actions.newSession.aria', { name: label }) : `在“${label}”中新建会话`
  const menuAriaLabel = t ? t('actions.projectMenu.aria', { name: label }) : `项目“${label}”的更多操作`

  return (
    <div
      className={clsx(css.projectRow, menuOpen && css.menuOpen)}
      role="treeitem"
      aria-expanded={expanded}
      onClick={onToggle}
    >
      <span className={clsx(css.slot, css.folder, active && css.folderActive)}>
        {expanded ? <IconFolderOpenRegular /> : <IconFolderCloseRegular />}
      </span>
      <span className={clsx(css.slot, css.chevron)}>
        <IconTriangleRightFillRegular className={clsx(css.arrow, expanded && css.arrowOpen)} />
      </span>
      <span className={css.projectText}>
        <span className={css.title}>{label}</span>
      </span>
      {hasActions && (
        <span
          className={css.rowActions}
          onClick={(event) => { event.stopPropagation() }}
        >
          {menuItems.length > 0 && (
            <Menu
              open={menuOpen}
              onClose={() => { setMenuOpen(false) }}
              align="end"
              portal
              items={menuItems}
              onSelect={(id) => {
                setMenuOpen(false)
                if (id === 'new-session') onNewSession?.()
                else if (id === 'hide-project') onHideProject?.()
                else if (id === 'delete-project') onDeleteProject?.()
              }}
              anchor={(
                <button
                  type="button"
                  className={css.iconButton}
                  aria-label={menuAriaLabel}
                  aria-haspopup="menu"
                  aria-expanded={menuOpen}
                  title={menuAriaLabel}
                  onClick={() => { setMenuOpen((open) => !open) }}
                >
                  <IconEllipsisOutline16 />
                </button>
              )}
            />
          )}
          {onNewSession !== undefined && (
            <button
              type="button"
              className={css.iconButton}
              aria-label={newSessionLabel}
              title={newSessionLabel}
              onClick={onNewSession}
            >
              <IconPlusOutline16 />
            </button>
          )}
        </span>
      )}
    </div>
  )
}

/** Monochrome thumbtack pin glyph matching the 16px outline icon grammar. */
export function PinGlyph({ filled = false }: { filled?: boolean }) {
  return (
    <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
      <path
        d="M9.8 2.4L13.6 6.2L12.5 7.3L11.4 6.6L8.4 9.6L9.1 11.4L8 12.5L5.6 10.1L2.6 13.1L2.9 10.4L5.9 7.4L3.5 5L4.6 3.9L6.4 4.6L9.4 1.6L9.8 2.4Z"
        fill={filled ? 'currentColor' : 'none'}
        stroke="currentColor"
        strokeWidth="1.25"
        strokeLinejoin="round"
      />
    </svg>
  )
}

/**
 * One top-level 32px session row, ported from upstream SessionNodeItem minus
 * drag wiring, HoverCard, and the schedule/subagent indicators. The trailing
 * cell keeps upstream's hover swap: `.rowResting` (relative time + optional
 * pinned indicator) by default, `.rowActions` (pin toggle, "..." menu, and
 * optional close button) on hover — all in the same flex row so buttons never
 * overlap the title. Long titles clip with an ellipsis at rest and marquee
 * smoothly on hover via upstream's `MarqueeText` + `useHoverMarquee`.
 */
export function SessionNodeItem({ session, selected, pinned = false, now, activity, pending, rowTestId, pinTestId, onOpen, onClose, onRename, onFork, onArchive, onDelete, onTogglePin, t }: {
  session: CatalogSession
  selected: boolean
  /** Whether this session is pinned in the sidebar. */
  pinned?: boolean
  /** Epoch ms for relative-time formatting. */
  now: number
  /**
   * This session's live turn state, upstream's `node.running`/`node.completed`.
   * Absent for a session the stream has said nothing about.
   */
  activity?: SessionActivity | undefined
  /**
   * What this session is waiting on, upstream's `node.pendingInteraction`. It
   * arrives as a prop because this gateway's catalog rows carry no interaction
   * field — the mirror stream's asks do.
   */
  pending?: PendingInteraction | undefined
  rowTestId?: string | undefined
  pinTestId?: string | undefined
  onOpen: (session: CatalogSession) => void
  onClose: (session: CatalogSession) => void
  /** Opens the rename dialog for this session. */
  onRename: (session: CatalogSession) => void
  /** Forks this session at its last completed turn, no confirmation. */
  onFork: (session: CatalogSession) => void
  /** Archives this session (one-way hide), no confirmation. */
  onArchive: (session: CatalogSession) => void
  /** Opens the delete confirmation dialog for this session. */
  onDelete: (session: CatalogSession) => void
  /** Toggles pinned state for this session. */
  onTogglePin?: ((session: CatalogSession) => void) | undefined
  t: RowTranslate
}) {
  const status = sessionStatus(activity, pending)
  const showStatus = showSessionStatus(session, activity, pending)
  const [menuOpen, setMenuOpen] = useState(false)
  const titleRef = useRef<HTMLSpanElement>(null)
  const marquee = useHoverMarquee(titleRef, { hold: menuOpen })
  const ccTarget = useCcConnect((state) => state.snapshot?.targets.find(
    (t) => t.session_id !== '' && t.session_id === session.id,
  ))
  const ccConfigured = useCcConnect((state) => state.snapshot?.configured ?? false)
  const ccBusy = useCcConnect((state) => (ccTarget != null ? Boolean(state.busyPids[ccTarget.pid]) : false))
  const ccChannel = ccTarget?.attached === true ? ccTarget.channel : ''
  const title = displayTitle(session)
  const menuItems: readonly MenuEntry[] = [
    ...(onTogglePin !== undefined
      ? [{
          id: 'toggle-pin',
          label: pinned ? '取消置顶' : '置顶会话',
          icon: <PinGlyph filled={pinned} />,
        }]
      : []),
    ...(ccTarget != null && (ccTarget.attached || ccConfigured)
      ? [{
          id: ccTarget.attached ? 'cc-detach' : 'cc-attach',
          label: ccTarget.attached ? `断开 ${ccTarget.channel || '飞书'} 协作` : '开启飞书协作',
          icon: <IconBranchOutline16 />,
        }]
      : []),
    { id: 'rename', label: t('actions.rename'), icon: <IconEditOutline16 /> },
    { id: 'fork', label: t('actions.fork'), icon: <IconBranchOutline16 /> },
    { id: 'archive', label: t('actions.archive'), icon: <IconArchiveOutline20 size={16} /> },
    { type: 'separator', id: 'sep' },
    { id: 'delete', label: t('actions.delete'), icon: <IconTrashOutline16 />, danger: true },
  ]
  return (
    <div
      className={clsx(css.sessionRow, selected && css.selected, menuOpen && css.menuOpen)}
      data-testid={rowTestId}
      role="treeitem"
      aria-selected={selected}
      onClick={() => { onOpen(session) }}
      onPointerEnter={marquee.onPointerEnter}
      onPointerLeave={marquee.onPointerLeave}
    >
      <span className={css.slot}>
        {showStatus && (
          <StateDot state={status.state} />
        )}
      </span>
      {(session.tui_attached === true || session.remote === true) && (
        <span
          className={localCss.tuiBadge}
          title={session.tui_pid != null ? `在线 TUI 进程 (PID ${session.tui_pid})` : '在线 TUI 进程'}
        >
          TUI
        </span>
      )}
      {ccChannel !== '' && ccTarget != null && (
        <button
          type="button"
          className={localCss.ccBadge}
          disabled={ccBusy}
          title={`已连接 ${ccChannel}（点击断开监听）`}
          onClick={(e) => {
            e.stopPropagation()
            void useCcConnect.getState().detach(ccTarget.pid, ccTarget.session_id)
          }}
        >
          <span>{ccChannel}</span>
          <span aria-hidden="true">×</span>
        </button>
      )}
      <MarqueeText ref={titleRef} className={css.title}>{title}</MarqueeText>
      <span className={css.rowResting}>
        <span className={css.time}>{timeLabel(Date.parse(session.modified_at), now, t)}</span>
        {pinned && (
          <span className={css.pinIndicator} aria-label="已置顶" title="已置顶">
            <PinGlyph filled />
          </span>
        )}
      </span>
      <span className={css.rowActions} onClick={(e) => { e.stopPropagation() }}>
        {onTogglePin !== undefined && (
          <button
            type="button"
            className={clsx(css.iconButton, pinned && localCss.pinBtnActive)}
            data-testid={pinTestId ?? `sidebar-pin-${session.id}`}
            aria-label={pinned ? '取消置顶' : '置顶会话'}
            title={pinned ? '取消置顶' : '置顶会话'}
            onClick={(e) => {
              e.stopPropagation()
              onTogglePin(session)
            }}
          >
            <PinGlyph filled={pinned} />
          </button>
        )}
        <Menu
          open={menuOpen}
          onClose={() => { setMenuOpen(false) }}
          items={menuItems}
          align="end"
          portal
          onSelect={(id) => {
            setMenuOpen(false)
            if (id === 'toggle-pin') onTogglePin?.(session)
            else if (id === 'cc-attach' && ccTarget != null) {
              void useCcConnect.getState().attach(ccTarget.pid, ccTarget.session_id, 'feishu')
            } else if (id === 'cc-detach' && ccTarget != null) {
              void useCcConnect.getState().detach(ccTarget.pid, ccTarget.session_id)
            } else if (id === 'rename') onRename(session)
            else if (id === 'fork') onFork(session)
            else if (id === 'archive') onArchive(session)
            else if (id === 'delete') onDelete(session)
          }}
          anchor={(
            <button
              type="button"
              className={css.iconButton}
              aria-haspopup="menu"
              aria-expanded={menuOpen}
              aria-label={t('actions.menu.aria', { name: title })}
              onClick={(e) => {
                e.stopPropagation()
                setMenuOpen((current) => !current)
              }}
            >
              <IconEllipsisOutline16 />
            </button>
          )}
        />
        {session.headless_open && !session.active && (
          <button
            type="button"
            className={css.iconButton}
            aria-label={t('actions.close.aria', { name: title })}
            onClick={(e) => {
              e.stopPropagation()
              onClose(session)
            }}
          >
            <IconPlusOutline16 className={localCss.closeGlyph} />
          </button>
        )}
      </span>
    </div>
  )
}

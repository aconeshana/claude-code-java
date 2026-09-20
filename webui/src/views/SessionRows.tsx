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
 *   running/subagent counts have no wire equivalent, so the remainder folds to
 *   the active/headless readings described on `sessionStatus`.
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
import { useState } from 'react'
import clsx from 'clsx'
import {
  IconArchiveOutline20, IconBranchOutline16, IconEditOutline16, IconEllipsisOutline16,
  IconFolderClose16, IconFolderOpen16, IconPlusOutline16, IconTrashOutline16,
  IconTriangleRightFill14, Menu, relativeTime, StateDot,
} from '@primitives'
import type { MenuEntry, StateDotState } from '@primitives'
import type { CatalogSession } from '../api/types'
import type { PendingInteraction } from '../store/approvals'
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
 * Session status presentation, reduced from upstream's sessionStatuses: a
 * pending interaction is primary and outranks everything, exactly as upstream
 * orders it — a session waiting on the user is the one state you must not have
 * to hunt for. Below that this gateway exposes only active (the TUI's
 * conversation) and headless_open (a gateway headless session); running/idle
 * have no wire equivalent. The selected headless session shows as ongoing — it
 * is the one live mirror stream the webui is attached to.
 */
export function sessionStatus(
  session: CatalogSession,
  selected: boolean,
  pending?: PendingInteraction,
): { state: StateDotState; labelKey: string } {
  if (pending === 'approval') return { state: 'warning', labelKey: 'status.waitingApproval' }
  if (pending === 'question') return { state: 'warning', labelKey: 'status.waitingAnswer' }
  if (session.active) return { state: 'done', labelKey: 'status.completed' }
  if (session.headless_open) {
    return { state: selected ? 'ongoing' : 'done', labelKey: selected ? 'status.running' : 'status.idle' }
  }
  return { state: 'done', labelKey: 'status.idle' }
}

/**
 * Project (workspace) header row, ported from upstream ProjectRowItem minus
 * the workspace menu/rename/delete actions and drag wiring.
 */
export function ProjectRowItem({ label, expanded, containsCurrent, onToggle }: {
  label: string
  expanded: boolean
  /** The group holds the current session (active folder tint). */
  containsCurrent: boolean
  onToggle: () => void
}) {
  const active = expanded && containsCurrent
  return (
    <div
      className={css.projectRow}
      role="treeitem"
      aria-expanded={expanded}
      onClick={onToggle}
    >
      <span className={clsx(css.slot, css.folder, active && css.folderActive)}>
        {expanded ? <IconFolderOpen16 /> : <IconFolderClose16 />}
      </span>
      <span className={clsx(css.slot, css.chevron)}>
        <IconTriangleRightFill14 className={clsx(css.arrow, expanded && css.arrowOpen)} />
      </span>
      <span className={css.projectText}>
        <span className={css.title}>{label}</span>
      </span>
    </div>
  )
}

/**
 * One top-level 32px session row, ported from upstream SessionNodeItem minus
 * drag wiring, HoverCard, and the schedule/subagent indicators. The trailing
 * cell keeps upstream's hover swap: relative time by default, row actions on
 * hover. The row menu (rename/fork/archive, mirroring upstream's
 * `sessionMenuItems`, plus this project's own delete) opens before the close
 * button — upstream's own row-action ordering. `onClose` is this gateway's
 * own action (closing a headless session) rendered with upstream's bare 16px
 * trailing-icon grammar, outside the menu.
 */
export function SessionNodeItem({ session, selected, now, pending, onOpen, onClose, onRename, onFork, onArchive, onDelete, t }: {
  session: CatalogSession
  selected: boolean
  /** Epoch ms for relative-time formatting. */
  now: number
  /**
   * What this session is waiting on, upstream's `node.pendingInteraction`. It
   * arrives as a prop because this gateway's catalog rows carry no interaction
   * field — the mirror stream's asks do.
   */
  pending?: PendingInteraction | undefined
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
  t: RowTranslate
}) {
  const status = sessionStatus(session, selected, pending)
  // A session waiting on the user lights up whether or not it is live: the ask
  // can only be answered here, so hiding the dot would strand it.
  const showStatus = pending != null || session.active || session.headless_open
  const [menuOpen, setMenuOpen] = useState(false)
  const title = displayTitle(session)
  const menuItems: readonly MenuEntry[] = [
    { id: 'rename', label: t('actions.rename'), icon: <IconEditOutline16 /> },
    { id: 'fork', label: t('actions.fork'), icon: <IconBranchOutline16 /> },
    { id: 'archive', label: t('actions.archive'), icon: <IconArchiveOutline20 size={16} /> },
    { type: 'separator', id: 'sep' },
    { id: 'delete', label: t('actions.delete'), icon: <IconTrashOutline16 />, danger: true },
  ]
  return (
    <div
      className={clsx(css.sessionRow, selected && css.selected, menuOpen && css.menuOpen)}
      role="treeitem"
      aria-selected={selected}
      onClick={() => { onOpen(session) }}
    >
      <span className={css.slot}>
        {showStatus && <StateDot state={status.state} />}
      </span>
      <span className={css.title}>{title}</span>
      <span className={css.time}>{timeLabel(Date.parse(session.modified_at), now, t)}</span>
      <span className={css.rowActions}>
        <Menu
          open={menuOpen}
          onClose={() => { setMenuOpen(false) }}
          items={menuItems}
          align="end"
          portal
          onSelect={(id) => {
            setMenuOpen(false)
            if (id === 'rename') onRename(session)
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

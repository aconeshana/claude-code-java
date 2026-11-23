import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import clsx from 'clsx'
import { IconCheckOutline16, IconRefreshOutline14, StateDot, Tooltip } from '@primitives'
import type { ShellEntry } from '../api/types'
import css from '../../vendor/ui-subagent/SubagentCatalogMenu.module.css'

/**
 * Header background-shell catalog: paired beside `SubagentCatalogMenu` in
 * `ConversationRoot`'s `headerActions` band, reusing the vendored
 * `SubagentCatalogMenu.module.css` surface and trigger vocabulary so both
 * header switchers read as one family.
 */
export interface ShellCatalogMenuProps {
  readonly shells: readonly ShellEntry[]
  readonly currentShellId?: string | undefined
  readonly failed?: boolean | undefined
  readonly onOpen: (taskId: string) => void
  readonly onRetry?: (() => void) | undefined
  readonly className?: string | undefined
}

const HOVER_OPEN_DELAY_MS = 150
const HOVER_CLOSE_DELAY_MS = 120
const VIEWPORT_MARGIN = 12
const MENU_GAP = 6

function TerminalIcon({ size = 14 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none" aria-hidden>
      <rect x="1.75" y="2.75" width="12.5" height="10.5" rx="2" stroke="currentColor" strokeWidth="1.35" />
      <path d="M4.75 6.25L7 8.25L4.75 10.25" stroke="currentColor" strokeWidth="1.35" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M8.5 10.25H11.25" stroke="currentColor" strokeWidth="1.35" strokeLinecap="round" />
    </svg>
  )
}

function formatElapsed(startMs: number, endMs?: number): string {
  const sec = Math.max(0, Math.floor(((endMs ?? Date.now()) - startMs) / 1_000))
  if (sec < 60) return `${sec}秒`
  const min = Math.floor(sec / 60)
  if (min < 60) return `${min}分${sec % 60}秒`
  const hr = Math.floor(min / 60)
  return `${hr}小时${min % 60}分`
}

function statusLabel(entry: ShellEntry): string {
  switch (entry.status) {
    case 'running': return '正在运行'
    case 'completed': return entry.exit_code != null ? `退出码 ${entry.exit_code}` : '已完成'
    case 'failed': return entry.exit_code != null ? `失败 (${entry.exit_code})` : '已失败'
    case 'killed': return '已停止'
    default: return entry.status
  }
}

export function ShellCatalogMenu({
  shells,
  currentShellId,
  failed = false,
  onOpen,
  onRetry,
  className,
}: ShellCatalogMenuProps) {
  const [open, setOpen] = useState(false)
  const [pinned, setPinned] = useState(false)
  const rootRef = useRef<HTMLDivElement>(null)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const menuRef = useRef<HTMLDivElement>(null)
  const hoverOpenTimer = useRef<number | null>(null)
  const hoverCloseTimer = useRef<number | null>(null)

  const runningCount = shells.filter((s) => s.status === 'running').length
  const visible = failed || shells.length > 0

  const cancelHover = useCallback(() => {
    if (hoverOpenTimer.current !== null) {
      window.clearTimeout(hoverOpenTimer.current)
      hoverOpenTimer.current = null
    }
    if (hoverCloseTimer.current !== null) {
      window.clearTimeout(hoverCloseTimer.current)
      hoverCloseTimer.current = null
    }
  }, [])

  const changeOpen = useCallback((next: boolean, restoreFocus = false) => {
    setOpen(next)
    if (!next) {
      setPinned(false)
      if (restoreFocus) triggerRef.current?.focus()
    }
  }, [])

  useEffect(() => {
    if (!visible && open) changeOpen(false)
  }, [visible, open, changeOpen])

  useEffect(() => () => { cancelHover() }, [cancelHover])

  useLayoutEffect(() => {
    if (!open) return
    const place = (): void => {
      const trigger = triggerRef.current
      const menu = menuRef.current
      if (trigger === null || menu === null) return
      const rect = trigger.getBoundingClientRect()
      const vw = window.innerWidth
      const vh = window.innerHeight
      const mw = menu.offsetWidth || 360
      const mh = menu.offsetHeight || 240
      const left = Math.max(VIEWPORT_MARGIN, Math.min(rect.right - mw, vw - mw - VIEWPORT_MARGIN))
      const top = rect.bottom + MENU_GAP + mh <= vh - VIEWPORT_MARGIN
        ? rect.bottom + MENU_GAP
        : Math.max(VIEWPORT_MARGIN, rect.top - MENU_GAP - mh)
      menu.style.position = 'fixed'
      menu.style.left = `${left}px`
      menu.style.top = `${top}px`
      menu.style.zIndex = '1200'
    }
    place()
    window.addEventListener('resize', place)
    window.addEventListener('scroll', place, true)
    return () => {
      window.removeEventListener('resize', place)
      window.removeEventListener('scroll', place, true)
    }
  }, [open, shells.length])

  useEffect(() => {
    if (!open) return
    const onPointerDown = (ev: PointerEvent): void => {
      const target = ev.target
      if (!(target instanceof Node)) return
      if (rootRef.current?.contains(target) || menuRef.current?.contains(target)) return
      changeOpen(false)
    }
    const onKeyDown = (ev: KeyboardEvent): void => {
      if (ev.key !== 'Escape') return
      ev.preventDefault()
      changeOpen(false, menuRef.current?.contains(document.activeElement) === true)
    }
    document.addEventListener('pointerdown', onPointerDown)
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('pointerdown', onPointerDown)
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [open, changeOpen])

  if (!visible) return null

  const label = `${shells.length} 个终端`

  return (
    <div
      ref={rootRef}
      className={clsx(css.root, className)}
      data-shell-action
      onPointerLeave={() => {
        if (pinned) return
        cancelHover()
        hoverCloseTimer.current = window.setTimeout(() => { changeOpen(false) }, HOVER_CLOSE_DELAY_MS)
      }}
    >
      <Tooltip label={label} disabled={open} delayMs={500} side="bottom">
        <button
          ref={triggerRef}
          type="button"
          className={css.trigger}
          aria-haspopup="dialog"
          aria-expanded={open}
          aria-label={label}
          onPointerEnter={(ev) => {
            if (ev.pointerType === 'touch' || pinned) return
            cancelHover()
            hoverOpenTimer.current = window.setTimeout(() => { changeOpen(true) }, HOVER_OPEN_DELAY_MS)
          }}
          onClick={() => {
            cancelHover()
            if (open && pinned) {
              changeOpen(false, true)
              return
            }
            setPinned(true)
            if (!open) changeOpen(true)
            queueMicrotask(() => { menuRef.current?.focus() })
          }}
        >
          <span className={css.activitySlot} aria-hidden>
            <TerminalIcon size={14} />
            {runningCount > 0 && <StateDot state="ongoing" className={css.textModeActivity} />}
          </span>
          <span className={css.count}>{label}</span>
        </button>
      </Tooltip>
      {open && createPortal((
        <div
          ref={menuRef}
          className={css.menu}
          role="dialog"
          tabIndex={-1}
          aria-label="后台终端任务"
          data-shell-panel
          onPointerEnter={(ev) => {
            if (ev.pointerType !== 'touch') cancelHover()
          }}
        >
          <div className={css.menuBody}>
            <div className={css.sectionHeader}>
              <span className={css.sectionIcon} aria-hidden>
                <TerminalIcon size={14} />
              </span>
              <h3 className={css.sectionTitle}>后台终端</h3>
              {runningCount > 0 && (
                <span className={css.sectionProgress}>{runningCount} 进行中</span>
              )}
            </div>
            <div>
              {failed && (
                <div className={css.error}>
                  <span>无法加载后台终端</span>
                  {onRetry != null && (
                    <button type="button" className={css.refresh} onClick={onRetry}>
                      <IconRefreshOutline14 />
                      重试
                    </button>
                  )}
                </div>
              )}
              {shells.map((entry) => {
                const isCurrent = entry.id === currentShellId
                const title = entry.command || entry.description || entry.id
                return (
                  <div
                    key={entry.id}
                    className={clsx(css.row, isCurrent && css.rowCurrent)}
                    data-current={isCurrent ? 'true' : undefined}
                  >
                    <button
                      type="button"
                      className={css.itemButton}
                      onClick={() => {
                        onOpen(entry.id)
                        changeOpen(false)
                      }}
                    >
                      <span className={css.activitySlot} aria-hidden>
                        {entry.status === 'running'
                          ? <StateDot state="ongoing" />
                          : <TerminalIcon size={13} />}
                      </span>
                      <span className={css.itemBody}>
                        <span className={css.itemPrimary} title={title}>{title}</span>
                        <span className={css.itemSecondary}>{statusLabel(entry)}</span>
                      </span>
                      <span className={css.itemMetrics}>
                        <span className={css.metricSecondary}>
                          {formatElapsed(entry.start_time_ms, entry.end_time_ms)}
                        </span>
                      </span>
                      <span className={css.sidebarSpace} aria-hidden>
                        {isCurrent && <IconCheckOutline16 />}
                      </span>
                    </button>
                  </div>
                )
              })}
            </div>
          </div>
        </div>
      ), document.body)}
    </div>
  )
}

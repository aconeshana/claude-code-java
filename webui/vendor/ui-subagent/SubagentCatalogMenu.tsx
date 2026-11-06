/**
 * The session header's subagent catalog: a descendant-count trigger that opens
 * a menu listing every subagent this session launched.
 *
 * Vendored from upstream `packages/client/ui-subagent/src/client/
 * SubagentHeaderLineage.tsx` (deepseek-ai/deepseek-harness, MIT). The row
 * vocabulary, the counts, the metric column, and the accessibility structure
 * are upstream's; what is re-written is everything that assumed upstream's
 * session model. See `webui/UPSTREAM.md` for the full provenance table.
 *
 * Two structural facts drive the rewrite:
 *
 * 1. **Upstream's catalog is a session tree; ours is a flat list.** Upstream
 *    browses *child sessions* (`subagentCatalog` projections, one entry per
 *    child, recursed with `toggleBranch` + breadcrumbs). This product's
 *    subagents are in-session `Task`/`Agent` tool calls, so there is no child
 *    to expand into: every row is a leaf, and the recursion, the disclosure
 *    gutter and the breadcrumb switcher are cut. *List* navigation survives
 *    the flattening — `ArrowUp`/`ArrowDown`/`Home`/`End` over the rows, and
 *    the trigger's `ArrowDown` opening onto the first row — because a flat
 *    list is exactly what those keys address; only the tree-specific
 *    left/right collapse-and-descend keys have no meaning here.
 * 2. **Upstream reads cordis projections; ours reads one gateway payload.**
 *    Rows come from `AgentRecord[]` — this gateway's `agents` extension on
 *    `/api/session/context/detail` — not from `useSessions`, so there is no
 *    loading/migration state machine: the caller supplies the records, or
 *    `undefined` while the read is still outstanding.
 *
 * What is preserved verbatim in behaviour: the 500ms hover tooltip, the 150ms
 * hover-open / 120ms hover-close / click-to-pin rules, the click's focus
 * hand-off into the menu, outside-pointer and Escape dismissal (with focus
 * restored to the trigger only when the menu held it), the count trigger's
 * `aria-haspopup`/`aria-expanded`/
 * `aria-describedby` wiring (the running count is the accessible description),
 * the running-indicator slot, the two-line metric column, and the current-row
 * check. The menu surface itself is the shared `Menu` primitive, which owns
 * placement; the vendored stylesheet keeps the surface's own look.
 */
import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import type { KeyboardEvent } from 'react'
import { createPortal } from 'react-dom'
import clsx from 'clsx'
import { IconCheckOutline16, IconRefreshOutline14, StateDot, Tooltip } from '@primitives'
import type { AgentRecord } from '@dsh-context/shared/types'
import { useTranslate } from '../../src/i18n/useTranslate'
import { SUBAGENT_NS, subagentDicts } from '../../src/i18n/dictionaries/subagent'
import { IconUsersOutline } from './IconUsersOutline'
import css from './SubagentCatalogMenu.module.css'

type Translate = ReturnType<typeof useTranslate>

/** How a catalog row reads its own activity. */
type RowActivity = 'running' | 'inactive'

/** One catalog row: an `AgentRecord` with its activity and a stable row key. */
interface CatalogEntry {
  record: AgentRecord
  /** `record.agentId` when the record carries one, else the opening tool-use id. */
  agentId: string
  activity: RowActivity
  /** Description first, then the subagent type, then the id (upstream's `primaryLabel`). */
  primaryLabel: string
  /** The agent name, shown under the description when both exist. */
  secondaryLabel: string | undefined
}

/** Upstream's `formatTokens`: one decimal under 100, whole above, K/M above 1e3/1e6. */
function formatTokens(value: number, t: Translate): string {
  const scaled = (next: number): string => next >= 100
    ? String(Math.round(next))
    : String(Math.round(next * 10) / 10)
  if (value < 1_000) return String(value)
  if (value < 1_000_000) return t('tokens.thousand', { value: scaled(value / 1_000) })
  return t('tokens.million', { value: scaled(value / 1_000_000) })
}

interface DurationParts {
  seconds: number
  minutes: number
  hours: number
  days: number
  totalMinutes: number
  totalHours: number
}

/** Split a millisecond duration into the calendar parts the copy needs. */
function splitDuration(ms: number): DurationParts {
  const totalSeconds = Math.floor(Math.max(0, ms) / 1_000)
  const totalMinutes = Math.floor(totalSeconds / 60)
  const totalHours = Math.floor(totalMinutes / 60)
  return {
    seconds: totalSeconds % 60,
    minutes: totalMinutes % 60,
    hours: totalHours % 24,
    days: Math.floor(totalHours / 24),
    totalMinutes,
    totalHours,
  }
}

/** Upstream's `formatDuration`, complete (a session catalog spans any timescale). */
function formatDuration(ms: number, t: Translate): string {
  const { seconds, minutes, hours, days, totalMinutes, totalHours } = splitDuration(ms)
  if (days >= 365) {
    const years = Math.floor(days / 365)
    const months = Math.floor((days % 365) / 30)
    return months === 0
      ? t('duration.years', { years })
      : t('duration.yearsMonths', { years, months })
  }
  if (days >= 30) {
    const months = Math.floor(days / 30)
    const remainingDays = days % 30
    return remainingDays === 0
      ? t('duration.months', { months })
      : t('duration.monthsDays', { months, days: remainingDays })
  }
  if (days > 0) {
    return hours === 0 ? t('duration.days', { days }) : t('duration.daysHours', { days, hours })
  }
  if (totalHours > 0) {
    return t('duration.hours', {
      hours: totalHours,
      minutes: String(minutes).padStart(2, '0'),
      seconds: String(seconds).padStart(2, '0'),
    })
  }
  if (totalMinutes > 0) {
    return t('duration.minutes', {
      minutes: totalMinutes,
      seconds: String(seconds).padStart(2, '0'),
    })
  }
  return t('duration.seconds', { seconds })
}

/** Preserve exact whole seconds for the hover title and the accessible name. */
function formatExactDuration(ms: number, t: Translate): string {
  const { seconds, minutes, hours, days } = splitDuration(ms)
  return days === 0
    ? formatDuration(ms, t)
    : t('duration.exactDays', {
      days,
      hours: String(hours).padStart(2, '0'),
      minutes: String(minutes).padStart(2, '0'),
      seconds: String(seconds).padStart(2, '0'),
    })
}

/** The four disjoint durable provider-usage buckets, summed (upstream's `tokenTotal`). */
function tokenTotal(record: AgentRecord): number | undefined {
  const usage = record.usage
  if (usage !== undefined) {
    return usage.uncachedInputTokens + usage.outputTokens
      + usage.cacheReadTokens + usage.cacheWriteTokens
  }
  // The fold also serves a flat total for a settled call whose buckets are
  // unavailable; prefer it over showing no metric at all.
  return record.tokens
}

/** Count direct members the way upstream counts a catalog level. */
function catalogProgress(entries: readonly CatalogEntry[], t: Translate): string {
  let completed = 0
  let active = 0
  let inactive = 0
  for (const entry of entries) {
    if (entry.activity === 'running') active++
    else if (entry.record.status === 'done') completed++
    else inactive++
  }
  return [
    ...completed > 0 ? [t('progress.completed', { count: completed })] : [],
    ...active > 0 ? [t('progress.active', { count: active })] : [],
    ...inactive > 0 ? [t('progress.inactive', { count: inactive })] : [],
  ].join('\u2002·\u2002')
}

/**
 * Reduce the session's agent records to catalog rows.
 *
 * Running agents lead (upstream sorts the same way — activity, then most
 * recent), so the reader sees live work first in a long session.
 */
export function catalogEntriesOf(agents: readonly AgentRecord[]): readonly CatalogEntry[] {
  return [...agents]
    .sort((a, b) => {
      const runDelta = Number(b.status === 'running') - Number(a.status === 'running')
      if (runDelta !== 0) return runDelta
      const timeDelta = (b.time ?? b.seq) - (a.time ?? a.seq)
      return timeDelta !== 0 ? timeDelta : (a.id < b.id ? -1 : 1)
    })
    .map((record) => {
      const description = record.description !== undefined && record.description.trim() !== ''
        ? record.description
        : undefined
      const name = record.type !== undefined && record.type.trim() !== '' ? record.type : undefined
      const fallback = record.agentId ?? record.id
      return {
        record,
        agentId: record.agentId ?? record.id,
        activity: record.status === 'running' ? 'running' : 'inactive',
        primaryLabel: description ?? name ?? fallback,
        secondaryLabel: description !== undefined && name !== undefined ? name : undefined,
      }
    })
}

/** The mode copy for a row. Every row here is an in-session call, so upstream's four modes collapse to one. */
function modeLabel(t: Translate): string {
  return t('mode.oneShot')
}

interface CatalogRowProps {
  entry: CatalogEntry
  /** Whether this row's subagent is the one whose transcript is on screen. */
  isCurrent: boolean
  onOpen: (entry: CatalogEntry) => void
  t: Translate
}

/** One catalog row: activity dot, description-over-name, metric column, and the open affordance. */
function CatalogRow({ entry, isCurrent, onOpen, t }: CatalogRowProps) {
  const { record, primaryLabel, secondaryLabel } = entry
  const completed = entry.activity === 'inactive' && record.status === 'done'
  const activityText = entry.activity === 'running'
    ? t('activity.running')
    : completed ? t('activity.completed') : t('activity.inactive')
  const secondary = [secondaryLabel, modeLabel(t), activityText]
    .filter((value) => value !== undefined)
    .join(' · ')

  const total = tokenTotal(record)
  const tokenMetric = total === undefined ? undefined : t('tokens.total', { value: formatTokens(total, t) })
  const durationMetric = record.durationMs === undefined
    ? undefined
    : {
      compact: formatDuration(record.durationMs, t),
      exact: formatExactDuration(record.durationMs, t),
    }
  const metrics = [tokenMetric, durationMetric?.exact]
    .filter((value) => value !== undefined)
    .join(' · ')

  const open = (): void => { onOpen(entry) }
  const handleKey = (event: KeyboardEvent<HTMLDivElement>): void => {
    if (event.key !== 'Enter' && event.key !== ' ') return
    event.preventDefault()
    event.stopPropagation()
    open()
  }

  return (
    <div className={css.node}>
      <div
        role="treeitem"
        tabIndex={0}
        aria-level={1}
        aria-current={isCurrent || undefined}
        aria-label={[primaryLabel, secondary, metrics].filter((value) => value !== '').join(' ')}
        className={css.row}
        onClick={open}
        onKeyDown={handleKey}
      >
        <div className={css.clickarea}>
          <span className={css.rowActivitySlot}>
            <StateDot state={entry.activity === 'running' ? 'ongoing' : completed ? 'done' : 'idle'} />
          </span>
          <span className={css.content}>
            <span className={clsx(css.label, isCurrent && css.currentLabel)}>{primaryLabel}</span>
            {secondaryLabel !== undefined && (
              <span className={css.secondaryLabel}>{secondaryLabel}</span>
            )}
          </span>
          {metrics !== '' && (
            <span className={css.metrics}>
              {tokenMetric !== undefined && <span className={css.metricToken}>{tokenMetric}</span>}
              {durationMetric !== undefined && (
                <span
                  className={css.metricDuration}
                  title={t('duration.exactTitle', { duration: durationMetric.exact })}
                >
                  {durationMetric.compact}
                </span>
              )}
            </span>
          )}
          {/* Upstream seats a "open in sidebar" action here. This app has no
              right-side pane for a subagent conversation, so the slot is
              reserved empty whenever the row also shows the current-check:
              the column width is what keeps the metric column aligned. */}
          {isCurrent
            ? (
              <span className={clsx(css.sidebarSpace, css.currentCheck)} aria-hidden data-subagent-current>
                <IconCheckOutline16 />
              </span>
            )
            : <span className={css.sidebarSpace} />}
        </div>
      </div>
    </div>
  )
}

export interface SubagentCatalogMenuProps {
  /**
   * This session's subagent calls, or `undefined` while the read is still
   * outstanding (the trigger then shows upstream's loading note).
   */
  agents: readonly AgentRecord[] | undefined
  /**
   * The agent whose transcript the chat is currently showing, if any — its row
   * carries the current-check instead of the reserved empty slot.
   */
  currentAgentId?: string | undefined
  /** Open one subagent's conversation in the transcript. */
  onOpen: (agentId: string) => void
  /** Whether a read failed, so the menu offers upstream's retry affordance. */
  failed?: boolean
  /** Read the session's subagent records again (the retry affordance). */
  onRetry?: (() => void) | undefined
  className?: string | undefined
}

/**
 * The header's descendant-count trigger and its subagent catalog.
 *
 * @param props.agents - the session's agent records, or `undefined` while loading.
 * @param props.currentAgentId - the subagent whose transcript is on screen.
 * @param props.onOpen - opens one subagent's conversation.
 * @param props.failed - whether the records read failed.
 * @param props.onRetry - re-reads the records.
 * @param props.className - extra class for the trigger's placement.
 */
export function SubagentCatalogMenu({
  agents, currentAgentId, onOpen, failed = false, onRetry, className,
}: SubagentCatalogMenuProps) {
  const t = useTranslate(SUBAGENT_NS, subagentDicts)
  const entries = useMemo(() => (agents === undefined ? [] : catalogEntriesOf(agents)), [agents])
  const directCount = entries.length
  const runningCount = entries.filter((entry) => entry.activity === 'running').length
  const progress = useMemo(() => catalogProgress(entries, t), [entries, t])

  const [open, setOpen] = useState(false)
  // A click-opened (pinned) menu ignores hover-out; only explicit dismissal closes it.
  const [pinned, setPinned] = useState(false)
  const rootRef = useRef<HTMLDivElement>(null)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const menuRef = useRef<HTMLDivElement>(null)
  const countRef = useRef<HTMLSpanElement>(null)
  const activityId = useId()
  const hoverOpenTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  const hoverCloseTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)

  const totalCountKey = directCount === 1 ? 'count.total.one' : 'count.total.other'
  const runningCountKey = runningCount === 1 ? 'count.running.one' : 'count.running.other'

  const cancelHoverClose = useCallback((): void => {
    if (hoverCloseTimer.current === undefined) return
    clearTimeout(hoverCloseTimer.current)
    hoverCloseTimer.current = undefined
  }, [])

  const cancelHoverOpen = useCallback((): void => {
    if (hoverOpenTimer.current === undefined) return
    clearTimeout(hoverOpenTimer.current)
    hoverOpenTimer.current = undefined
  }, [])

  const changeOpen = useCallback((next: boolean, restoreFocus = false): void => {
    cancelHoverOpen()
    cancelHoverClose()
    if (next) {
      if (triggerRef.current === null) return
      setOpen(true)
      return
    }
    setPinned(false)
    setOpen(false)
    // Focus goes back to the trigger only when the menu owned it, so a
    // pointer-dismissed menu does not steal focus from wherever the reader
    // clicked next (upstream's `restoreFocus` rule).
    if (restoreFocus) queueMicrotask(() => { triggerRef.current?.focus() })
  }, [cancelHoverOpen, cancelHoverClose])

  // CSS owns the header-width breakpoint; interaction reads its result only
  // when scheduling hover (upstream's `allowsHoverOpen`).
  const allowsHoverOpen = (): boolean => countRef.current !== null
    && getComputedStyle(countRef.current).display !== 'none'

  const scheduleHoverOpen = (): void => {
    cancelHoverOpen()
    cancelHoverClose()
    if (open || !allowsHoverOpen()) return
    hoverOpenTimer.current = setTimeout(() => {
      hoverOpenTimer.current = undefined
      if (allowsHoverOpen()) changeOpen(true)
    }, 150)
  }

  const scheduleHoverClose = (): void => {
    cancelHoverOpen()
    cancelHoverClose()
    if (pinned) return
    hoverCloseTimer.current = setTimeout(() => {
      hoverCloseTimer.current = undefined
      changeOpen(false)
    }, 120)
  }

  /**
   * Move focus between the menu's rows, wrapping at both ends (upstream's
   * `focusAt`). The catalog is a flat list, so an index into `entries` is the
   * whole of the position.
   */
  const focusAt = (index: number): void => {
    const rows = menuRef.current?.querySelectorAll<HTMLElement>('[role="treeitem"]')
    if (rows === undefined || rows.length === 0) return
    rows[(index + rows.length) % rows.length]?.focus()
  }

  /**
   * The list keys, bound on the trigger (upstream's `navigate`, bound on the
   * wrapper at `SubagentHeaderLineage.tsx:705`).
   *
   * Two upstream behaviours are worth stating rather than re-deriving. First,
   * the guard: a keystroke inside the menu that is not already on a row (in the
   * tree, or on the menu surface itself) is left alone. Second, and the reason
   * this is bound here and not on the menu — upstream portals the menu to the
   * body (`createPortal`), so it sits *outside* the wrapper and its key events
   * never bubble to this handler. `ArrowDown` on the trigger is therefore the
   * one list key upstream actually reaches, and it is the one that matters: it
   * opens the menu and lands on the first row, after which the rows are ordinary
   * tab stops. `Home`/`End`/`ArrowUp`/`ArrowDown` are kept for the case where
   * focus *is* already on a row, matching upstream key for key.
   */
  const navigate = (event: KeyboardEvent<HTMLDivElement>): void => {
    if (event.target instanceof Element
      && event.target !== triggerRef.current
      && event.target !== menuRef.current
      && event.target.closest('[role="tree"]') === null) return
    const rows = [...(menuRef.current?.querySelectorAll<HTMLElement>('[role="treeitem"]') ?? [])]
    const index = rows.indexOf(document.activeElement as HTMLElement)
    if (event.key === 'Home') {
      event.preventDefault()
      focusAt(0)
    } else if (event.key === 'End') {
      event.preventDefault()
      focusAt(rows.length - 1)
    } else if (event.key === 'ArrowDown') {
      event.preventDefault()
      focusAt(index + 1)
    } else if (event.key === 'ArrowUp') {
      event.preventDefault()
      focusAt(index < 0 ? rows.length - 1 : index - 1)
    }
  }

  useEffect(() => {
    if (!open) return
    const closeOutside = (event: PointerEvent): void => {
      // The menu is portalled to the body, so it is outside `rootRef` — both
      // containers have to be tested or a click on a row would dismiss the
      // menu before the row's own handler ran.
      if (
        event.target instanceof Node
        && !rootRef.current?.contains(event.target)
        && !menuRef.current?.contains(event.target)
      ) {
        changeOpen(false)
      }
    }
    document.addEventListener('pointerdown', closeOutside)
    const dismiss = (event: globalThis.KeyboardEvent): void => {
      if (event.key !== 'Escape') return
      event.preventDefault()
      changeOpen(false, menuRef.current?.contains(document.activeElement) === true)
    }
    document.addEventListener('keydown', dismiss)
    return () => {
      document.removeEventListener('pointerdown', closeOutside)
      document.removeEventListener('keydown', dismiss)
    }
  }, [open, changeOpen])

  useEffect(() => () => {
    cancelHoverOpen()
    cancelHoverClose()
  }, [cancelHoverOpen, cancelHoverClose])

  // Upstream hides the trigger on an empty, ready catalog; a failed read keeps
  // it so the retry stays reachable. A still-loading read shows the trigger,
  // because the count is not yet known (upstream's `visible` rule).
  const visible = agents === undefined || failed || directCount > 0 || runningCount > 0
  useEffect(() => {
    if (visible) return
    cancelHoverOpen()
    cancelHoverClose()
    if (open) changeOpen(false)
  }, [visible, open, changeOpen, cancelHoverOpen, cancelHoverClose])

  if (!visible) return null

  const menutItems = entries.map((entry) => (
    <CatalogRow
      key={entry.agentId}
      entry={entry}
      isCurrent={entry.agentId === currentAgentId}
      onOpen={(next) => {
        onOpen(next.agentId)
        changeOpen(false)
      }}
      t={t}
    />
  ))

  return (
    <div
      className={clsx(css.root, className)}
      ref={rootRef}
      data-subagent-action
      onKeyDown={navigate}
      onPointerLeave={scheduleHoverClose}
    >
      <Tooltip
        label={t(totalCountKey, { count: directCount })}
        disabled={open}
        delayMs={500}
        side="bottom"
      >
        <button
          ref={triggerRef}
          type="button"
          className={css.trigger}
          aria-haspopup="dialog"
          aria-expanded={open}
          aria-label={t(totalCountKey, { count: directCount })}
          aria-describedby={runningCount > 0 ? activityId : undefined}
          onPointerEnter={(event) => {
            if (event.pointerType !== 'touch') scheduleHoverOpen()
          }}
          onClick={() => {
            cancelHoverOpen()
            cancelHoverClose()
            // A click on an already-pinned menu is a dismissal, and focus
            // returns to the trigger because the keyboard was on it; a
            // hover-opened menu keeps the click as a pin (upstream's rules).
            if (open && (pinned || !allowsHoverOpen())) {
              changeOpen(false, true)
              return
            }
            setPinned(true)
            if (!open) changeOpen(true)
            // The reader may have arrived by pointer, but the menu is now
            // open and keyboard-reachable: hand it focus so the arrows work.
            queueMicrotask(() => { menuRef.current?.focus() })
          }}
          onKeyDown={(event) => {
            if (event.key !== 'ArrowDown') return
            event.preventDefault()
            // Upstream pins here when the trigger is in its compact
            // (`count`) variant; this trigger is always that variant, so the
            // pin is unconditional.
            setPinned(true)
            if (!open) changeOpen(true)
            queueMicrotask(() => { focusAt(0) })
          }}
        >
          <span className={css.activitySlot} aria-hidden>
            <IconUsersOutline size={14} />
            {runningCount > 0 && <StateDot state="ongoing" className={css.textModeActivity} />}
          </span>
          <span ref={countRef} className={css.count} data-subagent-count>
            {t(totalCountKey, { count: directCount })}
          </span>
        </button>
      </Tooltip>
      {runningCount > 0 && (
        <span id={activityId} hidden>{t(runningCountKey, { count: runningCount })}</span>
      )}
      {open && createPortal((
        <div
          ref={menuRef}
          className={css.menu}
          role="dialog"
          tabIndex={-1}
          aria-label={t('tree.aria')}
          data-subagent-panel
          onPointerEnter={(event) => {
            if (event.pointerType !== 'touch') cancelHoverClose()
          }}
        >
          <div className={css.menuBody}>
            <div className={css.sectionHeader} data-subagent-section-header>
              <span className={css.sectionIcon} aria-hidden>
                <IconUsersOutline size={14} />
              </span>
              <h3 className={css.sectionTitle}>{t('section.title')}</h3>
              {progress !== '' && <span className={css.sectionProgress}>{progress}</span>}
            </div>
            <div role="tree" aria-label={t('tree.aria')}>
              {agents === undefined && entries.length === 0 && (
                <div className={css.notice}>{t('loading.label')}</div>
              )}
              {failed && (
                <div className={css.error}>
                  <span>{t('load.error')}</span>
                  {onRetry !== undefined && (
                    <button type="button" className={css.refresh} onClick={onRetry}>
                      <IconRefreshOutline14 />
                      {t('retry')}
                    </button>
                  )}
                </div>
              )}
              {entries.length === 0 && !failed && agents !== undefined && (
                <p className={css.sectionEmpty}>{t('section.empty')}</p>
              )}
              {menutItems}
            </div>
          </div>
        </div>
      ), document.body)}
    </div>
  )
}
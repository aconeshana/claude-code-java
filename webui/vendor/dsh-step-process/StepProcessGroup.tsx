/**
 * Vendored and adapted from deepseek-ai/deepseek-harness
 * `packages/client/ui-chat/src/client/chat/ChatGroupSeat.tsx`.
 *
 * Stable step-process container: groups consecutive thinking and tool-call rows
 * into one single-line disclosure header (live shimmer + task detail while
 * running, ranked category summary once settled) and a capped scrolling body
 * (`max-height: min(400px, 50vh)`) with top/bottom edge fades when expanded.
 *
 * Cut from upstream: cordis store hooks (`useChatGroup`, `useChatNode`,
 * `useGroupAction`, `useGroupHeaderAction`) — state arrives as plain props from
 * `ChatView.tsx`. Collapsed bodies stay mounted via `hidden="until-found"` with
 * a `beforematch` auto-reveal listener so browser Ctrl+F still finds text inside.
 */
import { memo, useCallback, useEffect, useId, useRef, useState, type ReactNode } from 'react'
import {
  IconAgentPresetOutline16,
  IconApiOutline14,
  IconBrowseOutline16,
  IconChecklistOutline14,
  IconChevronDownOutline14,
  IconChevronUpOutline14,
  IconCodeOutline16,
  IconContextInjectionOutline16,
  IconEditOutline16,
  IconEnhanceOutline16,
  IconGlobeOutline14,
  IconSearchOutline16,
  IconThinkOutline14,
  IconWarningOutline16,
  TextShimmer,
} from '@primitives'
import css from './ChatGroupSeat.module.css'
import {
  processTitle,
  type ProcessActivity,
  type ProcessActivitySummary,
  type RunningProcessActivity,
  type StepProcessTranslate,
} from './stepProcess'
import { useProcessScroll } from './useProcessScroll'

const PROCESS_TITLE_MINIMUM_MS = 150

type ProcessTitleActivity = ProcessActivity | 'thinking'

interface LiveProcessTitle {
  readonly activity: RunningProcessActivity | 'thinking'
  readonly detail: string
}

const PROCESS_ICONS: Record<ProcessTitleActivity, ReactNode> = {
  thinking: <IconThinkOutline14 />,
  read: <IconBrowseOutline16 size={14} />,
  readImage: <IconBrowseOutline16 size={14} />,
  search: <IconSearchOutline16 size={14} />,
  edit: <IconEditOutline16 size={14} />,
  write: <IconEditOutline16 size={14} />,
  commands: <IconApiOutline14 />,
  code: <IconCodeOutline16 size={14} />,
  webSearch: <IconGlobeOutline14 />,
  webFetch: <IconBrowseOutline16 size={14} />,
  subagents: <IconAgentPresetOutline16 size={14} />,
  plan: <IconChecklistOutline14 />,
  questions: <IconWarningOutline16 />,
  tools: <IconEnhanceOutline16 size={14} />,
  context: <IconContextInjectionOutline16 size={14} />,
}

function sameLiveProcessTitle(left: LiveProcessTitle, right: LiveProcessTitle): boolean {
  return left.activity === right.activity && left.detail === right.detail
}

function useStableLiveProcessTitle(desired: LiveProcessTitle, active: boolean): LiveProcessTitle {
  const [displayed, setDisplayed] = useState(desired)
  const displayedRef = useRef(displayed)
  const desiredRef = useRef(desired)
  const displayedAtRef = useRef(Date.now())
  useEffect(() => {
    desiredRef.current = desired
    if (!active || sameLiveProcessTitle(displayedRef.current, desired)) return
    const remaining = PROCESS_TITLE_MINIMUM_MS - (Date.now() - displayedAtRef.current)
    const commit = (): void => {
      const next = desiredRef.current
      displayedRef.current = next
      displayedAtRef.current = Date.now()
      setDisplayed(next)
    }
    if (remaining <= 0) {
      commit()
      return
    }
    const timer = setTimeout(commit, remaining)
    return () => { clearTimeout(timer) }
  }, [active, desired.activity, desired.detail])
  return active ? displayed : desired
}

export interface StepProcessGroupProps {
  readonly groupKey: string
  readonly turn?: number | undefined
  /** Whether the group has finished running (no pending tool calls / open step). */
  readonly closed: boolean
  /** True in compact mode (collapsed with capped scroll); false in detailed/normal mode (flat). */
  readonly grouped: boolean
  /** Whether to show the live human-readable task detail beside the running label. */
  readonly liveProcessDetail: boolean
  readonly summary: ProcessActivitySummary
  readonly t: StepProcessTranslate
  /** Outer turn-process fold hidden state (when the whole turn is folded behind TurnProcessControl). */
  readonly outerHidden?: boolean | undefined
  readonly outerTurn?: number | undefined
  readonly children: ReactNode
}

export const StepProcessGroup = memo(function StepProcessGroup({
  groupKey,
  turn,
  closed,
  grouped,
  liveProcessDetail,
  summary,
  t,
  outerHidden = false,
  outerTurn,
  children,
}: StepProcessGroupProps) {
  const [open, setOpen] = useState(false)
  const bodyRef = useRef<HTMLDivElement>(null)
  const contentRef = useRef<HTMLDivElement>(null)
  const bodyId = useId()

  // Reset local disclosure when outer turn-process collapses this group.
  useEffect(() => {
    if (outerHidden) setOpen(false)
  }, [outerHidden])

  // Browser Ctrl+F inside a collapsed group reveals the body automatically.
  useEffect(() => {
    const body = bodyRef.current
    if (body === null) return
    const onBeforeMatch = (): void => { setOpen(true) }
    body.addEventListener('beforematch', onBeforeMatch)
    return () => { body.removeEventListener('beforematch', onBeforeMatch) }
  }, [])

  const { edges, events, initialize } = useProcessScroll(bodyRef, contentRef, open, grouped)
  const toggle = useCallback(() => {
    if (!open) initialize(closed ? 'top' : 'bottom')
    setOpen(prev => !prev)
  }, [closed, initialize, open])

  const live = useStableLiveProcessTitle({
    activity: summary.running ?? 'thinking',
    detail: summary.runningDetail,
  }, !closed)

  const label = closed
    ? processTitle(summary, t)
    : t(`message.stepProcess.${live.activity}`)
  const detail = liveProcessDetail
    ? (!closed ? live.detail : (summary.totalCalls === 1 ? summary.lastDetail : ''))
    : ''
  const countSuffix = closed && summary.totalCalls > 1 ? ` (${summary.totalCalls})` : ''
  const title = detail === ''
    ? `${label}${countSuffix}`
    : `${label}${t('message.turnProcess.separator')}${detail}`
  const activity: ProcessTitleActivity = closed
    ? summary.counts[0]?.kind ?? 'thinking'
    : live.activity

  const collapsed = grouped && !open
  const bodyClasses = [
    css.body,
    !grouped ? css.expandedBody : '',
    grouped && edges.canScrollUp ? css.fadeTop : '',
    grouped && edges.canScrollDown ? css.fadeBottom : '',
  ].filter(Boolean).join(' ')

  return (
    <div
      className={css.root}
      data-chat-group-key={groupKey}
      data-chat-flow-key={groupKey}
      data-chat-turn={turn}
      data-turn-process-member={outerTurn !== undefined ? String(outerTurn) : undefined}
      data-step-process=""
      data-group-expanded-mode={!grouped || undefined}
      hidden={outerHidden ? ('until-found' as unknown as boolean) : undefined}
    >
      {grouped && (
        <button
          type="button"
          className={css.title}
          aria-expanded={open}
          aria-controls={bodyId}
          data-process-activity={activity}
          onClick={(event) => {
            event.currentTarget.focus()
            toggle()
          }}
        >
          <span className={css.leading} aria-hidden="true">
            <span className={css.activityIcon} data-step-process-icon="">{PROCESS_ICONS[activity]}</span>
            <span className={css.chevron} data-step-process-chevron="">
              {open ? <IconChevronUpOutline14 /> : <IconChevronDownOutline14 />}
            </span>
          </span>
          <TextShimmer active={!closed}>
            <TextShimmer className={css.label}>{title}</TextShimmer>
          </TextShimmer>
        </button>
      )}
      <div
        ref={bodyRef}
        id={bodyId}
        className={bodyClasses}
        data-step-process-body=""
        data-scroll-up={edges.canScrollUp || undefined}
        data-scroll-down={edges.canScrollDown || undefined}
        hidden={collapsed ? ('until-found' as unknown as boolean) : undefined}
        {...events}
      >
        <div ref={contentRef} className={css.content} data-step-process-content="">
          {children}
        </div>
      </div>
    </div>
  )
})

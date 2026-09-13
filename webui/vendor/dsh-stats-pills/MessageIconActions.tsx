// Adapted from ui-chat/src/client/chat/MessageIconActions.tsx (pinned dsh
// commit). Shared IconActions chrome for user and assistant messages: copy
// live and an optional date-aware clock. The branch action stays cut — this
// app has no fork-the-conversation feature, so the row is copy + slot pills
// + clock. The usageAction seat (the TurnUsagePanel/TurnTimePanel pills)
// stays seated after the copy control, exactly where upstream places it
// between the branch control and the trailing clock.

import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { IconCheckOutline16, IconCopyOutline16, Tooltip, writeClipboard } from '@primitives'
import { formatMessageClock } from './message-chrome.ts'
import { useCalendarDay } from './use-calendar-day.ts'
import css from '@chat-styles/MessageIconActions.module.css'

/** The locale share the action rows consume. */
export type ActionsTranslate = {
  t: (key: 'copy' | 'copied', params?: Record<string, string | number>) => string
} & {
  t: (key: 'clock.md' | 'clock.ymd', params: Record<string, string | number>) => string
}

export interface MessageIconActionsProps {
  /** Plain text the copy action writes. */
  text: string
  /** Unix epoch ms for the clock label; omitted for transient messages. */
  time?: number | undefined
  /** Clock before icons (user) or after (assistant). */
  clock: 'start' | 'end'
  /** Parent layout class composed onto the actions row. */
  className?: string | undefined
  /**
   * Icon-row Turn-usage trigger (the TurnUsagePanel/TurnTimePanel pills),
   * seated after the copy control at the end of the icon cluster.
   */
  usageAction?: ReactNode
  /** The owning view's locale seat, passed down as a plain prop. */
  t: ActionsTranslate['t']
}

/**
 * Copy (/ clock) IconActions row shared by user and assistant chrome.
 * @param props - Copy text, event time, clock side, className.
 * @returns The actions row element.
 */
export function MessageIconActions({
  text, time, clock, className, usageAction, t,
}: MessageIconActionsProps) {
  const day = useCalendarDay()
  // Same success chrome as CodeBlock: a short check swap after the write,
  // gated so re-clicks during the window neither re-copy nor stack timers.
  const [copied, setCopied] = useState(false)
  const copyPending = useRef(false)
  const copyTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const copyEpoch = useRef(0)
  useEffect(() => () => {
    copyEpoch.current += 1
    copyPending.current = false
    if (copyTimer.current !== null) clearTimeout(copyTimer.current)
  }, [])
  const onCopy = useCallback(() => {
    if (copied || copyPending.current) return
    const epoch = copyEpoch.current
    copyPending.current = true
    void writeClipboard(text).then((ok) => {
      if (epoch !== copyEpoch.current) return
      copyPending.current = false
      if (!ok) return
      setCopied(true)
      copyTimer.current = window.setTimeout(() => {
        copyTimer.current = null
        setCopied(false)
      }, 1000)
    })
  }, [copied, text])
  const clockEl = time === undefined ? null : (
    <span className={clock === 'start' ? css.timeStart : css.timeEnd}>
      {formatMessageClock(time, t, day)}
    </span>
  )
  return (
    <div className={className === undefined ? css.actions : `${css.actions} ${className}`}>
      {clock === 'start' ? clockEl : null}
      <Tooltip label={copied ? t('copied') : t('copy')} side="bottom">
        <button type="button" className={css.action} aria-label={copied ? t('copied') : t('copy')} onClick={onCopy}>
          {copied ? <IconCheckOutline16 /> : <IconCopyOutline16 />}
        </button>
      </Tooltip>
      {usageAction}
      {clock === 'end' ? clockEl : null}
    </div>
  )
}

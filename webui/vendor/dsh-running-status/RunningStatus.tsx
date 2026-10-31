/** Running Turn clock isolated from the transcript's render cycle. */
import { memo, useEffect, useState } from 'react'
import { TextShimmer } from '@primitives'
import { formatRunDuration, LIVE_RUN_CLOCK_INTERVAL_MS } from './run-duration.ts'
import a11yCss from './accessibility.module.css'
import css from '@chat-styles/ChatView.module.css'

/** The chat-namespace translate seat (`useTranslate(STATS_NS, statsDicts)`). */
export type RunningStatusTranslate = (key: string, params?: Readonly<Record<string, string | number>>) => string

interface RunningStatusProps {
  readonly startTime: number | undefined
  /** Freeze the shown duration while the Client cannot observe the Session. */
  readonly paused: boolean
  /** Keep the slot without showing or announcing the status: the Session stopped before its Turn closed. */
  readonly holding?: boolean
  /** The TUI spinner's current verb ("Brewing"); absent until the vocabulary loads. */
  readonly verb?: string | undefined
  readonly t: RunningStatusTranslate
}

/**
 * Show live elapsed time after the current Turn's content without announcing ticks.
 * @param props - Current Turn start time, pause state, spinner verb, and localized copy.
 * @returns the blue running indicator; mount while the Session is running, or holding its slot hidden until the
 * Turn's actions row replaces it.
 */
export const RunningStatus = memo(function RunningStatus({ startTime, paused, holding = false, verb, t }: RunningStatusProps) {
  const [now, setNow] = useState(Date.now)
  useEffect(() => {
    if (startTime === undefined || paused || holding) return
    setNow(Date.now())
    const timer = setInterval(() => { setNow(Date.now()) }, LIVE_RUN_CLOCK_INTERVAL_MS)
    return () => { clearInterval(timer) }
  }, [startTime, paused, holding])
  const shownVerb = verb ?? t('chat.running')
  const label = startTime === undefined ? t('chat.runningVerb', { verb: shownVerb }) : t('chat.runningVerbFor', {
    verb: shownVerb,
    duration: formatRunDuration(Math.max(1000, now - startTime), t).map(part => part.text).join(''),
  })
  return (
    <div className={css.running} data-chat-running data-holding={holding ? '' : undefined} aria-hidden={holding || undefined}>
      {holding ? null : (
        <span className={a11yCss.visuallyHidden} role="status" aria-live="polite" aria-atomic="true">{t('chat.running')}</span>
      )}
      <span className={css.runningDivider} aria-hidden="true" />
      <span className={css.runningContent}>
        <TextShimmer active={!holding} className={css.runningText}>{label}</TextShimmer>
      </span>
    </div>
  )
})

// Trimmed from ui-chat/src/client/chat/message-chrome.ts to the live run
// clock the running status consumes. The dsh-stats-pills copy keeps the older
// whole-string formatter the settled turn-time pill renders.

/** The translate seat this module consumes: units only, no params. */
export type RunDurationTranslate = (key: 'duration.secondUnit' | 'duration.minuteUnit' | 'duration.hourUnit') => string

/** Refresh interval for whole-second live run clocks. */
export const LIVE_RUN_CLOCK_INTERVAL_MS = 1000

/** One numeric value or localized unit in an elapsed-time label. */
export interface RunDurationPart {
  readonly text: string
  readonly numeric: boolean
}

/**
 * Build elapsed-time fragments for both live and completed Turn labels.
 * @param ms - elapsed milliseconds; negatives clamp to zero and fractions floor.
 * @param t - translate seat supplying units and their trailing spacing.
 * @returns numbers and localized units in display order, without leading zeros;
 * minutes start at 60 seconds and hours at 60 minutes.
 */
export function formatRunDuration(ms: number, t: RunDurationTranslate): RunDurationPart[] {
  const total = Math.max(0, Math.floor(ms / 1000))
  const hours = Math.floor(total / 3600)
  const minutes = Math.floor(total / 60) % 60
  const seconds = total % 60
  const parts: RunDurationPart[] = []
  if (hours > 0) parts.push({ text: String(hours), numeric: true }, { text: t('duration.hourUnit'), numeric: false })
  if (total >= 60) parts.push({ text: String(minutes), numeric: true }, { text: t('duration.minuteUnit'), numeric: false })
  parts.push({ text: String(seconds), numeric: true }, { text: t('duration.secondUnit'), numeric: false })
  return parts
}

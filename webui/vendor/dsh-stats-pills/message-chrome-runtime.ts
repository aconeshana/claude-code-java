// Trimmed from ui-chat/src/client/chat/message-chrome.ts to the one
// duration formatter the turn-time pill consumes; the clock/calendar-day and
// latency helpers serve upstream chrome this app renders differently.

import type { StatsTranslate } from './statsPillsModel.ts'

/**
 * Localized elapsed-time label shared by running and settled turn chrome.
 * @param ms - Elapsed duration in milliseconds (negatives clamp to zero).
 * @param t - Locale seat supplying the duration templates.
 * @returns Display string in whole seconds.
 */
export function formatRunDuration(ms: number, t: StatsTranslate): string {
  const total = Math.max(0, Math.floor(ms / 1000))
  const minutes = Math.floor(total / 60)
  const seconds = total % 60
  return minutes > 0
    ? t('duration.minutes', { minutes, seconds: String(seconds).padStart(2, '0') })
    : t('duration.seconds', { seconds })
}

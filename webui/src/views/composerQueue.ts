import type { UserContentBlock } from '../api/types'

/**
 * The composer's busy-queue persistence: a submission held while a turn runs
 * belongs to the session it was queued under, and only that session may ever
 * flush it. Without the session check a queued row leaks across a sidebar
 * switch — the busy→idle edge of the *next* selected session would submit the
 * previous session's parked text.
 *
 * The queue rides localStorage (not component state) so a queued-but-unsent
 * submission also survives a reload mid-turn; a flush or a session switch
 * clears it. Nothing here ever touches the gateway token's sessionStorage.
 */

export const QUEUE_STORAGE_KEY = 'webui-composer-queue'

/** The storage row one session's queued submission lives under. */
export function QUEUED_SESSION_KEY(sessionId: string): string {
  return `${QUEUE_STORAGE_KEY}:${sessionId}`
}

type QueuedContent = string | readonly UserContentBlock[]

/** Parks one submission under its session; a re-queue replaces the previous. */
export function storeQueuedSubmission(sessionId: string, content: QueuedContent): void {
  try {
    localStorage.setItem(QUEUED_SESSION_KEY(sessionId), JSON.stringify({ content }))
  } catch { /* storage unavailable: the in-memory copy still serves this tab */ }
}

/**
 * The session's parked submission, or null when this session queued nothing.
 * A malformed or absent row reads as empty — never as a throw.
 */
export function readQueuedSubmission(sessionId: string): QueuedContent | null {
  let raw: string | null
  try {
    raw = localStorage.getItem(QUEUED_SESSION_KEY(sessionId))
  } catch {
    return null
  }
  if (raw == null) return null
  try {
    const parsed = JSON.parse(raw) as { content?: unknown }
    if (typeof parsed.content !== 'string' && !Array.isArray(parsed.content)) return null
    return parsed.content as QueuedContent
  } catch {
    return null
  }
}

/** Drops the session's parked submission (after a flush or a session switch). */
export function clearQueuedSubmission(sessionId: string): void {
  try {
    localStorage.removeItem(QUEUED_SESSION_KEY(sessionId))
  } catch { /* already absent for this tab */ }
}

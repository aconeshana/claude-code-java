import type { UserContentBlock } from '../api/types'

/**
 * The composer's busy-queue persistence: submissions held while a turn runs
 * belong to the session they were queued under, and only that session may ever
 * flush them. Supports multiple queued messages per session (mirroring upstream
 * DSH's composer-docked queue card) while remaining backward-compatible with
 * single-entry legacy payloads.
 */

export const QUEUE_STORAGE_KEY = 'webui-composer-queue'

/** The storage row one session's queued submissions live under. */
export function QUEUED_SESSION_KEY(sessionId: string): string {
  return `${QUEUE_STORAGE_KEY}:${sessionId}`
}

export type QueuedContent = string | readonly UserContentBlock[]

export interface QueuedItem {
  readonly id: string
  readonly content: QueuedContent
}

function isValidContent(value: unknown): value is QueuedContent {
  return typeof value === 'string' || Array.isArray(value)
}

/** Reads all queued items for {@code sessionId}. Malformed payloads return empty. */
export function readQueuedItems(sessionId: string): readonly QueuedItem[] {
  let raw: string | null
  try {
    raw = localStorage.getItem(QUEUED_SESSION_KEY(sessionId))
  } catch {
    return []
  }
  if (raw == null) return []
  try {
    const parsed = JSON.parse(raw) as { items?: unknown; content?: unknown }
    if (Array.isArray(parsed.items)) {
      const valid: QueuedItem[] = []
      for (const entry of parsed.items) {
        if (entry != null && typeof entry === 'object') {
          const item = entry as { id?: unknown; content?: unknown }
          if (typeof item.id === 'string' && isValidContent(item.content)) {
            valid.push({ id: item.id, content: item.content })
          }
        }
      }
      return valid
    }
    if (isValidContent(parsed.content)) {
      return [{ id: 'legacy-0', content: parsed.content }]
    }
    return []
  } catch {
    return []
  }
}

/** Persists the ordered list of queued items for {@code sessionId}. */
export function storeQueuedItems(sessionId: string, items: readonly QueuedItem[]): void {
  try {
    if (items.length === 0) {
      localStorage.removeItem(QUEUED_SESSION_KEY(sessionId))
    } else {
      localStorage.setItem(QUEUED_SESSION_KEY(sessionId), JSON.stringify({ items }))
    }
  } catch { /* storage unavailable: the in-memory copy still serves this tab */ }
}

/** Single-line human preview for a queued item's content. */
export function extractQueuedPreview(content: QueuedContent): string {
  if (typeof content === 'string') return content.replace(/\s+/g, ' ').trim()
  const parts: string[] = []
  for (const block of content) {
    if (block.type === 'text' && block.text.trim() !== '') {
      parts.push(block.text.replace(/\s+/g, ' ').trim())
    } else if (block.type === 'image') {
      parts.push('[图片]')
    } else if (block.type === 'document') {
      parts.push(`[文件: ${block.title ?? '附件'}]`)
    }
  }
  return parts.join(' ').trim() || '[附件]'
}

/** Merges multiple queued items (and optional trailing draft) into one submission payload. */
export function mergeQueuedContents(contents: readonly QueuedContent[]): QueuedContent {
  if (contents.length === 1) return contents[0]
  if (contents.every((c): c is string => typeof c === 'string')) {
    return contents.join('\n\n')
  }
  const merged: UserContentBlock[] = []
  contents.forEach((entry, index) => {
    if (index > 0) merged.push({ type: 'text', text: '\n\n' })
    if (typeof entry === 'string') {
      if (entry !== '') merged.push({ type: 'text', text: entry })
    } else {
      merged.push(...entry)
    }
  })
  return merged
}

/** Legacy single-item helper: parks one submission under its session. */
export function storeQueuedSubmission(sessionId: string, content: QueuedContent): void {
  storeQueuedItems(sessionId, [{ id: `q-${Date.now()}`, content }])
}

/** Legacy single-item helper: reads the first parked submission, or null. */
export function readQueuedSubmission(sessionId: string): QueuedContent | null {
  const items = readQueuedItems(sessionId)
  return items.length > 0 ? items[0].content : null
}

/** Drops all parked submissions for {@code sessionId}. */
export function clearQueuedSubmission(sessionId: string): void {
  storeQueuedItems(sessionId, [])
}

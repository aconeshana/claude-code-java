/**
 * Completed-turn transcript density preference (mirrors dsh ui-chat
 * chat-settings.ts's TranscriptViewMode).
 *
 * dsh's real semantic derives from its own ChatNode/turn-process anchor
 * model (turn-process-presentation.ts), which our message schema has no
 * equivalent of. This store keeps only the portable part of the concept: a
 * persisted normal/compact preference that controls the initial open/closed
 * state of thinking blocks and tool-call rows on newly rendered messages.
 */
import { create } from 'zustand'

export const TRANSCRIPT_VIEW_MODES = ['normal', 'compact'] as const

export type TranscriptViewMode = (typeof TRANSCRIPT_VIEW_MODES)[number]

/**
 * Upstream `ui-chat.foldedReplies`: which assistant replies stay visible when
 * a turn's process folds. Defaults to `'all'` (keep intermediate replies
 * visible, fold only process groups).
 */
export const FOLDED_REPLIES_MODES = ['all', 'final'] as const

export type FoldedRepliesMode = (typeof FOLDED_REPLIES_MODES)[number]

const STORAGE_KEY = 'webui-transcript-view'
const DEFAULT_MODE: TranscriptViewMode = 'compact'
const FOLDED_REPLIES_STORAGE_KEY = 'webui-folded-replies'
const DEFAULT_FOLDED_REPLIES: FoldedRepliesMode = 'all'

function readMode(): TranscriptViewMode {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return (TRANSCRIPT_VIEW_MODES as readonly string[]).includes(raw ?? '')
      ? (raw as TranscriptViewMode)
      : DEFAULT_MODE
  } catch {
    return DEFAULT_MODE
  }
}

function readFoldedReplies(): FoldedRepliesMode {
  try {
    const raw = localStorage.getItem(FOLDED_REPLIES_STORAGE_KEY)
    return (FOLDED_REPLIES_MODES as readonly string[]).includes(raw ?? '')
      ? (raw as FoldedRepliesMode)
      : DEFAULT_FOLDED_REPLIES
  } catch {
    return DEFAULT_FOLDED_REPLIES
  }
}

export interface TranscriptViewStore {
  readonly mode: TranscriptViewMode
  readonly foldedReplies: FoldedRepliesMode
  /** Monotonic revision incremented on every mode change so mounted rows re-fold dynamically. */
  readonly revision: number
  setMode(next: TranscriptViewMode): void
  setFoldedReplies(next: FoldedRepliesMode): void
}

export const useTranscriptView = create<TranscriptViewStore>(set => ({
  mode: readMode(),
  foldedReplies: readFoldedReplies(),
  revision: 0,

  setMode(next) {
    try {
      localStorage.setItem(STORAGE_KEY, next)
    } catch { /* storage unavailable (private mode): preference stays session-only */ }
    set((state) => ({ mode: next, revision: state.revision + 1 }))
  },

  setFoldedReplies(next) {
    try {
      localStorage.setItem(FOLDED_REPLIES_STORAGE_KEY, next)
    } catch { /* storage unavailable (private mode): preference stays session-only */ }
    set((state) => ({ foldedReplies: next, revision: state.revision + 1 }))
  },
}))

window.addEventListener('storage', (event) => {
  if (event.key === STORAGE_KEY) {
    useTranscriptView.setState((state) => ({ mode: readMode(), revision: state.revision + 1 }))
  } else if (event.key === FOLDED_REPLIES_STORAGE_KEY) {
    useTranscriptView.setState((state) => ({
      foldedReplies: readFoldedReplies(),
      revision: state.revision + 1,
    }))
  }
})

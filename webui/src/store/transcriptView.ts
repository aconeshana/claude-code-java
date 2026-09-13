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

const STORAGE_KEY = 'webui-transcript-view'
const DEFAULT_MODE: TranscriptViewMode = 'compact'

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

export interface TranscriptViewStore {
  readonly mode: TranscriptViewMode
  setMode(next: TranscriptViewMode): void
}

export const useTranscriptView = create<TranscriptViewStore>(set => ({
  mode: readMode(),

  setMode(next) {
    try {
      localStorage.setItem(STORAGE_KEY, next)
    } catch { /* storage unavailable (private mode): preference stays session-only */ }
    set({ mode: next })
  },
}))

window.addEventListener('storage', (event) => {
  if (event.key === STORAGE_KEY) useTranscriptView.setState({ mode: readMode() })
})

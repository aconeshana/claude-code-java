/**
 * Which workspace file the right column previews, or null while none is open.
 *
 * Upstream opens a `text` tab in the sidebar by pushing a document address onto
 * the dock; this app's right column hosts one occupant at a time, so the
 * preview is a seat like the review's and needs no address model.
 */
import { create } from 'zustand'

export interface OpenPreview {
  readonly sessionId: string
  readonly path: string
}

interface FilePreviewSeatState {
  readonly preview: OpenPreview | null
  readonly openPreview: (sessionId: string, path: string) => void
  readonly closePreview: () => void
}

export const useFilePreviewSeat = create<FilePreviewSeatState>()((set) => ({
  preview: null,

  openPreview(sessionId, path) {
    set({ preview: { sessionId, path } })
  },

  closePreview() {
    set({ preview: null })
  },
}))

/**
 * Which workspace file the right column previews, or null while none is open.
 *
 * Upstream opens a `text` tab in the sidebar by pushing a document address onto
 * the dock; this app's right column hosts one occupant at a time, so the
 * preview is a seat like the review's and needs no address model.
 */
import { create } from 'zustand'
import { closeContentInRightDock, openFileInRightDock } from './rightSidebar'

export interface OpenPreview {
  readonly sessionId: string
  readonly path: string
}

interface FilePreviewSeatState {
  readonly preview: OpenPreview | null
  readonly openPreview: (sessionId: string, path: string) => void
  readonly closePreview: () => void
}

export const useFilePreviewSeat = create<FilePreviewSeatState>()((set, get) => ({
  preview: null,

  openPreview(sessionId, path) {
    set({ preview: { sessionId, path } })
    openFileInRightDock(sessionId, path)
  },

  closePreview() {
    const current = get().preview
    set({ preview: null })
    if (current !== null) {
      closeContentInRightDock(current.sessionId, `file:${current.sessionId}:${current.path}`)
    }
  },
}))

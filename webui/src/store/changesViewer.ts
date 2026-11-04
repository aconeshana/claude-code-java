import { create } from 'zustand'
import { fetchWorkspaceFileDiff, openFileInHost } from '../api/client'
import type { WorkspaceFileDiff } from '../api/types'

/**
 * The changed-files viewer's state — which file's diff is open, and the
 * fetch status of that diff.
 *
 * Deviation from upstream: upstream opens a file as a tab in the right
 * Sidebar's resource registry (`dsh-resource://file/...`, routed to the
 * `textpreview` tab type). This app has no sidebar tab registry and no
 * resource system, so the viewer is a single modal and the selection is one
 * path per session. That is a structural difference in the host, not in the
 * viewer's own behaviour: one file at a time, a reload affordance, and a
 * failure that stays visible.
 */

/** The diff fetch's status. `unavailable` is the browser host's own answer. */
export type DiffStatus = 'idle' | 'loading' | 'ready' | 'failed' | 'unavailable'

interface ChangesViewerState {
  /** The session the open diff belongs to, or null when the viewer is closed. */
  readonly sessionId: string | null
  /** The open file's absolute path, or null when the viewer is closed. */
  readonly path: string | null
  readonly status: DiffStatus
  readonly diff: WorkspaceFileDiff | null
  /** A human-readable reason for `failed` / `unavailable`. */
  readonly message: string | null
  readonly open: (sessionId: string, path: string) => void
  readonly close: () => void
  readonly reload: () => void
  /** Opens a file through the host. Records the outcome so the control can
   *  render its own unavailable state rather than a silent no-op. */
  readonly openInHost: (sessionId: string, path: string) => Promise<void>
  readonly hostMessage: string | null
  readonly hostPending: boolean
}

export const useChangesViewer = create<ChangesViewerState>((set, get) => ({
  sessionId: null,
  path: null,
  status: 'idle',
  diff: null,
  message: null,
  hostMessage: null,
  hostPending: false,

  open(sessionId, path) {
    set({ sessionId, path, status: 'loading', diff: null, message: null })
    void load(set, get, sessionId, path)
  },

  close() {
    set({ sessionId: null, path: null, status: 'idle', diff: null, message: null })
  },

  reload() {
    const { sessionId, path } = get()
    if (sessionId === null || path === null) return
    set({ status: 'loading', message: null })
    void load(set, get, sessionId, path)
  },

  async openInHost(sessionId, path) {
    set({ hostPending: true, hostMessage: null })
    try {
      await openFileInHost(sessionId, path)
      set({ hostPending: false, hostMessage: null })
    } catch (error) {
      // The failure stays visible until the next attempt (upstream's
      // semantics): the control is disabled and explains itself rather than
      // looking like a successful no-op.
      set({
        hostPending: false,
        hostMessage: error instanceof Error ? error.message : 'unavailable',
      })
    }
  },
}))

async function load(
  set: (partial: Partial<ChangesViewerState>) => void,
  get: () => ChangesViewerState,
  sessionId: string,
  path: string,
): Promise<void> {
  try {
    const diff = await fetchWorkspaceFileDiff(sessionId, path)
    // A response for a file the user has since closed or replaced writes
    // nothing: the viewer keeps one selection, so a late answer must not
    // overwrite the newer one under it.
    if (get().path !== path || get().sessionId !== sessionId) return
    set({ status: 'ready', diff, message: null })
  } catch (error) {
    if (get().path !== path || get().sessionId !== sessionId) return
    set({
      status: 'failed',
      diff: null,
      message: error instanceof Error ? error.message : 'failed',
    })
  }
}
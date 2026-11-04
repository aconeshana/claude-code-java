/**
 * Per-session to-do board state, over the gateway's `/api/session/tasks`
 * endpoint and the `task_board` mirror frames — the webui dock's data source,
 * the remote counterpart of the TUI's task panel.
 *
 * The board the model writes through the `Task*` tools reaches us on two
 * transports: a pushed frame carries each new revision, and the endpoint
 * answers with the standing state a client connecting mid-session has not
 * seen. Both carry the same shape, so the store folds them the same way and
 * never branches on which one arrived.
 *
 * Addressing mirrors ../store/permissionMode.ts: a null session follows the
 * active TUI session, a bound store takes only its own frames, and a slow
 * answer for a session we have since left is discarded.
 */
import { create } from 'zustand'
import { fetchSessionTasks } from '../api/client'
import type { MirrorFrame, SessionTaskBoard } from '../api/types'

export interface TaskBoardStore {
  readonly board: SessionTaskBoard | null
  /** The session the current board belongs to, null for the active TUI session. */
  readonly sessionId: string | null
  /** Cold-loads the addressed session's standing board. */
  load(sessionId?: string | null): Promise<void>
  /** Clears the board, e.g. when the dock is torn down on a session switch. */
  reset(): void
  /**
   * Applies one `task_board` mirror frame. Ignored for a session this store is
   * not bound to; a whole snapshot is folded over, never merged item-wise,
   * because a revision is the complete list.
   */
  applyFrame(frame: MirrorFrame): void
  /**
   * Applies a board fetched by the caller (the same shape the endpoint and the
   * frames carry), so an already-fetched snapshot need not be re-requested.
   */
  applyBoard(board: SessionTaskBoard): void
}

export const useTaskBoard = create<TaskBoardStore>((set, get) => ({
  board: null,
  sessionId: null,

  async load(sessionId) {
    const target = sessionId ?? null
    if (get().sessionId !== target) set({ sessionId: target, board: null })
    try {
      const answer = await fetchSessionTasks(target)
      if (get().sessionId !== target) return
      set({ board: answer })
    } catch {
      if (get().sessionId !== target) return
      set({ board: null })
    }
  },

  reset() {
    set({ board: null })
  },

  applyFrame(frame) {
    if (frame.event !== 'task_board') return
    const bound = get().sessionId
    if (bound != null && bound !== frame.data.session_id) return
    set({ board: frame.data })
  },

  applyBoard(board) {
    set({ board })
  },
}))
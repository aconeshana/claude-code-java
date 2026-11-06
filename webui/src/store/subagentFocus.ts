/**
 * The relay between the chat header's subagent catalog and the transcript's
 * `SubagentRow`: the catalog lists every subagent the session launched, but
 * only the ones whose launching call is currently rendered have a row to
 * expand, and the row owns its own expansion state.
 *
 * A request is a one-shot, session-scoped address: the catalog publishes
 * `open(sessionId, agentId)`; the addressed row consumes it on mount (or on
 * arrival, when it is already mounted) and expands itself with its transcript
 * open. A request for an agent whose row is not rendered — scrolled out of a
 * folded turn, or from a turn the transcript has dropped — simply expires, and
 * the catalog's list has already told the reader everything it knows.
 *
 * Holding the request rather than the row's state is what keeps this out of
 * the transcript reducer: the catalog has no business reaching into message
 * state to flip a boolean three components down.
 */
import { create } from 'zustand'

export interface SubagentFocus {
  readonly agentId: string
}

export interface SubagentFocusStore {
  /** The newest unclaimed request, or null. */
  readonly pending: SubagentFocus | null
  /**
   * The subagent the reader last picked out of the catalog, kept after the
   * request is claimed. This is what the catalog marks with its check, and it
   * must outlive the request: claiming is what makes the mark correct, so
   * deriving the mark from `pending` would erase it the moment the row opens.
   */
  readonly current: string | null
  /** Publish a request to open one subagent's row. */
  open(sessionId: string, agentId: string): void
  /**
   * Claim the pending request for this row, if it names this agent. The claim
   * is destructive — one request expands one row — so a duplicate mount of the
   * same row cannot double-consume it.
   */
  claim(agentId: string): boolean
  /** Test isolation and session switches: drop the request and the mark. */
  clear(): void
}

/** The session the pending request was addressed to; a switch invalidates it. */
let addressedSession: string | null = null

export const useSubagentFocus = create<SubagentFocusStore>((set, get) => ({
  pending: null,
  current: null,

  open(sessionId, agentId) {
    addressedSession = sessionId
    set({ pending: { agentId }, current: agentId })
  },

  claim(agentId) {
    const pending = get().pending
    if (pending === null || pending.agentId !== agentId) return false
    // `current` is deliberately left standing: it is the reader's pick, and
    // the catalog's check should still point at it after the row opens.
    set({ pending: null })
    return true
  },

  clear() {
    addressedSession = null
    set({ pending: null, current: null })
  },
}))

/** The session the addressed subagent belongs to (a session switch clears it). */
export function subagentFocusSession(): string | null {
  return addressedSession
}

/**
 * Drop a pending request that belongs to a session the reader has left. Called
 * on session switch: an address into another session's transcript would
 * otherwise expand an unrelated row that happens to reuse the agent id.
 *
 * A null `sessionId` is the not-yet-known state (the first render, before the
 * catalog has selected a session), not a different session — clearing on it
 * would discard a request published a moment earlier.
 */
export function dropForeignSubagentFocus(sessionId: string | null): void {
  if (sessionId === null) return
  if (addressedSession !== null && addressedSession !== sessionId) {
    useSubagentFocus.getState().clear()
  }
}
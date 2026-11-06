import { create } from 'zustand'
import { fetchAgentTranscript } from '../api/client'
import type { MessageState } from './conversations'
import { toMessageState } from './conversations'

/**
 * Restored sub-agent conversations, keyed by `sessionId` + `agentId`.
 *
 * A sub-agent's work lives in its own sidechain transcript
 * (`<project>/<sessionId>/subagents/agent-<agentId>.jsonl`), which the gateway
 * serves through `GET /api/sessions/{id}/agents/{agentId}/transcript` — parsed
 * with the same implementation the TUI's local-agent viewer uses, so both
 * surfaces read one transcript rather than two nearly-equal ones.
 *
 * Rows arrive in the parent snapshot's own shape, so they are reduced with the
 * shared {@link toMessageState} and rendered by the same row components
 * (`MessageItem`) the chat uses. Loading is per-agent and lazy: the row only
 * asks when the reader expands the transcript, and the cache is keyed so a
 * second open of the same agent never refetches. A failure is kept on the
 * entry (with a retry) rather than thrown, so a missing sidechain reads as
 * "no record" instead of breaking the row.
 */

/** The fetch status of one agent's transcript. */
export type AgentTranscriptStatus = 'loading' | 'ready' | 'failed'

export interface AgentTranscriptEntry {
  readonly status: AgentTranscriptStatus
  /** The reduced rows; empty until a read succeeds. */
  readonly messages: readonly MessageState[]
  /** A human-readable reason for `failed`. */
  readonly message: string | null
}

export interface AgentTranscriptStore {
  readonly transcripts: Readonly<Record<string, AgentTranscriptEntry>>
  /**
   * Ensure the transcript for `agentId` in `sessionId` is loaded. Idempotent:
   * a key already loading or loaded is left alone (open the same agent twice
   * and the second call is a no-op).
   */
  load(sessionId: string, agentId: string): void
  /** Drop one entry and read it again (the failure note's retry). */
  reload(sessionId: string, agentId: string): void
}

/** The cache key. Both halves are opaque ids, so a separator that cannot appear in either is enough. */
function keyOf(sessionId: string, agentId: string): string {
  return `${sessionId}\u0000${agentId}`
}

export const useAgentTranscript = create<AgentTranscriptStore>((set, get) => ({
  transcripts: {},

  load(sessionId, agentId) {
    const key = keyOf(sessionId, agentId)
    if (get().transcripts[key] !== undefined) return
    set((state) => ({
      transcripts: { ...state.transcripts, [key]: { status: 'loading', messages: [], message: null } },
    }))
    void read(set, sessionId, agentId)
  },

  reload(sessionId, agentId) {
    set((state) => {
      const { [keyOf(sessionId, agentId)]: _dropped, ...rest } = state.transcripts
      return { transcripts: rest }
    })
    get().load(sessionId, agentId)
  },
}))

/** One read, landing into the entry the caller's `load` already claimed. */
async function read(
  set: (updater: (state: AgentTranscriptStore) => Partial<AgentTranscriptStore>) => void,
  sessionId: string,
  agentId: string,
): Promise<void> {
  const key = keyOf(sessionId, agentId)
  const land = (entry: AgentTranscriptEntry): void => {
    set((state) => ({ transcripts: { ...state.transcripts, [key]: entry } }))
  }
  try {
    const snapshot = await fetchAgentTranscript(sessionId, agentId)
    land({ status: 'ready', messages: snapshot.messages.map(toMessageState), message: null })
  } catch (error) {
    land({
      status: 'failed',
      messages: [],
      message: error instanceof Error ? error.message : String(error),
    })
  }
}

/** Test isolation: drop every cached transcript. */
export function resetAgentTranscripts(): void {
  useAgentTranscript.setState({ transcripts: {} })
}
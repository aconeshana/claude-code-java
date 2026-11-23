import { create } from 'zustand'
import { fetchAgentTranscript } from '../api/client'
import type { MessageState } from './conversations'
import { toMessageState } from './conversations'

/**
 * Restored & live sub-agent conversations, keyed by `sessionId` + `agentId`.
 *
 * Served through `GET /api/sessions/{id}/agents/{agentId}/transcript`, which
 * returns the live in-memory display transcript while the agent is running and
 * falls back to its persisted sidechain JSONL once completed.
 */

export type AgentTranscriptStatus = 'loading' | 'ready' | 'failed'

export interface AgentTranscriptEntry {
  readonly status: AgentTranscriptStatus
  /** The reduced rows; empty until a read succeeds. */
  readonly messages: readonly MessageState[]
  /** A human-readable reason for `failed`. */
  readonly message: string | null
  /** True when the gateway reports an active in-process run for this agent. */
  readonly running: boolean
}

export interface AgentTranscriptStore {
  readonly transcripts: Readonly<Record<string, AgentTranscriptEntry>>
  /** Ensure the transcript is loaded (no-op if already loading or ready). */
  load(sessionId: string, agentId: string): void
  /** Drop one entry and read it again (shows loading state). */
  reload(sessionId: string, agentId: string): void
  /**
   * Silent background refresh: updates messages and running flag in place
   * without flashing a loading state (used while polling a live/resumed agent).
   */
  refresh(sessionId: string, agentId: string): Promise<boolean>
  /** Optimistically append a user follow-up message while a continuation starts. */
  appendOptimisticUser(sessionId: string, agentId: string, text: string): void
  /** Clear or set the running flag (e.g. when a continuation request fails). */
  setRunning(sessionId: string, agentId: string, running: boolean): void
}

function keyOf(sessionId: string, agentId: string): string {
  return `${sessionId}\u0000${agentId}`
}

let optimisticSeq = 0

export const useAgentTranscript = create<AgentTranscriptStore>((set, get) => ({
  transcripts: {},

  load(sessionId, agentId) {
    const key = keyOf(sessionId, agentId)
    if (get().transcripts[key] !== undefined) return
    set((state) => ({
      transcripts: {
        ...state.transcripts,
        [key]: { status: 'loading', messages: [], message: null, running: false },
      },
    }))
    void read(set, sessionId, agentId, false)
  },

  reload(sessionId, agentId) {
    set((state) => {
      const { [keyOf(sessionId, agentId)]: _dropped, ...rest } = state.transcripts
      return { transcripts: rest }
    })
    get().load(sessionId, agentId)
  },

  async refresh(sessionId, agentId) {
    return read(set, sessionId, agentId, true)
  },

  appendOptimisticUser(sessionId, agentId, text) {
    const key = keyOf(sessionId, agentId)
    const current = get().transcripts[key]
    const optimistic: MessageState = {
      kind: 'user',
      id: `optimistic-user-${++optimisticSeq}`,
      text,
      time: Date.now(),
    }
    set((state) => ({
      transcripts: {
        ...state.transcripts,
        [key]: {
          status: 'ready',
          messages: [...(current?.messages ?? []), optimistic],
          message: null,
          running: true,
        },
      },
    }))
  },

  setRunning(sessionId, agentId, running) {
    const key = keyOf(sessionId, agentId)
    const current = get().transcripts[key]
    if (current == null) return
    set((state) => ({
      transcripts: {
        ...state.transcripts,
        [key]: { ...current, running },
      },
    }))
  },
}))

async function read(
  set: (updater: (state: AgentTranscriptStore) => Partial<AgentTranscriptStore>) => void,
  sessionId: string,
  agentId: string,
  silent: boolean,
): Promise<boolean> {
  const key = keyOf(sessionId, agentId)
  try {
    const snapshot = await fetchAgentTranscript(sessionId, agentId)
    const running = snapshot.running === true
    set((state) => ({
      transcripts: {
        ...state.transcripts,
        [key]: {
          status: 'ready',
          messages: snapshot.messages.map(toMessageState),
          message: null,
          running,
        },
      },
    }))
    return running
  } catch (error) {
    if (!silent) {
      set((state) => ({
        transcripts: {
          ...state.transcripts,
          [key]: {
            status: 'failed',
            messages: [],
            message: error instanceof Error ? error.message : String(error),
            running: false,
          },
        },
      }))
    }
    return false
  }
}

/** Test isolation: drop every cached transcript. */
export function resetAgentTranscripts(): void {
  useAgentTranscript.setState({ transcripts: {} })
}

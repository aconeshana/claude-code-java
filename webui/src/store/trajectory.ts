import { create } from 'zustand'
import {
  fetchAgentTrajectory,
  fetchSessionTrajectory,
  type RawTrajectoryRequestNumber,
  type RawTrajectoryTurn,
} from '../api/trajectory'
import type { MirrorFrame } from '../api/types'
import { useConversationView } from './conversationView'

export interface TrajectoryEntryState {
  readonly loading: boolean
  readonly olderLoading: boolean
  readonly error: string | null
  readonly totalTurns: number
  readonly oldestTurn: number
  readonly hasOlderTurns: boolean
  readonly turns: readonly RawTrajectoryTurn[]
  readonly requestNumbers: readonly RawTrajectoryRequestNumber[]
}

export interface SubagentTrajectoryDrill {
  readonly agentId: string
  readonly label: string
}

export interface TrajectoryStore {
  readonly entries: Readonly<Record<string, TrajectoryEntryState>>
  /** Per-session subagent drill-down stack (depth bounded to 2 on backend). */
  readonly drillStackBySession: Readonly<Record<string, readonly SubagentTrajectoryDrill[]>>
  readonly inspectCallBySession: Readonly<Record<string, string | null>>
  load(sessionId: string, agentId?: string | null, includeCallId?: string | null): Promise<void>
  loadOlder(sessionId: string, agentId?: string | null): Promise<boolean>
  drillIntoSubagent(sessionId: string, agentId: string, label?: string): void
  popSubagentDrill(sessionId: string): void
  clearSubagentDrill(sessionId: string): void
  requestInspectCall(sessionId: string, callId: string): void
  acknowledgeInspectCall(sessionId: string): void
  applyFrame(frame: MirrorFrame): void
}

const EMPTY_ENTRY: TrajectoryEntryState = {
  loading: false,
  olderLoading: false,
  error: null,
  totalTurns: 0,
  oldestTurn: 0,
  hasOlderTurns: false,
  turns: [],
  requestNumbers: [],
}

function entryKey(sessionId: string, agentId?: string | null): string {
  return agentId != null && agentId !== '' ? `${sessionId}\u0000${agentId}` : sessionId
}

let refreshTimerBySession = new Map<string, number>()

export const useTrajectory = create<TrajectoryStore>((set, get) => ({
  entries: {},
  drillStackBySession: {},
  inspectCallBySession: {},

  async load(sessionId, agentId, includeCallId) {
    if (!sessionId) return
    const key = entryKey(sessionId, agentId)
    const prev = get().entries[key]
    set((state) => ({
      entries: {
        ...state.entries,
        [key]: {
          ...(prev ?? EMPTY_ENTRY),
          loading: prev == null || prev.turns.length === 0,
          error: null,
        },
      },
    }))
    try {
      const data = agentId != null && agentId !== ''
        ? await fetchAgentTrajectory(sessionId, agentId)
        : await fetchSessionTrajectory(sessionId, { includeCallId })
      set((state) => ({
        entries: {
          ...state.entries,
          [key]: {
            loading: false,
            olderLoading: false,
            error: null,
            totalTurns: data.total_turns,
            oldestTurn: data.oldest_turn,
            hasOlderTurns: data.has_older_turns,
            turns: data.turns,
            requestNumbers: data.request_numbers,
          },
        },
      }))
    } catch (failure: unknown) {
      set((state) => ({
        entries: {
          ...state.entries,
          [key]: {
            ...(state.entries[key] ?? EMPTY_ENTRY),
            loading: false,
            olderLoading: false,
            error: failure instanceof Error ? failure.message : String(failure),
          },
        },
      }))
    }
  },

  async loadOlder(sessionId, agentId) {
    const key = entryKey(sessionId, agentId)
    const current = get().entries[key]
    if (current == null || !current.hasOlderTurns || current.olderLoading || current.oldestTurn <= 1) {
      return false
    }
    set((state) => ({
      entries: {
        ...state.entries,
        [key]: { ...current, olderLoading: true },
      },
    }))
    try {
      const older = agentId != null && agentId !== ''
        ? await fetchAgentTrajectory(sessionId, agentId, { beforeTurn: current.oldestTurn })
        : await fetchSessionTrajectory(sessionId, { beforeTurn: current.oldestTurn })
      set((state) => {
        const latest = state.entries[key] ?? current
        const existingTurns = new Set(
          latest.turns.map(t => t.turn).filter((t): t is number => t !== null),
        )
        const prependedTurns = older.turns.filter(
          t => t.turn === null || !existingTurns.has(t.turn),
        )
        const existingReqNums = new Set(latest.requestNumbers.map(r => r.number))
        const prependedReqs = older.request_numbers.filter(r => !existingReqNums.has(r.number))
        return {
          entries: {
            ...state.entries,
            [key]: {
              ...latest,
              olderLoading: false,
              oldestTurn: older.oldest_turn,
              hasOlderTurns: older.has_older_turns,
              turns: [...prependedTurns, ...latest.turns],
              requestNumbers: [...prependedReqs, ...latest.requestNumbers],
            },
          },
        }
      })
      return older.turns.length > 0
    } catch {
      set((state) => ({
        entries: {
          ...state.entries,
          [key]: { ...(state.entries[key] ?? current), olderLoading: false },
        },
      }))
      return false
    }
  },

  drillIntoSubagent(sessionId, agentId, label) {
    const currentStack = get().drillStackBySession[sessionId] ?? []
    if (currentStack.length >= 2) return
    if (currentStack[currentStack.length - 1]?.agentId === agentId) return
    const nextStack = [
      ...currentStack,
      { agentId, label: label != null && label.trim() !== '' ? label : `agent-${agentId}` },
    ]
    set((state) => ({
      drillStackBySession: {
        ...state.drillStackBySession,
        [sessionId]: nextStack,
      },
    }))
    void get().load(sessionId, agentId)
  },

  popSubagentDrill(sessionId) {
    const currentStack = get().drillStackBySession[sessionId] ?? []
    if (currentStack.length === 0) return
    set((state) => ({
      drillStackBySession: {
        ...state.drillStackBySession,
        [sessionId]: currentStack.slice(0, -1),
      },
    }))
  },

  clearSubagentDrill(sessionId) {
    if ((get().drillStackBySession[sessionId]?.length ?? 0) === 0) return
    set((state) => ({
      drillStackBySession: {
        ...state.drillStackBySession,
        [sessionId]: [],
      },
    }))
  },

  requestInspectCall(sessionId, callId) {
    set((state) => ({
      drillStackBySession: {
        ...state.drillStackBySession,
        [sessionId]: [],
      },
      inspectCallBySession: {
        ...state.inspectCallBySession,
        [sessionId]: callId,
      },
    }))
    useConversationView.getState().setView(sessionId, 'trajectory')
    void get().load(sessionId, null, callId)
  },

  acknowledgeInspectCall(sessionId) {
    set((state) => ({
      inspectCallBySession: {
        ...state.inspectCallBySession,
        [sessionId]: null,
      },
    }))
  },

  applyFrame(frame) {
    const data = frame.data as { readonly session_id?: string | undefined }
    const sessionId = typeof data.session_id === 'string' ? data.session_id : undefined
    if (!sessionId) return
    const view = useConversationView.getState().viewOf(sessionId)
    if (view !== 'trajectory' && !(sessionId in get().entries)) return
    const relevant = frame.event === 'turn.started'
      || frame.event === 'turn.completed'
      || frame.event === 'tool.started'
      || frame.event === 'tool.completed'
      || frame.event === 'output.text'
    if (!relevant) return

    const existingTimer = refreshTimerBySession.get(sessionId)
    if (existingTimer !== undefined) {
      window.clearTimeout(existingTimer)
    }
    const timer = window.setTimeout(() => {
      refreshTimerBySession.delete(sessionId)
      const stack = get().drillStackBySession[sessionId] ?? []
      const activeDrill = stack[stack.length - 1]
      void get().load(sessionId, activeDrill?.agentId ?? null)
    }, frame.event === 'turn.completed' ? 80 : 250)
    refreshTimerBySession.set(sessionId, timer)
  },
}))

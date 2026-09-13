import { create } from 'zustand'
import type { MessagesSnapshot, MirrorFrame, SnapshotMessage, TurnUsage } from '../api/types'

/**
 * Per-session conversation state: the snapshot is the authority, mirror
 * frames are the live delta.
 *
 * Reduction rules (the mirror frames' contract):
 * - output.text/output.thinking are full blocks, one frame per block —
 *   never deltas; appending is the whole story.
 * - tool.started opens a pending tool_call; tool.completed pairs back by
 *   tool_use_id and fills the result.
 * - turn.completed finishes the assistant message and lands the frame's
 *   turn usage facts on it; turn.started opens a fresh one.
 * - Every frame carries a monotonic id; a frame at or below the last
 *   applied id for its session is a journal replay overlap and drops.
 */

interface AssistantMessageState {
  readonly kind: 'assistant'
  readonly id: string
  readonly textBlocks: readonly string[]
  readonly thinkingBlocks: readonly string[]
  readonly toolCalls: readonly ToolCallState[]
  readonly open: boolean
  /** 1-based turn number (snapshot path); absent before the first human prompt. */
  readonly turn?: number
  /** This assistant step's provider-reported buckets (snapshot or frame path). */
  readonly turnUsage?: TurnUsage
  /** Turn wall time in ms (frame path only; the snapshot path has no per-turn clock). */
  readonly runMs?: number
  /** Durable transcript timestamp, epoch ms — the turn-tail clock label. */
  readonly time?: number
}

export interface ToolCallState {
  readonly toolUseId: string
  readonly name: string
  readonly args: Readonly<Record<string, unknown>> | null
  readonly status: 'pending' | 'executed' | 'failed'
  readonly ready: boolean
  readonly resultData: string | null
  readonly resultType: string | null
  readonly resultError: string | null
  readonly transcriptPath: string | null
  readonly locations: readonly string[] | null
}

export type MessageState =
  | AssistantMessageState
  | { readonly kind: 'user'; readonly id: string; readonly text: string
    /** Durable transcript timestamp, epoch ms — the user row's leading clock. */
    readonly time?: number }

export interface ConversationState {
  readonly messages: readonly MessageState[]
  /** Highest mirror frame id applied to this conversation. */
  readonly lastFrameId: number
  /** Turn spinner: a turn.started without its matching completion yet. */
  readonly turnRunning: boolean
  readonly lastError: string | null
}

export interface ConversationsStore {
  readonly conversations: Readonly<Record<string, ConversationState>>
  /** Marks a session as loading its snapshot; frames arriving meanwhile park. */
  loadSnapshot(sessionId: string): Promise<void>
  applyFrame(frame: MirrorFrame): void
}

const EMPTY: ConversationState = { messages: [], lastFrameId: 0, turnRunning: false, lastError: null }

export const useConversations = create<ConversationsStore>((set, get) => ({
  conversations: {},

  async loadSnapshot(sessionId: string) {
    // Pull the snapshot lazily to avoid a module cycle: client.ts imports
    // only wire types from types.ts, so this dynamic import is safe.
    const { fetchSnapshot } = await import('../api/client')
    const snapshot: MessagesSnapshot = await fetchSnapshot(sessionId)
    const messages = snapshot.messages.map(toMessageState)
    // The snapshot is authoritative for content; frames that raced ahead of
    // it would duplicate, so the conversation resets wholesale and the next
    // frame id (already monotonic server-side) re-baselines the cursor.
    set((state) => ({
      conversations: {
        ...state.conversations,
        [sessionId]: { messages, lastFrameId: 0, turnRunning: false, lastError: null },
      },
    }))
  },

  applyFrame(frame: MirrorFrame) {
    const sessionId = frame.data.session_id
    if (sessionId == null || sessionId === '') return
    const current = get().conversations[sessionId] ?? EMPTY
    if (frame.id !== 0 && frame.id <= current.lastFrameId) return

    const next = reduceFrame(current, frame)
    if (next === current) return
    set((state) => ({
      conversations: {
        ...state.conversations,
        [sessionId]: { ...next, lastFrameId: frame.id },
      },
    }))
  },
}))

function toMessageState(message: SnapshotMessage): MessageState {
  if (message.role === 'user') {
    return {
      kind: 'user',
      id: message.id,
      text: message.text,
      ...(message.time !== undefined ? { time: message.time } : {}),
    }
  }
  const textBlocks: string[] = []
  const thinkingBlocks: string[] = []
  const toolCalls: ToolCallState[] = []
  for (const block of message.content) {
    if (block.type === 'text') textBlocks.push(block.text)
    else if (block.type === 'thinking') thinkingBlocks.push(block.thinking)
    else {
      const tool = block.tool
      toolCalls.push({
        toolUseId: tool.tool_use_id,
        name: tool.name,
        args: tool.args,
        status: tool.status,
        ready: tool.ready,
        resultData: tool.result?.data ?? null,
        resultType: tool.result?.type ?? null,
        resultError: tool.result?.errorMessage ?? null,
        transcriptPath: tool.result?.transcript_path ?? null,
        locations: tool.result?.locations ?? null,
      })
    }
  }
  return {
    kind: 'assistant',
    id: message.id,
    textBlocks,
    thinkingBlocks,
    toolCalls,
    open: false,
    ...(message.time !== undefined ? { time: message.time } : {}),
    ...(message.turn !== undefined ? { turn: message.turn } : {}),
    ...(message.turn_usage !== undefined ? { turnUsage: message.turn_usage } : {}),
  }
}

function reduceFrame(state: ConversationState, frame: MirrorFrame): ConversationState {
  switch (frame.event) {
    case 'turn.started': {
      const userMessage: MessageState = {
        kind: 'user',
        id: `live-user-${frame.id}`,
        text: frame.data.display_text,
        ...(frame.data.time !== undefined ? { time: frame.data.time } : {}),
      }
      return {
        ...state,
        messages: [...state.messages, userMessage],
        turnRunning: true,
        lastError: null,
      }
    }
    case 'output.text': {
      const messages = appendBlock(state.messages, (last) => ({
        ...last,
        textBlocks: [...last.textBlocks, frame.data.content],
      }))
      return { ...state, messages }
    }
    case 'output.thinking': {
      const messages = appendBlock(state.messages, (last) => ({
        ...last,
        thinkingBlocks: [...last.thinkingBlocks, frame.data.content],
      }))
      return { ...state, messages }
    }
    case 'tool.started': {
      const call: ToolCallState = {
        toolUseId: frame.data.tool_use_id,
        name: frame.data.name,
        args: frame.data.input ?? null,
        status: 'pending',
        ready: false,
        resultData: null,
        resultType: null,
        resultError: null,
        transcriptPath: null,
        locations: null,
      }
      const messages = appendBlock(state.messages, (last) => ({
        ...last,
        toolCalls: [...last.toolCalls, call],
      }))
      return { ...state, messages }
    }
    case 'tool.completed': {
      const messages = state.messages.map((message) => {
        if (message.kind !== 'assistant') return message
        const toolCalls: ToolCallState[] = message.toolCalls.map((call) => {
          if (call.toolUseId !== frame.data.tool_use_id) return call
          const completed: ToolCallState = {
            toolUseId: call.toolUseId,
            name: call.name,
            args: call.args,
            // The mirror frame reports completed|failed; the snapshot's
            // tool vocabulary uses executed for the success case — the same
            // state the snapshot path produces.
            status: frame.data.status === 'completed' ? 'executed' : 'failed',
            ready: true,
            resultData: frame.data.result.data,
            resultType: frame.data.result.type,
            resultError: frame.data.result.errorMessage ?? null,
            transcriptPath: frame.data.result.transcript_path ?? null,
            locations: frame.data.result.locations ?? null,
          }
          return completed
        })
        return { ...message, toolCalls }
      })
      return { ...state, messages }
    }
    case 'session.error': {
      return { ...state, turnRunning: false, lastError: frame.data.message }
    }
    case 'turn.completed': {
      // The completion frame carries the turn's durable-fold delta and wall
      // time; land them on the closing assistant row (the turn tail chrome
      // renders from there).
      const messages = closeAssistant(state.messages, (last) => ({
        ...last,
        ...(frame.data.turn !== undefined ? { turn: frame.data.turn } : {}),
        ...(frame.data.turn_usage !== undefined ? { turnUsage: frame.data.turn_usage } : {}),
        runMs: frame.data.elapsed_ms,
        ...(frame.data.time !== undefined ? { time: frame.data.time } : {}),
      }))
      return { ...state, messages, turnRunning: false }
    }
    case 'turn.cancelled': {
      const messages = closeAssistant(state.messages)
      return { ...state, messages, turnRunning: false }
    }
    case 'session.idle': {
      return { ...state, turnRunning: false }
    }
    default:
      // permission.asked/resolved feed the approvals store; session.activated
      // feeds the sessions store; tool.progress is not rendered in v1.
      return state
  }
}

/** Applies {@code edit} to the open assistant row, opening one when absent. */
function appendBlock(
  messages: readonly MessageState[],
  edit: (last: AssistantMessageState) => AssistantMessageState,
): readonly MessageState[] {
  const last = messages[messages.length - 1]
  if (last != null && last.kind === 'assistant' && last.open) {
    return [...messages.slice(0, -1), edit(last)]
  }
  return [...messages, edit({
    kind: 'assistant',
    id: `live-${messages.length}`,
    textBlocks: [],
    thinkingBlocks: [],
    toolCalls: [],
    open: true,
  })]
}

function closeAssistant(
  messages: readonly MessageState[],
  edit?: (last: AssistantMessageState) => AssistantMessageState,
): readonly MessageState[] {
  const last = messages[messages.length - 1]
  if (last == null || last.kind !== 'assistant' || !last.open) return messages
  return [...messages.slice(0, -1), edit == null ? { ...last, open: false } : { ...edit(last), open: false }]
}

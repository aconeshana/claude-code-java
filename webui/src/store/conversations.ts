import { create } from 'zustand'
import type { MessageImageAttachment, MessagesSnapshot, MirrorFrame, SnapshotMessage, TurnUsage } from '../api/types'
import { mergeDelta, type StreamingDraft } from './streamingDraft'

/**
 * Per-session conversation state: the snapshot is the authority, mirror
 * frames are the live delta.
 *
 * Reduction rules (the mirror frames' contract):
 * - output.text/output.thinking are full blocks, one frame per block;
 *   appending is the whole story for the transcript rows.
 * - output.text.delta/output.thinking.delta are the transient live slices
 *   of the block in flight: they only build `streaming`, the draft the view
 *   renders until that block's settled frame (which clears it) arrives.
 * - tool.started opens a pending tool_call; tool.completed pairs back by
 *   tool_use_id and fills the result.
 * - turn.completed finishes the assistant message and lands the frame's
 *   turn usage facts on it; turn.started opens a fresh one.
 * - Every journaled frame carries a monotonic id; a frame at or below the
 *   last applied id for its session is a journal replay overlap and drops.
 *   Transient frames arrive with id 0: never deduped, never a cursor.
 */

interface AssistantMessageState {
  readonly kind: 'assistant'
  readonly id: string
  readonly textBlocks: readonly string[]
  readonly thinkingBlocks: readonly string[]
  readonly toolCalls: readonly ToolCallState[]
  readonly open: boolean
  /**
   * The owning assistant step's identity (frame path: the mirror frame's
   * message_id; snapshot path: the entry id, which is the same fact). Frames
   * sharing it belong to one step; a change closes the row and opens the next.
   */
  readonly messageId?: string
  /** A System-message projection rather than model output (frame path only). */
  readonly synthetic?: boolean
  /** 1-based turn number (snapshot path); absent before the first human prompt. */
  readonly turn?: number
  /** This assistant step's provider-reported buckets (snapshot or frame path). */
  readonly turnUsage?: TurnUsage
  /** Turn wall time in ms (frame path only; the snapshot path has no per-turn clock). */
  readonly runMs?: number
  /** Time to first stream output, ms (frame path; absent when the turn produced none). */
  readonly ttftMs?: number
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
    readonly time?: number
    readonly images?: readonly MessageImageAttachment[] }

export interface ConversationState {
  readonly messages: readonly MessageState[]
  /** Highest mirror frame id applied to this conversation. */
  readonly lastFrameId: number
  /** Turn spinner: a turn.started without its matching completion yet. */
  readonly turnRunning: boolean
  /**
   * The running turn's start clock, epoch ms — the running status's elapsed
   * origin. Frame path: turn.started's time; snapshot path: running_since, so
   * a page opened mid-turn resumes the same clock. Absent while idle.
   */
  readonly turnStartedAt?: number
  /**
   * The block streaming right now (live delta frames, or the snapshot's
   * `streaming` seed for a page opened mid-block). Absent between blocks.
   */
  readonly streaming?: StreamingDraft
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
    // The snapshot is authoritative for CONTENT, but the cursor it replaces
    // must survive: resetting lastFrameId to 0 un-dedupes every journaled
    // frame still inside the ring, so a turn.started replayed on reconnect
    // (or on a session.activated re-select) appended a second copy of a user
    // row the snapshot already carried. Frame ids are monotonic server-side,
    // so carrying the previous cursor forward drops exactly that overlap.
    set((state) => ({
      conversations: {
        ...state.conversations,
        [sessionId]: snapshotConversation(
          messages, snapshot.running_since, snapshot.streaming,
          state.conversations[sessionId]?.lastFrameId ?? 0,
        ),
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
        [sessionId]: { ...next, lastFrameId: frame.id === 0 ? current.lastFrameId : frame.id },
      },
    }))
  },
}))

/**
 * The conversation a snapshot seeds. The snapshot carries running_since only
 * while a turn runs, so a page opened mid-turn shows the running status (and
 * its elapsed clock) before the next frame arrives. Likewise `streaming`
 * seeds the draft of a block caught mid-stream, so the next delta's offset
 * continues it rather than gapping.
 *
 * `lastFrameId` is the cursor the snapshot replaces, carried over rather than
 * zeroed — see loadSnapshot. A first load for a session passes 0, which is
 * correct because there is no prior state for a frame to overlap.
 */
function snapshotConversation(
  messages: readonly MessageState[],
  runningSince: number | undefined,
  streaming: StreamingDraft | undefined,
  lastFrameId: number,
): ConversationState {
  return {
    messages,
    lastFrameId,
    turnRunning: runningSince !== undefined,
    ...(runningSince !== undefined ? { turnStartedAt: runningSince } : {}),
    ...(streaming !== undefined ? { streaming: { kind: streaming.kind, content: streaming.content } } : {}),
    lastError: null,
  }
}

function toMessageState(message: SnapshotMessage): MessageState {
  if (message.role === 'user') {
    return {
      kind: 'user',
      id: message.id,
      text: message.text ?? '',
      ...(message.time !== undefined ? { time: message.time } : {}),
      ...(message.images !== undefined ? { images: message.images } : {}),
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
    // The snapshot entry id IS the step identity the frame path reports as
    // message_id, so both paths reduce to the same row identity.
    messageId: message.id,
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
      // A replay overlap the cursor could not catch (a frame replayed after a
      // snapshot that already folded the same prompt) lands here. The live row
      // and the snapshot row carry different id spaces (live-user-N vs the
      // durable uuid), so identity is compared on the facts both sides share.
      //
      // Only the trailing user row is eligible: a genuine second prompt with
      // the same wording is always appended AFTER the first, so it can never
      // match a row that was there before this frame. Matching anywhere in the
      // transcript would swallow it — and a repeated "继续吧" is exactly the
      // case this guard must not eat.
      const text: string = frame.data.display_text
      const turnStartedAt = frame.data.time ?? Date.now()
      let trailing: (MessageState & { readonly kind: 'user' }) | undefined
      for (let i = state.messages.length - 1; i >= 0; i -= 1) {
        const candidate = state.messages[i]!
        if (candidate.kind === 'user') { trailing = candidate; break }
      }
      const alreadyShown = trailing !== undefined
        && trailing.text === text
        // The two clocks are taken at different moments (the snapshot stamps
        // the row when it is written, the frame when the turn is dispatched),
        // so equality is a strong signal, not a requirement: the cursor fix in
        // loadSnapshot is what normally prevents the overlap, and this only
        // needs to catch the case where that cursor was lost.
        && (frame.data.time === undefined || trailing.time === undefined
          || frame.data.time === trailing.time)
      if (alreadyShown) {
        return { ...settleDraft(state), turnRunning: true, turnStartedAt, lastError: null }
      }
      const userMessage: MessageState = {
        kind: 'user',
        id: `live-user-${frame.id}`,
        text,
        ...(frame.data.time !== undefined ? { time: frame.data.time } : {}),
        ...(frame.data.images !== undefined ? { images: frame.data.images } : {}),
      }
      return {
        ...settleDraft(state),
        messages: [...state.messages, userMessage],
        turnRunning: true,
        turnStartedAt,
        lastError: null,
      }
    }
    case 'output.text': {
      const messages = appendBlock(state.messages, stepOf(frame.data), (last) => ({
        ...last,
        textBlocks: [...last.textBlocks, frame.data.content],
      }))
      // A System projection is not the streamed block; the draft stays.
      return { ...(frame.data.synthetic === true ? state : settleDraft(state)), messages }
    }
    case 'output.thinking': {
      const messages = appendBlock(state.messages, stepOf(frame.data), (last) => ({
        ...last,
        thinkingBlocks: [...last.thinkingBlocks, frame.data.content],
      }))
      return { ...settleDraft(state), messages }
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
      const messages = appendBlock(state.messages, stepOf(frame.data), (last) => ({
        ...last,
        toolCalls: [...last.toolCalls, call],
      }))
      return { ...settleDraft(state), messages }
    }
    case 'output.text.delta':
    case 'output.thinking.delta': {
      const kind = frame.event === 'output.text.delta' ? 'text' : 'thinking'
      const streaming = mergeDelta(state.streaming, kind, frame.data.content, frame.data.offset)
      // An unchanged draft (a gapped slice) leaves the state — and the
      // render — alone; `messages` keeps its identity either way, so the
      // fold derivation never re-runs per slice.
      return streaming === state.streaming || streaming === undefined ? state : { ...state, streaming }
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
      return { ...idle(state), lastError: frame.data.message }
    }
    case 'turn.completed': {
      // The completion frame carries the turn's durable-fold delta and wall
      // time. The snapshot path stamps the turn facts — `turn`, `time` and
      // `turn_usage` — on EVERY assistant row of the turn
      // (GatewayMessagesSnapshotHandler.assistantEntry), so the live path
      // must too. Two reasons: the fold's turn number has to reach every row
      // (a turn ending on a tool call has no trailing answer row), and the
      // tail facts have to reach the row the tail anchor lands on, which is
      // the last text-bearing step — not necessarily the closing row. The
      // anchor gate in the fold view picks exactly one of the stamped rows,
      // so stamping widely cannot fan the tail out.
      const messages = closeTurn(state.messages, (row) => ({
        ...row,
        ...(frame.data.turn !== undefined ? { turn: frame.data.turn } : {}),
        ...(frame.data.turn_usage !== undefined ? { turnUsage: frame.data.turn_usage } : {}),
        runMs: frame.data.elapsed_ms,
        ...(frame.data.ttft_ms !== undefined ? { ttftMs: frame.data.ttft_ms } : {}),
        ...(frame.data.time !== undefined ? { time: frame.data.time } : {}),
      }))
      return { ...idle(state), messages }
    }
    case 'turn.cancelled': {
      const messages = closeAssistant(state.messages)
      return { ...idle(state), messages }
    }
    case 'session.idle': {
      return idle(state)
    }
    default:
      // permission.asked/resolved feed the approvals store; session.activated
      // feeds `useSessions.applyFrame`; tool.progress is not rendered in v1.
      return state
  }
}

/** The state with no turn running: the spinner, its start clock and any draft clear. */
function idle(state: ConversationState): ConversationState {
  const { turnStartedAt: _cleared, ...rest } = settleDraft(state)
  return { ...rest, turnRunning: false }
}

/** The state without its streaming draft — a settled block replaced it. */
function settleDraft(state: ConversationState): ConversationState {
  if (state.streaming === undefined) return state
  const { streaming: _settled, ...rest } = state
  return rest
}

/** One step's identity as reported by the frame that carries a block. */
interface StepIdentity {
  readonly messageId?: string
  readonly synthetic?: boolean
}

/** The step identity a block-carrying frame reports. */
function stepOf(data: { message_id?: string; synthetic?: boolean }): StepIdentity {
  return {
    ...(data.message_id !== undefined ? { messageId: data.message_id } : {}),
    ...(data.synthetic === true ? { synthetic: true } : {}),
  }
}

/**
 * Applies {@code edit} to the open assistant row of {@code step}, opening one
 * when the step changed or none is open.
 *
 * One assistant message is one step; the mirror fans it out into one frame per
 * content block, so consecutive frames sharing message_id reassemble into one
 * row — the shape the snapshot path serves directly. Without this split a
 * turn's tool steps and its final answer collapse into a single row and the
 * turn-process fold can never find an answer boundary. A frame with no
 * message_id (an older gateway) keeps the previous single-row behavior; a
 * synthetic System projection always stands alone, since it has no step.
 */
function appendBlock(
  messages: readonly MessageState[],
  step: StepIdentity,
  edit: (last: AssistantMessageState) => AssistantMessageState,
): readonly MessageState[] {
  const last = messages[messages.length - 1]
  const openRow = last != null && last.kind === 'assistant' && last.open ? last : null
  if (openRow != null && !opensNewStep(openRow, step)) {
    return [...messages.slice(0, -1), edit(openRow)]
  }
  const settled = openRow == null
    ? messages
    : [...messages.slice(0, -1), { ...openRow, open: false }]
  return [...settled, edit({
    kind: 'assistant',
    // The step id doubles as the row id, matching the snapshot path's entry
    // id, so a reload keeps the same row identity.
    id: step.messageId ?? `live-${messages.length}`,
    textBlocks: [],
    thinkingBlocks: [],
    toolCalls: [],
    open: true,
    ...(step.messageId !== undefined ? { messageId: step.messageId } : {}),
    ...(step.synthetic === true ? { synthetic: true } : {}),
  })]
}

function opensNewStep(open: AssistantMessageState, step: StepIdentity): boolean {
  if (step.synthetic === true || open.synthetic === true) return true
  if (step.messageId == null || open.messageId == null) return false
  return open.messageId !== step.messageId
}

function closeAssistant(
  messages: readonly MessageState[],
  edit?: (last: AssistantMessageState) => AssistantMessageState,
): readonly MessageState[] {
  const last = messages[messages.length - 1]
  if (last == null || last.kind !== 'assistant' || !last.open) return messages
  return [...messages.slice(0, -1), edit == null ? { ...last, open: false } : { ...edit(last), open: false }]
}

/**
 * Closes the running turn: applies {@code stampRow} to every assistant row of
 * the turn and marks the closing row settled.
 *
 * One turn fans out into N assistant rows (one per step), and the snapshot
 * path stamps the turn's facts on all of them, so the live path walks back to
 * the turn's first row — the row after the most recent user row — rather than
 * editing only the tail. The tail facts ride the same stamp: the tail's
 * anchor is the turn's last text-bearing step, which is not necessarily the
 * closing row, so stamping only the closing row would leave the anchor — and
 * therefore the tail — factless on the live path while a reload shows it.
 *
 * Rows before the last user row belong to earlier turns and are left alone.
 */
function closeTurn(
  messages: readonly MessageState[],
  stampRow: (row: AssistantMessageState) => AssistantMessageState,
): readonly MessageState[] {
  const lastIndex = messages.length - 1
  const last = messages[lastIndex]
  if (last == null || last.kind !== 'assistant' || !last.open) return messages

  // The turn starts after the most recent user row; with none, the whole
  // assistant prefix is one (pre-turn) run.
  let start = 0
  for (let index = lastIndex; index >= 0; index--) {
    if (messages[index].kind === 'user') {
      start = index + 1
      break
    }
  }

  return messages.map((message, index) => {
    if (index < start || message.kind !== 'assistant') return message
    const stamped = stampRow(message)
    return index === lastIndex ? { ...stamped, open: false } : stamped
  })
}

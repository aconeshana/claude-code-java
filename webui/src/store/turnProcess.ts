/**
 * Turn-process disclosure state over the vendored dsh fold rules
 * (`vendor/dsh-turn-process/turnProcessRules.ts`).
 *
 * This module owns two things only: the non-persisted manual-expansion store
 * (upstream keeps it in the session-scoped Chat store; absence means
 * collapsed) and the adapter that maps this app's reduced message list onto
 * the rules' `TurnInput` shape. Every eligibility, counting and layout
 * decision lives in the vendored rules — do not re-derive them here.
 *
 * Adapter contract:
 * - a turn is the user row plus the assistant rows that follow it;
 * - one assistant row is one step (upstream's `assistant-step`), and its
 *   ordinal inside the turn is its `step`;
 * - a synthetic System row is a `context` row, not a step: upstream treats an
 *   injection as process evidence that folds, never as an answer candidate;
 * - `hidden="until-found"` rows stay mounted, so browser find can reveal them
 *   (a `beforematch` opens the shared group) and stateful tool renderers keep
 *   their state;
 * - transcriptView "normal" disables folding entirely.
 */
import { create } from 'zustand'
import type { ConversationState, MessageState } from './conversations'
import { useTranscriptView } from './transcriptView'
import {
  derivePresentation, processSpec,
  type AssistantBlock, type AssistantStep, type TurnInput, type TurnMemberRow,
} from '../../vendor/dsh-turn-process/turnProcessRules'

export { isSubagentDelegationTool } from '../../vendor/dsh-turn-process/turnProcessRules'

/** Per-turn manual expansion: absent = collapsed (the compact default). */
export interface TurnProcessStore {
  readonly openTurns: ReadonlySet<number>
  setOpen(turn: number, open: boolean): void
}

export const useTurnProcess = create<TurnProcessStore>(set => ({
  openTurns: new Set(),

  setOpen(turn, open) {
    set((state) => {
      const next = new Set(state.openTurns)
      if (open) next.add(turn)
      else next.delete(turn)
      return { openTurns: next }
    })
  },
}))

/**
 * One message row's fold classification inside its turn.
 * - `member`: hidden behind the control while the group is collapsed;
 * - `answer`: the visible answer row;
 * - `undefined`: independent (user rows, open turns, no-answer turns).
 */
export type RowRole = 'member' | 'answer'

/** The control's summary segments (upstream's three counts). */
export interface TurnProcessCounts {
  readonly toolCallCount: number
  readonly messageCount: number
  readonly subagentCount: number
}

export interface TurnProcessView {
  /** Row roles keyed by message id; unkeyed rows are independent. */
  readonly roles: Readonly<Record<string, RowRole>>
  /** The turn number each folded group belongs to, keyed by the user row's id. */
  readonly controlTurns: Readonly<Record<string, number>>
  /** Fold counts per turn, keyed by the user row's id (the control's label). */
  readonly counts: Readonly<Record<string, TurnProcessCounts>>
  /** The owning turn of every member row, keyed by the member's message id. */
  readonly memberTurns: Readonly<Record<string, number>>
  /** Whether a turn's group is currently expanded. */
  readonly expandedTurns: ReadonlySet<number>
  /**
   * Answer row ids whose collapsed control sits directly against them, taking
   * the 8px follow-gap. Absent when an independent input separates the two
   * (upstream's `compactAnswer`).
   */
  readonly compactAnswers: ReadonlySet<string>
  /**
   * Answer row ids that must hide their own reasoning blocks: the answer
   * step's thinking is process, and the fold's UI state is its single source
   * of truth (upstream's `inlineReasoning`).
   */
  readonly inlineReasoningAnswers: ReadonlySet<string>
}

function emptyView(): TurnProcessView {
  return {
    roles: {},
    controlTurns: {},
    counts: {},
    memberTurns: {},
    expandedTurns: new Set(),
    compactAnswers: new Set(),
    inlineReasoningAnswers: new Set(),
  }
}

type AssistantRow = Extract<MessageState, { kind: 'assistant' }>

/** This app's message state projected onto upstream's block union. */
function assistantBlocks(message: AssistantRow): AssistantBlock[] {
  const blocks: AssistantBlock[] = []
  for (const text of message.thinkingBlocks) blocks.push({ kind: 'reasoning', text })
  for (const text of message.textBlocks) blocks.push({ kind: 'text', text })
  for (const call of message.toolCalls) {
    blocks.push({ kind: 'tool-call', callId: call.toolUseId, name: call.name })
  }
  return blocks
}

/** One turn slice of the transcript: its boundaries and its rows. */
interface TurnSlice {
  readonly turn: number
  readonly userRow: MessageState
  readonly input: TurnInput
  /** Assistant row ids by seq, so roles can be keyed back to messages. */
  readonly idsBySeq: ReadonlyMap<number, string>
}

/**
 * Slice one turn out of the transcript and project it onto the rules' input.
 * Returns undefined when the slice carries no assistant evidence or no turn
 * number (the pre-turn transcript head).
 */
function toTurnSlice(
  messages: readonly MessageState[], start: number, end: number, turnRunning: boolean,
  isLastSlice: boolean,
): TurnSlice | undefined {
  const userRow = messages[start]
  const steps: AssistantStep[] = []
  const rows: TurnMemberRow[] = []
  const idsBySeq = new Map<number, string>()
  let turn: number | undefined
  let open = false

  if (userRow.kind === 'user') rows.push({ seq: start, kind: 'user' })

  for (let seq = start + 1; seq < end; seq++) {
    const row = messages[seq]
    if (row.kind !== 'assistant') continue
    if (row.turn !== undefined) turn ??= row.turn
    if (row.open) open = true
    idsBySeq.set(seq, row.id)
    if (row.synthetic === true) {
      // An injection folds with the process but never answers it.
      rows.push({ seq, kind: 'context' })
      continue
    }
    steps.push({
      seq,
      step: steps.length,
      blocks: assistantBlocks(row),
      complete: !row.open,
    })
  }

  if (turn === undefined || (steps.length === 0 && rows.length <= 1)) return undefined
  return {
    turn,
    userRow,
    idsBySeq,
    input: {
      turn,
      steps,
      rows,
      startSeq: userRow.kind === 'user' ? start : undefined,
      // An open turn never folds: a running row keeps its evidence visible.
      closed: !open && !(isLastSlice && turnRunning),
    },
  }
}

/**
 * Derive the fold view over one conversation's reduced messages: slice the
 * transcript into turns, run the vendored rules over each, and key the
 * resulting roles back to message ids.
 */
export function deriveTurnProcessView(
  conversation: ConversationState | undefined,
  expandedTurns: ReadonlySet<number>,
): TurnProcessView {
  if (conversation == null || useTranscriptView.getState().mode !== 'compact') {
    return emptyView()
  }
  const messages = conversation.messages
  if (messages.length === 0) return emptyView()

  const boundaries: number[] = [0]
  for (let index = 0; index < messages.length; index++) {
    if (messages[index].kind === 'user') boundaries.push(index)
  }

  const roles: Record<string, RowRole> = {}
  const controlTurns: Record<string, number> = {}
  const counts: Record<string, TurnProcessCounts> = {}
  const memberTurns: Record<string, number> = {}
  const compactAnswers = new Set<string>()
  const inlineReasoningAnswers = new Set<string>()
  const seenTurns = new Set<number>()

  for (let boundary = 0; boundary < boundaries.length; boundary++) {
    const start = boundaries[boundary]
    const end = boundary + 1 < boundaries.length ? boundaries[boundary + 1] : messages.length
    const slice = toTurnSlice(
      messages, start, end, conversation.turnRunning, boundary + 1 === boundaries.length,
    )
    if (slice === undefined) continue
    // The snapshot path back-fills turn numbers lazily; a turn number that
    // already produced its view is not re-derived from a later slice.
    if (seenTurns.has(slice.turn)) continue
    seenTurns.add(slice.turn)

    const spec = processSpec(slice.input)
    if (spec === null) continue
    const presentation = derivePresentation(slice.input, spec)
    // Upstream's seat gate: the control exists from the first process
    // evidence but only shows once a closed turn has a final answer with
    // process rows outside the answer step.
    if (!presentation.turnClosed
      || spec.answerAnchorSeq === null
      || !presentation.hasExternalProcess) continue

    const anchorId = slice.userRow.kind === 'user' ? slice.userRow.id : `head-${slice.turn}`
    controlTurns[anchorId] = slice.turn
    counts[anchorId] = {
      toolCallCount: spec.toolCallCount,
      messageCount: spec.messageCount,
      subagentCount: spec.subagentCount,
    }

    for (const [seq, id] of slice.idsBySeq) {
      if (seq === spec.answerAnchorSeq) {
        roles[id] = 'answer'
        if (presentation.compactAnswer) compactAnswers.add(id)
        if (spec.inlineReasoning) inlineReasoningAnswers.add(id)
        continue
      }
      if (seq < spec.processStartSeq || seq > spec.answerAnchorSeq) continue
      roles[id] = 'member'
      memberTurns[id] = slice.turn
    }
  }

  return {
    roles,
    controlTurns,
    counts,
    memberTurns,
    expandedTurns,
    compactAnswers,
    inlineReasoningAnswers,
  }
}

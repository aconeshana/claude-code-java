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

/** One turn slice's contribution to the view, cached across renders. */
interface TurnContribution {
  readonly anchorId: string
  readonly turn: number
  readonly counts: TurnProcessCounts
  /** Row roles this slice claims, as (id, role) pairs. */
  readonly roles: readonly (readonly [string, RowRole])[]
  readonly memberIds: readonly string[]
  readonly compactAnswerId: string | null
  readonly inlineReasoningAnswerId: string | null
}

/**
 * Per-turn memo replacing upstream's `ChatTurnProcessProjector` cache.
 *
 * The reduction rebuilds the whole `messages` array on every streamed block
 * (a text delta rewrites the last row and therefore the array), so keying the
 * caller's `useMemo` on `messages` alone still re-specs every settled turn per
 * token. Settled rows do keep their object identity though, so a slice can be
 * fingerprinted by the identities of the rows it actually reads: an unchanged
 * fingerprint means the vendored rules would return exactly what they
 * returned last time.
 *
 * Bounded by construction: one entry per turn of the live conversation, and
 * the map is rebuilt (not appended to) on every derivation, so turns that
 * leave the transcript leave the cache with them.
 */
type TurnCache = Map<number, { readonly key: string; readonly value: TurnContribution | null }>

let lastCache: TurnCache = new Map()

/**
 * Identity fingerprint of the rows a slice reads.
 *
 * Row identity is the signal, not row content: the reducer replaces a row
 * object whenever it edits it. `tool.completed` maps over every assistant row
 * and rebuilds each one, so identity is conservative rather than exact — an
 * unrelated tool completion invalidates more turns than strictly necessary,
 * which costs a re-derive but can never serve a stale view.
 */
function sliceKey(
  messages: readonly MessageState[], start: number, end: number,
  turnRunning: boolean, isLastSlice: boolean,
): string {
  const ids: string[] = [String(start), String(end)]
  for (let seq = start; seq < end; seq++) ids.push(identityToken(messages[seq]))
  // The closed/open decision reads both flags, so they belong in the key.
  ids.push(turnRunning ? 'running' : 'idle', isLastSlice ? 'last' : 'mid')
  return ids.join('\u0000')
}

const identityTokens = new WeakMap<MessageState, string>()
let nextIdentityToken = 0

/** A stable per-object token, so row identity can live in a string key. */
function identityToken(message: MessageState): string {
  const existing = identityTokens.get(message)
  if (existing !== undefined) return existing
  nextIdentityToken += 1
  const token = `r${nextIdentityToken}`
  identityTokens.set(message, token)
  return token
}

/** Run the vendored rules over one slice, or null when it contributes nothing. */
function deriveContribution(slice: TurnSlice): TurnContribution | null {
  const spec = processSpec(slice.input)
  if (spec === null) return null
  const presentation = derivePresentation(slice.input, spec)
  // Upstream's seat gate: the control exists from the first process
  // evidence but only shows once a closed turn has a final answer with
  // process rows outside the answer step.
  if (!presentation.turnClosed
    || spec.answerAnchorSeq === null
    || !presentation.hasExternalProcess) return null

  const roles: (readonly [string, RowRole])[] = []
  const memberIds: string[] = []
  let compactAnswerId: string | null = null
  let inlineReasoningAnswerId: string | null = null

  for (const [seq, id] of slice.idsBySeq) {
    if (seq === spec.answerAnchorSeq) {
      roles.push([id, 'answer'])
      if (presentation.compactAnswer) compactAnswerId = id
      if (spec.inlineReasoning) inlineReasoningAnswerId = id
      continue
    }
    if (seq < spec.processStartSeq || seq > spec.answerAnchorSeq) continue
    roles.push([id, 'member'])
    memberIds.push(id)
  }

  return {
    anchorId: slice.userRow.kind === 'user' ? slice.userRow.id : `head-${slice.turn}`,
    turn: slice.turn,
    counts: {
      toolCallCount: spec.toolCallCount,
      messageCount: spec.messageCount,
      subagentCount: spec.subagentCount,
    },
    roles,
    memberIds,
    compactAnswerId,
    inlineReasoningAnswerId,
  }
}

/**
 * Derive the fold view over one conversation's reduced messages: slice the
 * transcript into turns, run the vendored rules over each, and key the
 * resulting roles back to message ids.
 *
 * Per-turn results are memoized on the identity of the rows each turn reads,
 * so a streaming delta re-runs the rules for the active turn only.
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
  // Rebuilt rather than mutated in place, so dropped turns drop their entries.
  const nextCache: TurnCache = new Map()

  for (let boundary = 0; boundary < boundaries.length; boundary++) {
    const start = boundaries[boundary]
    const end = boundary + 1 < boundaries.length ? boundaries[boundary + 1] : messages.length
    const isLastSlice = boundary + 1 === boundaries.length
    const slice = toTurnSlice(
      messages, start, end, conversation.turnRunning, isLastSlice,
    )
    if (slice === undefined) continue
    // The snapshot path back-fills turn numbers lazily; a turn number that
    // already produced its view is not re-derived from a later slice.
    if (seenTurns.has(slice.turn)) continue
    seenTurns.add(slice.turn)

    const key = sliceKey(messages, start, end, conversation.turnRunning, isLastSlice)
    const cached = lastCache.get(slice.turn)
    const contribution = cached !== undefined && cached.key === key
      ? cached.value
      : deriveContribution(slice)
    nextCache.set(slice.turn, { key, value: contribution })
    if (contribution === null) continue

    controlTurns[contribution.anchorId] = contribution.turn
    counts[contribution.anchorId] = contribution.counts
    for (const [id, role] of contribution.roles) roles[id] = role
    for (const id of contribution.memberIds) memberTurns[id] = contribution.turn
    if (contribution.compactAnswerId !== null) compactAnswers.add(contribution.compactAnswerId)
    if (contribution.inlineReasoningAnswerId !== null) {
      inlineReasoningAnswers.add(contribution.inlineReasoningAnswerId)
    }
  }

  lastCache = nextCache

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

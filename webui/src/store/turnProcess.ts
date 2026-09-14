/**
 * Turn-process disclosure state (mirrors dsh ui-chat's turn-process fold):
 * for a closed turn whose latest assistant message is a real answer (has
 * reply text, no tool calls), everything before that answer — thinking
 * blocks, tool rows, intermediate assistant messages — collapses behind one
 * summary control ("N 次工具调用 · N 条消息"). The answer itself and the
 * user's message stay visible.
 *
 * Upstream derives the membership per ChatNode from a TurnProcessSpec event
 * projection (turn-process.ts + turn-process-presentation.ts); this port
 * derives it from the reduced message list instead — same rules, simpler
 * substrate:
 * - an open (running) turn never folds;
 * - a closed turn with no qualifying answer keeps every row visible
 *   (upstream: "a closed Turn with no final answer keeps all process
 *   evidence visible");
 * - rows are hidden with `hidden="until-found"`, never unmounted, so browser
 *   find can reveal them (`beforematch` opens the group) and stateful tool
 *   renderers keep their state;
 * - manual expansion lives in this non-persisted store only (upstream keeps
 *   it in the session-scoped Chat store; absence means collapsed);
 * - transcriptView "normal" disables folding entirely.
 */
import { create } from 'zustand'
import type { ConversationState, MessageState } from './conversations'
import { useTranscriptView } from './transcriptView'

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

/** The turn number an assistant row belongs to, when known. */
function turnOf(message: MessageState): number | undefined {
  return message.kind === 'assistant' ? message.turn : undefined
}

/**
 * The index of the turn's final answer row: the LAST closed assistant
 * message in the turn that carries non-blank reply text and no tool calls
 * (upstream's latestAnswer — a tool-calling step is process, not answer).
 */
function answerIndex(messages: readonly MessageState[]): number | null {
  let answer: number | null = null
  for (let index = 0; index < messages.length; index++) {
    const message = messages[index]
    if (message.kind !== 'assistant' || message.open) continue
    if (message.textBlocks.some((text) => text.trim() !== '')
      && message.toolCalls.length === 0) {
      answer = index
    }
  }
  return answer
}

/**
 * One message row's fold classification inside its turn.
 * - `member`: hidden behind the control while the group is collapsed;
 * - `answer`: the visible answer row (gets the 8px follow-gap);
 * - `undefined`: independent (user rows, open turns, no-answer turns).
 */
export type RowRole = 'member' | 'answer'

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
}

/** The control's summary segments (upstream's three counts). */
export interface TurnProcessCounts {
  readonly toolCallCount: number
  readonly messageCount: number
  readonly subagentCount: number
}

/** Upstream's subagent delegation names: `subagent` and `subagent_*`. */
export function isSubagentDelegationTool(name: string): boolean {
  return name === 'subagent' || name.startsWith('subagent_')
}

function emptyView(): TurnProcessView {
  return { roles: {}, controlTurns: {}, counts: {}, memberTurns: {}, expandedTurns: new Set() }
}

/**
 * Derive the fold view over one conversation's reduced messages. Follows
 * upstream's eligibility: compact mode + a closed turn + a final answer
 * with process rows before it.
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

  // Slice the transcript into turns: each turn = a user row (or transcript
  // head) plus the assistant rows that follow it.
  const boundaries: number[] = [0]
  for (let index = 0; index < messages.length; index++) {
    if (messages[index].kind === 'user') boundaries.push(index)
  }

  const roles: Record<string, RowRole> = {}
  const controlTurns: Record<string, number> = {}
  const counts: Record<string, TurnProcessCounts> = {}
  const memberTurns: Record<string, number> = {}
  const seenTurns = new Set<number>()

  for (let boundary = 0; boundary < boundaries.length; boundary++) {
    const start = boundaries[boundary]
    const end = boundary + 1 < boundaries.length ? boundaries[boundary + 1] : messages.length
    const group = messages.slice(start, end)
    const userRow = group[0]
    const assistantRows = group.slice(1)
    if (assistantRows.length === 0) continue

    const turn = assistantRows.map(turnOf).find((candidate) => candidate !== undefined)
    if (turn === undefined) continue
    // A still-open turn never folds (upstream: an open Turn remains fully
    // expanded; a running row keeps its evidence visible).
    if (assistantRows.some((row) => row.kind === 'assistant' && row.open)) continue
    // The snapshot path back-fills turn numbers lazily; a turn number that
    // already produced its view is not re-derived from a later slice.
    if (seenTurns.has(turn)) continue
    seenTurns.add(turn)

    const answerLocal = answerIndex(assistantRows)
    if (answerLocal === null) continue
    const answer = assistantRows[answerLocal]
    if (answerLocal === 0) continue // answer-only turn: nothing to fold

    let toolCallCount = 0
    let messageCount = 0
    let subagentCount = 0
    for (let index = 0; index < answerLocal; index++) {
      const row = assistantRows[index]
      if (row.kind !== 'assistant') continue
      for (const call of row.toolCalls) {
        if (isSubagentDelegationTool(call.name)) subagentCount += 1
        else toolCallCount += 1
      }
      if (row.textBlocks.some((text) => text.trim() !== '')) messageCount += 1
    }
    // Pure-reasoning turns fold too (the "已思考" label case) — only a
    // zero-row group would render an empty control, which cannot happen
    // here because answerLocal > 0 guarantees at least one member row.

    if (userRow.kind === 'user') controlTurns[userRow.id] = turn
    else controlTurns[`head-${turn}`] = turn
    counts[userRow.kind === 'user' ? userRow.id : `head-${turn}`] = {
      toolCallCount, messageCount, subagentCount,
    }
    for (let index = 0; index < answerLocal; index++) {
      const row = assistantRows[index]
      if (row.kind === 'assistant') {
        roles[row.id] = 'member'
        memberTurns[row.id] = turn
      }
    }
    roles[answer.id] = 'answer'
  }

  return { roles, controlTurns, counts, memberTurns, expandedTurns }
}

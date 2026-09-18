/**
 * Vendored turn-process fold rules from dsh `ui-chat`, merged from four
 * upstream files (see `webui/UPSTREAM.md` for the provenance table):
 *
 * - `src/client/contract/turn-process.ts` — `TurnProcessSpec`,
 *   `TURN_PROCESS_INDEPENDENT_KINDS`, `sameTurnProcessSpec`,
 *   `isSubagentDelegationTool`
 * - `src/client/contract/assistant-content.ts` — `hasAssistantReplyContent`
 * - `src/client/conversation-nodes/turn-process.ts` — `latestAnswer`,
 *   `processSpec` (the eligibility and counting rules)
 * - `src/client/conversation-nodes/turn-process-presentation.ts` —
 *   `derivePresentation` (the cross-node layout facts)
 *
 * Cut from upstream: the cordis `ConversationNodeDefinition` (match/start/
 * update/publication/buildLocationData/buildViewNode), the event-stream
 * projection it reduces (`assistant/live-chunk`, `assistant/attempt`,
 * `step/start`, `llm/retry`), the Location-data store and its
 * reference-preserving equality gates, and the `ChatTurnProcessProjector`
 * class with its per-turn memo map. This app has no event registry: the
 * gateway's mirror frames reduce to a message list, so the rules run over
 * that list directly and React's `useMemo` replaces the projector's cache.
 *
 * Kept verbatim in substance: the answer boundary is the turn's LAST step and
 * only that step, `hasAssistantReplyContent`'s block predicate, the split
 * counting rules (messages before the answer step; tool calls across the whole
 * turn), `inlineReasoning`, the independent-kind exemption set, and
 * `derivePresentation`'s `hasExternalProcess` / `compactAnswer` derivation.
 *
 * Substrate mapping: upstream's monotonic event `seq` becomes the transcript
 * row index, and a Turn's `step` becomes the assistant row's ordinal inside
 * its turn. Both are monotonic within a turn, which is all the rules use them
 * for.
 */

/** One assistant step's blocks, in upstream's presentation-sorted union. */
export type AssistantBlock =
  | { readonly kind: 'text'; readonly text: string }
  | { readonly kind: 'reasoning'; readonly text: string }
  | { readonly kind: 'tool-call'; readonly callId: string; readonly name: string }

/**
 * One assistant step of a turn: upstream's `assistant-step` Chat Node data
 * reduced to what the rules read.
 */
export interface AssistantStep {
  /** Transcript row index — upstream's event `seq`. */
  readonly seq: number
  /** Ordinal inside the turn — upstream's `step`. */
  readonly step: number
  readonly blocks: readonly AssistantBlock[]
  /**
   * Whether this step is finalized. Upstream reads `finalNode !== undefined`
   * (a durable `assistant/message`); a still-streaming step is not an answer.
   */
  readonly complete: boolean
}

/** A non-assistant row that participates in the turn's layout. */
export interface TurnMemberRow {
  readonly seq: number
  readonly kind: TurnRowKind
}

/**
 * Upstream Chat Node kinds this app projects. `system-prompt`,
 * `turn-max-tokens` and `model-retry` have no counterpart in the gateway's
 * frame catalog yet; the names stay so the exemption set below reads against
 * upstream's list unchanged.
 */
export type TurnRowKind =
  | 'assistant-step' | 'user' | 'steering' | 'context' | 'tool'
  | 'system-prompt' | 'turn-process' | 'turn-error' | 'turn-max-tokens' | 'turn-tail'

const TURN_PROCESS_INDEPENDENT_KIND_LIST = [
  'system-prompt',
  'user',
  'steering',
  'turn-process',
  'turn-error',
  'turn-max-tokens',
  'turn-tail',
] as const satisfies readonly TurnRowKind[]

/** Chat row kinds that remain independent of a Turn's process disclosure. */
export const TURN_PROCESS_INDEPENDENT_KINDS: ReadonlySet<string> = new Set(
  TURN_PROCESS_INDEPENDENT_KIND_LIST,
)

/** Current process range and finalized answer boundary derived from one Turn. */
export interface TurnProcessSpec {
  readonly turn: number
  /** Stable control-row anchor source, including currently ineligible evidence. */
  readonly controlAnchorSeq: number
  readonly processStartSeq: number
  readonly answerAnchorSeq: number | null
  readonly answerStep: number | null
  readonly inlineReasoning: boolean
  /** Reply-bearing durable assistant steps before the final answer. */
  readonly messageCount: number
  /** Durable non-subagent tool calls recorded by this Turn. */
  readonly toolCallCount: number
  /** Tool calls whose configured name identifies a subagent delegation. */
  readonly subagentCount: number
}

/**
 * Compare immutable Turn-process specifications by their published fields.
 * @param left - previous specification.
 * @param right - next specification.
 * @returns whether both values describe the same process presentation.
 */
export function sameTurnProcessSpec(left: TurnProcessSpec, right: TurnProcessSpec): boolean {
  return left.turn === right.turn
    && left.controlAnchorSeq === right.controlAnchorSeq
    && left.processStartSeq === right.processStartSeq
    && left.answerAnchorSeq === right.answerAnchorSeq
    && left.answerStep === right.answerStep
    && left.inlineReasoning === right.inlineReasoning
    && left.messageCount === right.messageCount
    && left.toolCallCount === right.toolCallCount
    && left.subagentCount === right.subagentCount
}

/**
 * Recognize the subagent delegation name and its configured variants.
 *
 * Deviation from upstream, which ships `subagent` / `subagent_*`: this
 * product's delegation tool registers as `Agent` with the `Task` alias
 * (`claude-code-tools` `AgentTool`), and its control-plane siblings
 * (`SendMessage`, `TaskList`) carry unrelated names, so the same
 * "delegation vs control tool" split holds under different literals.
 * @param name - durable tool-call name.
 * @returns whether the call creates or forks a subagent.
 */
export function isSubagentDelegationTool(name: string): boolean {
  return name === 'Agent' || name === 'Task'
}

/**
 * Test whether assistant blocks contain a user-facing reply rather than only
 * reasoning or tool-call protocol material.
 * @param blocks - assistant content blocks.
 * @returns whether the blocks contain visible reply content.
 */
export function hasAssistantReplyContent(blocks: readonly AssistantBlock[]): boolean {
  return blocks.some((block) => {
    if (block.kind === 'reasoning' || block.kind === 'tool-call') return false
    if (block.kind === 'text') return block.text.trim() !== ''
    return true
  })
}

/** One turn's rows, in transcript order — upstream's `TurnLocation`. */
export interface TurnInput {
  readonly turn: number
  /** Assistant steps in order; the LAST one is the answer candidate. */
  readonly steps: readonly AssistantStep[]
  /** Every non-assistant row of the turn (context injections, tool rows…). */
  readonly rows: readonly TurnMemberRow[]
  /** The turn's opening row seq — upstream's `turn.start.seq`. */
  readonly startSeq: number | undefined
  /** Whether the turn reached `turn/end`. An open turn never folds. */
  readonly closed: boolean
}

/**
 * The turn's finalized answer, or null.
 *
 * Upstream reads the LATEST step only: an earlier reply-bearing step is
 * process, not a fallback answer, so a turn whose last step is reasoning-only
 * or tool-calling keeps every process row visible.
 */
function latestAnswer(turn: TurnInput): AssistantStep | null {
  const latestStep = turn.steps.at(-1)
  if (latestStep === undefined) return null
  if (!latestStep.complete || !hasAssistantReplyContent(latestStep.blocks)) return null
  return latestStep.blocks.some((block) => block.kind === 'tool-call') ? null : latestStep
}

/**
 * Derive one turn's immutable process specification, or null when the turn has
 * produced no process evidence to anchor a control on.
 * @param turn - the turn's rows in transcript order.
 * @returns the specification, or null when no control anchor exists.
 */
export function processSpec(turn: TurnInput): TurnProcessSpec | null {
  const evidence = processEvidenceSeqs(turn)
  if (evidence.controlAnchorSeq === undefined) return null
  const answer = latestAnswer(turn)

  let toolCallCount = 0
  let subagentCount = 0
  // Upstream counts tool calls across the WHOLE turn (state.toolCallCount is
  // published unfiltered), unlike the message count below.
  for (const step of turn.steps) {
    for (const block of step.blocks) {
      if (block.kind !== 'tool-call') continue
      if (isSubagentDelegationTool(block.name)) subagentCount += 1
      else toolCallCount += 1
    }
  }
  const messageCount = turn.steps.filter((step) => step.complete
    && hasAssistantReplyContent(step.blocks)
    && (answer === null || step.step < answer.step)).length
  const counts = { messageCount, toolCallCount, subagentCount }

  if (answer === null) {
    return {
      turn: turn.turn,
      controlAnchorSeq: evidence.controlAnchorSeq,
      processStartSeq: evidence.controlAnchorSeq,
      answerAnchorSeq: null,
      answerStep: null,
      inlineReasoning: false,
      ...counts,
    }
  }
  const inlineReasoning = answer.blocks.some(
    (block) => block.kind === 'reasoning' && block.text.trim() !== '',
  )
  const earlierAssistantSeq = Math.min(
    ...turn.steps.filter((step) => step.step < answer.step).map((step) => step.seq),
  )
  const externalProcessSeq = Math.min(
    evidence.otherStartSeq ?? Number.POSITIVE_INFINITY,
    earlierAssistantSeq,
  )
  return {
    turn: turn.turn,
    controlAnchorSeq: evidence.controlAnchorSeq,
    processStartSeq: turn.startSeq
      ?? (Number.isFinite(externalProcessSeq) ? externalProcessSeq : answer.seq),
    answerAnchorSeq: answer.seq,
    answerStep: answer.step,
    inlineReasoning,
    ...counts,
  }
}

/**
 * The turn's first process evidence, split by kind — upstream's
 * `updateProcessState` reduction over `assistant/*` versus `tool/*` +
 * `llm/retry` events, with the control anchor as the minimum of both.
 */
function processEvidenceSeqs(turn: TurnInput): {
  controlAnchorSeq: number | undefined
  otherStartSeq: number | undefined
} {
  const visibleSteps = turn.steps.filter((step) => hasVisibleContent(step.blocks))
  const otherRows = turn.rows.filter((row) => !TURN_PROCESS_INDEPENDENT_KINDS.has(row.kind))
  const assistantSeq = visibleSteps.length === 0
    ? undefined
    : Math.min(...visibleSteps.map((step) => step.seq))
  const otherStartSeq = otherRows.length === 0
    ? undefined
    : Math.min(...otherRows.map((row) => row.seq))
  const anchors = [assistantSeq, otherStartSeq].filter((seq) => seq !== undefined)
  return {
    controlAnchorSeq: anchors.length === 0 ? undefined : Math.min(...anchors),
    otherStartSeq,
  }
}

/**
 * Upstream's `visibleAssistantEvent`: a step counts as evidence when it
 * carries non-blank text/reasoning or any non-tool-call block.
 */
function hasVisibleContent(blocks: readonly AssistantBlock[]): boolean {
  return blocks.some((block) => {
    if (block.kind === 'tool-call') return false
    return block.text.trim() !== ''
  })
}

/** Cross-row process layout facts for one turn. */
export interface TurnProcessPresentation {
  readonly turn: number
  readonly spec: TurnProcessSpec
  readonly turnClosed: boolean
  /** Whether any process row outside the answer step exists to fold. */
  readonly hasExternalProcess: boolean
  /** Whether the collapsed control sits directly against the answer (8px gap). */
  readonly compactAnswer: boolean
}

/**
 * Derive the turn's cross-row presentation from its spec and row set.
 * @param turn - the turn's rows in transcript order.
 * @param spec - the turn's process specification.
 * @returns the presentation facts the seats read.
 */
export function derivePresentation(
  turn: TurnInput,
  spec: TurnProcessSpec,
): TurnProcessPresentation {
  let openingHumanAnchor: number | undefined
  for (const row of turn.rows) {
    if ((row.kind === 'user' || row.kind === 'steering') && row.seq < spec.controlAnchorSeq) {
      openingHumanAnchor = Math.min(openingHumanAnchor ?? row.seq, row.seq)
    }
  }

  let hasExternalProcess = false
  let compactAnswer = true
  for (const row of turn.rows) {
    if (row.kind === 'turn-process') continue
    if ((row.kind === 'user' || row.kind === 'steering')
      && (openingHumanAnchor === undefined || row.seq > openingHumanAnchor)
      && (spec.answerAnchorSeq === null || row.seq < spec.answerAnchorSeq)) {
      compactAnswer = false
    }
    if (TURN_PROCESS_INDEPENDENT_KINDS.has(row.kind)
      || row.seq < spec.processStartSeq
      || (spec.answerAnchorSeq !== null && row.seq >= spec.answerAnchorSeq)) continue
    hasExternalProcess = true
  }
  for (const step of turn.steps) {
    if (step.seq < spec.processStartSeq
      || (spec.answerAnchorSeq !== null && step.seq >= spec.answerAnchorSeq)) continue
    if (spec.answerStep === null || step.step !== spec.answerStep) hasExternalProcess = true
  }
  return {
    turn: turn.turn,
    spec,
    turnClosed: turn.closed,
    hasExternalProcess,
    compactAnswer,
  }
}

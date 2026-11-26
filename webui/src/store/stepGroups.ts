/**
 * Step-level process grouping over the reduced transcript (`MessageState[]`),
 * adapted from upstream deepseek-harness `conversation-nodes/process-groups.ts`.
 *
 * Consecutive thinking and tool-call work between independent user messages or
 * assistant text replies is segmented into one `StepProcessGroupEntry` so long
 * multi-tool runs (including live/running turns and turns that end on tools)
 * collapse to one header line in compact mode and expand inside a capped
 * scrolling container (`max-height: min(400px, 50vh)`).
 */
import type { MessageState } from './conversations'
import {
  summarizeProcessSteps,
  type ProcessActivitySummary,
} from '@vendor/dsh-step-process/stepProcess'

export type AssistantMessageRow = Extract<MessageState, { kind: 'assistant' }>

export interface StepGroupItem {
  readonly message: AssistantMessageRow
  readonly part: 'process' | 'reasoning' | 'tools'
  readonly index: number
}

export interface StepProcessGroupEntry {
  readonly kind: 'step-group'
  readonly key: string
  readonly turn?: number | undefined
  readonly closed: boolean
  readonly summary: ProcessActivitySummary
  readonly items: readonly StepGroupItem[]
}

export interface FlowMessageEntry {
  readonly kind: 'message'
  readonly key: string
  readonly message: MessageState
  readonly part: 'all' | 'response'
  readonly index: number
}

export type ChatFlowEntry = FlowMessageEntry | StepProcessGroupEntry

function hasNonBlankText(blocks: readonly string[]): boolean {
  return blocks.some((text) => text.trim() !== '')
}

/**
 * Segment a conversation's rendered messages into standalone messages and
 * grouped step-process runs.
 *
 * `inlineReasoningAnswerIds` names final-answer rows whose thinking is owned by
 * the outer `TurnProcessControl` (`hideReasoning`); those rows stay single
 * `part: 'all'` entries so the outer fold remains the single source of truth.
 */
export function buildChatFlowEntries(
  messages: readonly MessageState[],
  turnRunning: boolean,
  inlineReasoningAnswerIds: ReadonlySet<string> = new Set(),
): readonly ChatFlowEntry[] {
  const entries: ChatFlowEntry[] = []
  let pending: StepGroupItem[] = []

  const flush = (followedByBoundary: boolean): void => {
    if (pending.length === 0) return
    const first = pending[0]!
    const hasRunningStep = pending.some(
      (item) => (item.part !== 'tools' && item.message.open)
        || (item.part !== 'reasoning' && item.message.toolCalls.some((call) => call.status === 'pending')),
    )
    const closed = followedByBoundary || (!turnRunning && !hasRunningStep)
    const summary = summarizeProcessSteps(
      pending.map((item) => ({
        thinkingBlocks: item.part === 'tools' ? [] : item.message.thinkingBlocks,
        toolCalls: item.part === 'reasoning' ? [] : item.message.toolCalls,
        open: item.part !== 'tools' && item.message.open,
        synthetic: item.message.synthetic,
      })),
    )
    const turn = pending.find((item) => item.message.turn !== undefined)?.message.turn
    entries.push({
      kind: 'step-group',
      key: `step-group:${first.message.id}:${first.part}`,
      turn,
      closed,
      summary,
      items: pending,
    })
    pending = []
  }

  for (let index = 0; index < messages.length; index++) {
    const message = messages[index]!
    if (message.kind === 'user' || message.synthetic === true) {
      flush(true)
      entries.push({ kind: 'message', key: message.id, message, part: 'all', index })
      continue
    }

    const hasThinking = hasNonBlankText(message.thinkingBlocks)
    const hasTools = message.toolCalls.length > 0
    const hasReply = hasNonBlankText(message.textBlocks)

    // Final answer row whose only process is inline reasoning owned by TurnProcessControl.
    if (!hasTools && inlineReasoningAnswerIds.has(message.id)) {
      flush(true)
      entries.push({ kind: 'message', key: message.id, message, part: 'all', index })
      continue
    }

    if ((hasThinking || hasTools) && !hasReply) {
      pending.push({ message, part: 'process', index })
      continue
    }

    if (!hasThinking && !hasTools) {
      flush(true)
      entries.push({ kind: 'message', key: message.id, message, part: 'all', index })
      continue
    }

    // Both process and reply exist on the same assistant row.
    // Upstream `process-groups.ts`: reasoning joins the current process group,
    // reply flushes and stands outside, and trailing tool calls start the next group.
    if (hasThinking) {
      pending.push({ message, part: 'reasoning', index })
    }
    flush(true)
    entries.push({ kind: 'message', key: `${message.id}:response`, message, part: 'response', index })
    if (hasTools) {
      pending.push({ message, part: 'tools', index })
    }
  }

  flush(false)
  return entries
}

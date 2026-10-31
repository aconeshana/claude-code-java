import type { MessageState } from './conversations'

/**
 * The block the model is streaming right now, built from the mirror's
 * transient output.text.delta / output.thinking.delta frames (and seeded from
 * the snapshot's `streaming` field). It is a rendering draft only: the
 * block's settled output.text / output.thinking frame replaces it.
 */
export interface StreamingDraft {
  readonly kind: 'text' | 'thinking'
  readonly content: string
}

/**
 * Folds one delta into the draft by its offset (UTF-16 code units — the
 * gateway's StringBuilder length, the same unit as a JS string length).
 *
 * - offset equals the draft length: append.
 * - offset below it: the stream restarted the block (a retry or a streaming
 *   fallback re-streams from 0); truncate to the offset, then append.
 * - offset beyond it: deltas were missed (the snapshot/subscribe window, a
 *   dropped connection); the draft keeps its last good content until the
 *   settled block replaces it. Returns the input draft unchanged.
 *
 * A delta of the other kind starts a fresh draft — only from offset 0.
 */
export function mergeDelta(
  draft: StreamingDraft | undefined,
  kind: StreamingDraft['kind'],
  content: string,
  offset: number,
): StreamingDraft | undefined {
  const base = draft?.kind === kind ? draft.content : ''
  if (!Number.isInteger(offset) || offset < 0 || offset > base.length) return draft
  return { kind, content: base.slice(0, offset) + content }
}

/**
 * The transcript rows with the draft rendered in place.
 *
 * Delta frames carry no step identity, so the draft is placed where its
 * settled block will land: a step streams its blocks thinking → text →
 * tool_use, so an open assistant row with no tool call yet is still the
 * streaming step and the draft joins it as its trailing block — the settled
 * block then takes the same index, and the row's markdown keeps its instance.
 * Otherwise (a tool step closed the row, or the turn has only its prompt so
 * far) the draft is a step of its own and stands in a fresh open row.
 */
export function withStreamingDraft(
  messages: readonly MessageState[],
  draft: StreamingDraft | undefined,
): readonly MessageState[] {
  if (draft == null || draft.content === '') return messages
  const last = messages[messages.length - 1]
  if (last != null && last.kind === 'assistant' && last.open && last.toolCalls.length === 0
    && last.synthetic !== true) {
    return [...messages.slice(0, -1), draft.kind === 'text'
      ? { ...last, textBlocks: [...last.textBlocks, draft.content] }
      : { ...last, thinkingBlocks: [...last.thinkingBlocks, draft.content] }]
  }
  return [...messages, {
    kind: 'assistant',
    id: 'streaming',
    textBlocks: draft.kind === 'text' ? [draft.content] : [],
    thinkingBlocks: draft.kind === 'thinking' ? [draft.content] : [],
    toolCalls: [],
    open: true,
  }]
}

import { useMemo } from 'react'
import { MarkdownText } from '@vendor/ui-primitives/markdown/MarkdownText'
import type { MarkdownLabels } from '@vendor/ui-primitives/markdown/MarkdownText'
import css from '@chat-styles/MessageItem.module.css'
import type { MessageState } from '../store/conversations'
import { useTranscriptView } from '../store/transcriptView'
import { useTranslate } from '../i18n/useTranslate'
import { STATS_NS, statsDicts } from '../i18n/dictionaries/stats'
import { markdownLabels } from './labels'
import { DeliverablesRow } from './DeliverablesRow'
import { ReasoningRow } from './ReasoningRow'
import { SubagentRow, isSubagentCall } from './SubagentRow'
import { ToolCallRow } from './ToolCallRow'
import { useSessions } from '../store/sessions'
import { ContextJumpButton } from './context/ContextJumpButton'
import { ProducedFiles } from './ProducedFiles'
import { useChangesViewer } from '../store/changesViewer'
import { DELIVERABLES_NS, deliverablesDicts } from '../i18n/dictionaries/deliverables'
import { MessageIconActions } from '../../vendor/dsh-stats-pills/MessageIconActions'
import { TurnTimePanel, TurnUsagePanel } from '../../vendor/dsh-stats-pills/TurnUsagePanel'
import { ImageGallery } from '../../vendor/ui-attachment/MessageImages'
import turnTailCss from '@chat-styles/TurnTailNodeView.module.css'

/**
 * One transcript row: the user bubble (right-aligned, vendored dsh shape)
 * or the assistant stack (thinking disclosure + markdown + tool call rows)
 * plus the vendored dsh chrome rows — copy actions with a date-aware clock
 * under every user bubble, and the full turn-tail icon row (copy + the
 * vendored dsh-context Chat→Context jump + usage pills + trailing clock) on
 * the settled turn's anchor assistant row, passed in as `showTail`. Upstream
 * renders that row from a per-turn `turn-tail` node, so exactly one assistant
 * row of each turn carries it — never one per step.
 */
export function MessageItem({ message, hideReasoning = false, showTail = false,
  producedFiles = [], sessionId = null, inSubagentTranscript = false }: {
  message: MessageState
  /**
   * The turn-process fold's `inlineReasoning`: this row is a folded turn's
   * answer, so its own thinking blocks belong to the process group and stay
   * hidden while the reply stays visible.
   */
  hideReasoning?: boolean
  /**
   * Whether this assistant row is its turn's tail anchor — the ONE row that
   * renders the turn tail (copy + usage/time pills + clock).
   *
   * The gateway stamps `turn_usage`/`time` on every assistant row of a turn
   * (the same fact the TUI transcript row carries), so a per-row presence
   * test would fan the tail out N times for an N-step turn. Upstream renders
   * it from a separate per-turn `turn-tail` node instead; the fold view's
   * `tailAnchors` reproduces that anchor decision here.
   */
  showTail?: boolean
  /**
   * The files this row's turn produced, aggregated over the whole turn — the
   * vendored delivered-files chip row renders them on the tail anchor only.
   */
  producedFiles?: readonly string[]
  /** The addressed session, for the viewer the chips open. */
  sessionId?: string | null
  /**
   * This row belongs to a sub-agent's restored transcript, not the chat.
   *
   * The restored transcript is rendered with this same component (its rows
   * come off the wire reduced by the same `toMessageState`), but a nested
   * delegation inside it must render as a plain tool row: a sub-agents'
   * sub-agent has its own sidechain file, and this row's id addresses the
   * parent's session, so letting it expand would fetch the wrong transcript.
   */
  inSubagentTranscript?: boolean
}) {
  const statsT = useTranslate(STATS_NS, statsDicts)
  if (message.kind === 'user') {
    return (
      <div className={css.userRow}>
        <div className={css.userStack}>
          {message.images !== undefined && message.images.length > 0 && (
            <ImageGallery images={message.images} />
          )}
          {message.text !== '' && <div className={css.bubble}>{message.text}</div>}
        </div>
        <MessageIconActions
          text={message.text}
          time={message.time}
          clock="start"
          t={statsT}
        />
      </div>
    )
  }
  return (
    <AssistantItem
      message={message}
      t={statsT}
      hideReasoning={hideReasoning}
      showTail={showTail}
      producedFiles={producedFiles}
      sessionId={sessionId}
      inSubagentTranscript={inSubagentTranscript}
    />
  )
}

function AssistantItem({ message, t, hideReasoning, showTail, producedFiles, sessionId,
  inSubagentTranscript }: {
  message: Extract<MessageState, { kind: 'assistant' }>
  t: ReturnType<typeof useTranslate>
  hideReasoning: boolean
  showTail: boolean
  producedFiles: readonly string[]
  sessionId: string | null
  inSubagentTranscript: boolean
}) {
  const defaultOpen = useTranscriptView((state) => state.mode === 'normal')
  // One stable labels identity: MarkdownText's streaming cache resets when
  // the object identity changes.
  const labels = useMemo<MarkdownLabels>(() => markdownLabels, [])
  const plainText = useMemo(
    () => message.textBlocks.join('\n\n'),
    [message.textBlocks],
  )
  const selectedSessionId = useSessions((state) => state.selectedSessionId)
  const addressedSessionId = sessionId ?? selectedSessionId
  const deliverablesT = useTranslate(DELIVERABLES_NS, deliverablesDicts)
  const openDiff = useChangesViewer((state) => state.open)

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8, minWidth: 0 }}>
      {!hideReasoning && message.thinkingBlocks.map((text: string, index: number) => (
        <ReasoningRow
          key={index}
          text={text}
          running={message.open && index === message.thinkingBlocks.length - 1}
        />
      ))}
      {message.textBlocks.map((text: string, index: number) => (
        <MarkdownText
          key={index}
          text={text}
          streaming={message.open && index === message.textBlocks.length - 1}
          labels={labels}
        />
      ))}
      {message.toolCalls.map((call) => (
        !inSubagentTranscript && isSubagentCall(call)
          ? (
            <SubagentRow
              key={call.toolUseId}
              call={call}
              sessionId={addressedSessionId}
              defaultOpen={defaultOpen}
            />
          )
          : <ToolCallRow key={call.toolUseId} call={call} defaultOpen={defaultOpen} />
      ))}
      {/* The per-message chip row below this row's own tool calls (upstream's
          per-step produced list). Kept as-is; the turn-wide row above the tail
          is the vendored ui-deliverables one. */}
      <DeliverablesRow toolCalls={message.toolCalls} />
      {/* The delivered-files row — vendored from upstream ui-deliverables and
          mounted on the turn's tail anchor, the one row that carries the tail.
          Upstream seats it at `conversation.chat.turnTail`; this project has no
          such hole, so the anchor renders it with the paths aggregated over the
          whole turn (see ChatView.producedByTurn). */}
      {showTail && !message.open && producedFiles.length > 0 && (
        <ProducedFiles
          paths={producedFiles}
          openFile={(path) => {
            if (addressedSessionId != null) openDiff(addressedSessionId, path)
          }}
          t={deliverablesT}
        />
      )}
      {/* The settled turn tail: the full vendored icon row — copy action,
          then the usage pill (provider buckets) and the time pill (turn
          wall time), then the trailing clock. Exactly one assistant row of
          each turn renders it (the fold view's `tailAnchors`), because the
          gateway stamps the turn facts on every row; a running turn has no
          anchor yet, and an anchor row that carries no facts shows no row
          (upstream facts-absent semantics). */}
      {showTail && !message.open && (message.turnUsage !== undefined
        || message.runMs !== undefined || message.time !== undefined) && (
        <MessageIconActions
          text={plainText}
          time={message.time}
          clock="end"
          className={turnTailCss.actions}
          usageAction={(
            <>
              <ContextJumpButton sessionId={addressedSessionId} time={message.time} turn={message.turn} />
              {message.turnUsage !== undefined && (
                <TurnUsagePanel usage={message.turnUsage} t={t} />
              )}
              {message.runMs !== undefined && (
                <TurnTimePanel
                  runMs={message.runMs}
                  // Turn decode throughput: output tokens over the turn wall
                  // time (upstream computes it over decode-timed steps; this
                  // gateway reports one wall clock for the whole turn, so the
                  // reading includes tool time — the closest available fact).
                  tokensPerSecond={message.turnUsage !== undefined
                    && message.turnUsage.output_tokens > 0 && message.runMs > 0
                    ? message.turnUsage.output_tokens / (message.runMs / 1_000)
                    : undefined}
                  ttftMs={message.ttftMs}
                  t={t}
                />
              )}
            </>
          )}
          t={t}
        />
      )}
    </div>
  )
}

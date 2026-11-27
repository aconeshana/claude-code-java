import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Button,
  IconBranchOutline16,
  IconEditOutline16,
  IconRefreshOutline16,
  Tooltip,
} from '@primitives'
import { MarkdownText } from '@vendor/ui-primitives/markdown/MarkdownText'
import type { MarkdownLabels } from '@vendor/ui-primitives/markdown/MarkdownText'
import css from '@chat-styles/MessageItem.module.css'
import actionCss from '@chat-styles/MessageIconActions.module.css'
import { forkSession, submitTurn } from '../api/client'
import { toast } from '../store/toast'
import type { MessageState } from '../store/conversations'
import { useTranscriptView } from '../store/transcriptView'
import { useTranslate } from '../i18n/useTranslate'
import { STATS_NS, statsDicts } from '../i18n/dictionaries/stats'
import { markdownLabels } from './labels'
import { DeliverablesRow } from './DeliverablesRow'
import { ReasoningRow } from './ReasoningRow'
import { RewindConfirmModal } from './RewindConfirmModal'
import { SubagentRow, isSubagentCall } from './SubagentRow'
import { ToolCallRow } from './ToolCallRow'
import { useSessions } from '../store/sessions'
import { ContextJumpButton } from './context/ContextJumpButton'
import { ChangedFiles } from '@vendor/ui-deliverables/ChangedFiles'
import { changesDiffAccess, changesSummaryStore, useChangesSummary } from '../store/changes'
import { useReviewSeat } from '../store/review'
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
  changesTurn, sessionId = null, inSubagentTranscript = false, retryPrompt, part = 'all' }: {
  message: MessageState
  hideReasoning?: boolean
  showTail?: boolean
  changesTurn?: number | undefined
  sessionId?: string | null
  inSubagentTranscript?: boolean
  /** Preceding user prompt text for the assistant turn's Retry action. */
  retryPrompt?: string | undefined
  /** When split across a step-process group: `process`/`reasoning`/`tools` render process slices, `response` renders prose/tail. */
  part?: 'all' | 'process' | 'reasoning' | 'tools' | 'response'
}) {
  const statsT = useTranslate(STATS_NS, statsDicts)
  if (message.kind === 'user') {
    return (
      <UserItem
        message={message}
        t={statsT}
        sessionId={sessionId}
        inSubagentTranscript={inSubagentTranscript}
      />
    )
  }
  return (
    <AssistantItem
      message={message}
      t={statsT}
      hideReasoning={hideReasoning}
      showTail={showTail}
      changesTurn={changesTurn}
      sessionId={sessionId}
      inSubagentTranscript={inSubagentTranscript}
      retryPrompt={retryPrompt}
      part={part}
    />
  )
}

function UserItem({
  message,
  t,
  sessionId,
  inSubagentTranscript,
}: {
  message: Extract<MessageState, { kind: 'user' }>
  t: ReturnType<typeof useTranslate>
  sessionId: string | null
  inSubagentTranscript: boolean
}) {
  const selectedSessionId = useSessions((state) => state.selectedSessionId)
  const addressedSessionId = sessionId ?? selectedSessionId
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState(message.text)
  const [rewindModalOpen, setRewindModalOpen] = useState(false)

  const openRewindConfirm = useCallback(() => {
    const trimmed = draft.trim()
    if (trimmed === '' || addressedSessionId == null) return
    setRewindModalOpen(true)
  }, [draft, addressedSessionId])

  return (
    <div className={css.userRow}>
      <div className={css.userStack}>
        {message.images !== undefined && message.images.length > 0 && (
          <ImageGallery images={message.images} />
        )}
        {editing ? (
          <div
            style={{
              display: 'flex',
              flexDirection: 'column',
              gap: 10,
              width: '100%',
              maxWidth: 540,
              minWidth: 'min(320px, 100%)',
              padding: '12px 14px 10px',
              borderRadius: 16,
              background: 'var(--dsw-alias-bg-layer-1, #ffffff)',
              border: '1px solid var(--dsw-alias-border-l2, rgba(0, 0, 0, 0.12))',
              boxShadow: '0 4px 18px rgba(0, 0, 0, 0.06)',
              boxSizing: 'border-box',
            }}
          >
            <textarea
              value={draft}
              rows={Math.min(8, Math.max(2, draft.split('\n').length))}
              autoFocus
              onChange={(e) => { setDraft(e.target.value) }}
              onKeyDown={(e) => {
                if (e.key === 'Escape') {
                  setEditing(false)
                  setDraft(message.text)
                } else if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
                  e.preventDefault()
                  openRewindConfirm()
                }
              }}
              style={{
                boxSizing: 'border-box',
                width: '100%',
                minHeight: 44,
                maxHeight: 220,
                padding: 0,
                resize: 'none',
                border: 'none',
                outline: 'none',
                background: 'transparent',
                color: 'var(--dsw-alias-label-primary)',
                font: 'inherit',
                fontSize: 13.5,
                lineHeight: 1.55,
              }}
            />
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12 }}>
              <span style={{ fontSize: 11.5, color: 'var(--dsw-alias-label-tertiary)', userSelect: 'none' }}>
                Enter 继续 · Esc 取消
              </span>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => {
                    setEditing(false)
                    setDraft(message.text)
                  }}
                >
                  取消
                </Button>
                <Button
                  variant="primary"
                  size="sm"
                  disabled={draft.trim() === ''}
                  onClick={openRewindConfirm}
                  data-testid="user-edit-continue"
                >
                  继续
                </Button>
              </div>
            </div>
          </div>
        ) : (
          message.text !== '' && <div className={css.bubble}>{message.text}</div>
        )}
        {addressedSessionId != null && (
          <RewindConfirmModal
            open={rewindModalOpen}
            sessionId={addressedSessionId}
            messageId={message.id}
            originalText={message.text}
            editedText={draft.trim()}
            onClose={() => { setRewindModalOpen(false) }}
            onCompleted={() => {
              setRewindModalOpen(false)
              setEditing(false)
            }}
          />
        )}
      </div>
      <MessageIconActions
        text={message.text}
        time={message.time}
        clock="start"
        usageAction={
          !inSubagentTranscript && addressedSessionId != null && message.text !== '' ? (
            <Tooltip label="编辑并重新发送" side="bottom">
              <button
                type="button"
                className={actionCss.action}
                aria-label="编辑并重新发送"
                data-message-action="edit-resend"
                onClick={() => {
                  setDraft(message.text)
                  setEditing(true)
                }}
              >
                <IconEditOutline16 />
              </button>
            </Tooltip>
          ) : null
        }
        t={t}
      />
    </div>
  )
}

function AssistantItem({ message, t, hideReasoning, showTail, changesTurn, sessionId,
  inSubagentTranscript, retryPrompt, part = 'all' }: {
  message: Extract<MessageState, { kind: 'assistant' }>
  t: ReturnType<typeof useTranslate>
  hideReasoning: boolean
  showTail: boolean
  changesTurn: number | undefined
  sessionId: string | null
  inSubagentTranscript: boolean
  retryPrompt?: string | undefined
  part?: 'all' | 'process' | 'reasoning' | 'tools' | 'response'
}) {
  const showReasoning = part === 'all' || part === 'process' || part === 'reasoning'
  const showTools = part === 'all' || part === 'process' || part === 'tools'
  const showResponse = part === 'all' || part === 'response'
  const defaultOpen = useTranscriptView((state) => state.mode === 'normal')
  const [forking, setForking] = useState(false)
  const [retrying, setRetrying] = useState(false)
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
  const openReview = useReviewSeat((state) => state.openReview)

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8, minWidth: 0 }}>
      {showReasoning && !hideReasoning && message.thinkingBlocks.map((text: string, index: number) => (
        <ReasoningRow
          key={index}
          text={text}
          running={message.open && index === message.thinkingBlocks.length - 1}
          defaultOpen={defaultOpen}
        />
      ))}
      {showResponse && message.textBlocks.map((text: string, index: number) => (
        <MarkdownText
          key={index}
          text={text}
          streaming={message.open && index === message.textBlocks.length - 1}
          labels={labels}
        />
      ))}
      {showTools && message.toolCalls.map((call) => (
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
      {showTools && <DeliverablesRow toolCalls={message.toolCalls} />}
      {/* The delivered-files row — vendored from upstream ui-deliverables and
          mounted on the turn's tail anchor, the one row that carries the tail.
          Upstream seats it at `conversation.chat.turnTail`; this project has no
          such hole, so the anchor renders it with the paths aggregated over the
          whole turn (see ChatView.producedByTurn). */}
      {showResponse && showTail && !message.open && changesTurn !== undefined && addressedSessionId != null && (
        <ChangedFilesCard
          sessionId={addressedSessionId}
          turn={changesTurn}
          openReview={(index) => { openReview(addressedSessionId, changesTurn, index) }}
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
      {showResponse && showTail && !message.open && (message.turnUsage !== undefined
        || message.runMs !== undefined || message.time !== undefined) && (
        <MessageIconActions
          text={plainText}
          time={message.time}
          clock="end"
          className={turnTailCss.actions}
          usageAction={(
            <>
              {!inSubagentTranscript && addressedSessionId != null && retryPrompt !== undefined && (
                <Tooltip label="重新生成" side="bottom">
                  <button
                    type="button"
                    className={actionCss.action}
                    aria-label="重新生成"
                    data-message-action="retry"
                    disabled={retrying}
                    onClick={() => {
                      if (retrying) return
                      setRetrying(true)
                      void submitTurn(addressedSessionId, retryPrompt).finally(() => { setRetrying(false) })
                    }}
                  >
                    <IconRefreshOutline16 />
                  </button>
                </Tooltip>
              )}
              {!inSubagentTranscript && addressedSessionId != null && (
                <Tooltip label="从此创建分支会话" side="bottom">
                  <button
                    type="button"
                    className={actionCss.action}
                    aria-label="从此创建分支会话"
                    data-message-action="fork"
                    disabled={forking}
                    onClick={() => {
                      if (forking) return
                      setForking(true)
                      void forkSession(addressedSessionId, undefined, message.id)
                        .then(async (res) => {
                          await useSessions.getState().refresh()
                          if (res.session_id) await useSessions.getState().select(res.session_id)
                          toast.success('已创建分支会话并切换')
                        })
                        .catch((err: unknown) => {
                          toast.error(err instanceof Error ? `分支创建失败：${err.message}` : '分支创建失败')
                        })
                        .finally(() => { setForking(false) })
                    }}
                  >
                    <IconBranchOutline16 />
                  </button>
                </Tooltip>
              )}
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

/**
 * The changed-files card for one turn, mounted on that turn's tail anchor.
 *
 * The card itself is the vendored `ChangedFiles`, verbatim in its drawing and
 * hover logic. This wrapper owns only the read lifecycle the card's props
 * assume: upstream receives an already-loaded summary through its slot props,
 * whereas here the summary arrives from the vendored `ChangesSummaryStore`, so
 * something has to issue the first read and hold the card back until it
 * settles.
 *
 * Rendering nothing until the summary lands is deliberate, not a placeholder:
 * the card's shape is derived from the summary (single-file card vs folded
 * list), so there is no honest state to draw before it arrives. The membership
 * test that got us here came from the transcript, but the counts and the file
 * list come from the client — a turn whose summary is `missing` (the client no
 * longer serves it) draws nothing rather than a card of zeros.
 *
 * @param props.sessionId - the viewed Session.
 * @param props.turn - the 1-based turn number to summarise.
 * @param props.openReview - open this turn's review on the file at an index.
 * @param props.t - the card's copy.
 * @returns the card, or null while the summary is unread or unavailable.
 */
function ChangedFilesCard({ sessionId, turn, openReview, t }: {
  sessionId: string
  turn: number
  openReview: (index: number) => void
  t: (key: string, params?: Readonly<Record<string, string | number>>) => string
}) {
  const summary = useChangesSummary(sessionId, turn)
  useEffect(() => {
    if (summary == null || summary === 'error') void changesSummaryStore.load(sessionId, turn)
  }, [summary, sessionId, turn])
  if (summary == null || typeof summary === 'string') {
    // Drawing nothing until the summary settles makes "the read has not landed"
    // and "this turn has no changed files" indistinguishable in the DOM. The
    // marker keeps that seam observable so a test can tell the states apart
    // without reaching into the store; `error` is the one state that reads
    // again (a tab still carrying an earlier launch's token), so it must not
    // be drawn as a final absence.
    return (
      <span
        hidden
        data-changes-state={summary ?? 'unread'}
        data-changes-turn={turn}
      />
    )
  }
  return (
    <ChangedFiles
      changes={summary}
      sessionId={sessionId}
      openReview={openReview}
      diff={changesDiffAccess}
      t={t}
    />
  )
}

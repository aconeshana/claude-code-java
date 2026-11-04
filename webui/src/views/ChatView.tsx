import { useEffect, useMemo, useRef, useState } from 'react'
import { IconChevronDownOutline14 } from '@primitives'
import type { ConversationState, ToolCallState } from '../store/conversations'
import { deriveTurnProcessView, openTurnsOf, useTurnProcess } from '../store/turnProcess'
import { useTranscriptView } from '../store/transcriptView'
import { useSpinnerVerbs } from '../store/spinnerVerbs'
import { withStreamingDraft } from '../store/streamingDraft'
import { STATS_NS, statsDicts } from '../i18n/dictionaries/stats'
import { useTranslate } from '../i18n/useTranslate'
import { RunningStatus } from '@vendor/dsh-running-status/RunningStatus.tsx'
import css from '@chat-styles/ChatView.module.css'
import { MessageItem } from './MessageItem'
import { TurnProcessControl } from './TurnProcessControl'
import { collectTurnDeliverables } from './turnDeliverables'
import { runningVerb } from './runningVerb'

/**
 * Transcript scrollport over the vendored dsh ChatView styles. Follow
 * behavior is the simplified v1: stick to the bottom while the reader is
 * within the follow threshold of the end, surfacing a back-to-bottom control
 * once they scroll away from it.
 *
 * The turn-process fold (dsh's TurnProcessNodeView seat plan): each closed
 * turn with a final answer gets one summary control right after its opening
 * user row, and its process rows (thinking/tool/intermediate messages) hide
 * with `hidden="until-found"` while collapsed — still mounted, so browser
 * find can reveal them (a `beforematch` on a member expands the group,
 * upstream's reveal rule) and stateful tool renderers keep their state. The
 * answer row carries `data-turn-process-answer` for the vendored 8px
 * follow-gap. Both the control and every row stay direct children of
 * `.column` — the vendored sibling-gap rules key on that.
 *
 * While a turn runs, the vendored RunningStatus (dsh's shimmer row with its
 * live elapsed clock, minus the whale icon) is the column's last flow row. It
 * is worded with the TUI spinner's verbs (`/api/spinner-verbs`), picked the
 * way the spinner picks them — see runningVerb. Its clock origin is the
 * conversation's turnStartedAt, which the snapshot seeds from running_since,
 * so a page opened mid-turn resumes the same clock. Each row
 * names its `data-chat-flow-kind`: the status draws its divider only when the
 * row above it is output rather than the user's prompt.
 *
 * The block the model is streaming (the conversation's `streaming` draft,
 * built from the mirror's transient delta frames) renders in place through
 * the same MessageItem as a settled block, open — so text appears as the
 * TUI prints it rather than all at once when the block settles. See
 * withStreamingDraft for where the draft sits. Every slice repaints, as
 * upstream does: MarkdownText is memoized with a streaming cache, so settled
 * rows skip and the open block re-parses only its tail. (A deferred draft
 * would let back-to-back urgent delta updates keep interrupting its
 * transition render, freezing the text until the block settles — the very
 * bug this fixes.)
 */
export function ChatView(
  { conversation, sessionId }: {
    conversation: ConversationState | undefined
    sessionId: string | null
  },
) {
  const scrollRef = useRef<HTMLDivElement>(null)
  const followingRef = useRef(true)
  const [following, setFollowing] = useState(true)
  const setOpen = useTurnProcess((state) => state.setOpen)
  const openTurns = useTurnProcess((state) => openTurnsOf(state, sessionId))
  // The derivation reads the transcript mode too (it disables folding
  // outright outside compact), so the view must re-derive when it flips —
  // and the memo must depend on it, or a settings toggle would not repaint.
  const transcriptMode = useTranscriptView((state) => state.mode)
  const chatT = useTranslate(STATS_NS, statsDicts)
  const verbs = useSpinnerVerbs((state) => state.vocabulary)
  const loadVerbs = useSpinnerVerbs((state) => state.load)
  useEffect(() => { void loadVerbs() }, [loadVerbs])
  // Keyed on the substrate the derivation actually reads, NOT on the
  // conversation object: the reduction returns a fresh conversation (and a
  // fresh messages array) on every streamed block, so a `conversation` key
  // is dead during streaming. `deriveTurnProcessView` memoizes each turn
  // internally, so the re-run this key admits re-specs the active turn only.
  const foldView = useMemo(
    () => deriveTurnProcessView(conversation, openTurns),
    // eslint-disable-next-line react-hooks/exhaustive-deps -- transcriptMode is read inside the derivation
    [conversation?.messages, conversation?.turnRunning, openTurns, transcriptMode],
  )

  useEffect(() => {
    const scroller = scrollRef.current
    if (scroller == null || !followingRef.current) return
    scroller.scrollTop = scroller.scrollHeight
  })

  const onScroll = (): void => {
    const scroller = scrollRef.current
    if (scroller == null) return
    const distance = scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight
    const next = distance <= 64
    followingRef.current = next
    setFollowing(next)
  }

  const scrollToBottom = (): void => {
    const scroller = scrollRef.current
    if (scroller == null) return
    scroller.scrollTop = scroller.scrollHeight
    followingRef.current = true
    setFollowing(true)
  }

  // A beforematch on a hidden member reveals that node alone in the DOM;
  // flip the owning group's open state too, so the control reflects it and
  // sibling members unhide with it (upstream's shared-group reveal). React
  // 18's typings carry no onBeforeMatch, so this is a native listener.
  useEffect(() => {
    const scroller = scrollRef.current
    if (scroller == null) return
    const onBeforeMatch = (event: Event): void => {
      const target = event.target as HTMLElement | null
      const member = target?.closest('[data-turn-process-member]') ?? null
      if (member == null) return
      const turn = Number(member.getAttribute('data-turn-process-member'))
      if (Number.isFinite(turn)) setOpen(sessionId, turn, true)
    }
    scroller.addEventListener('beforematch', onBeforeMatch)
    return () => { scroller.removeEventListener('beforematch', onBeforeMatch) }
  }, [setOpen, sessionId])

  const messages = conversation?.messages ?? []
  const draft = conversation?.streaming
  const rendered = useMemo(() => withStreamingDraft(messages, draft), [messages, draft])

  // Per-turn produced-file paths, collected for the turn tail's row. Upstream
  // mounts its delivered-files row at the `conversation.chat.turnTail` seat
  // with a whole-turn scope; this project's tail anchor is the one row that
  // renders it, so the paths are aggregated by turn here and looked up by the
  // anchor's own turn number.
  const producedByTurn = useMemo(() => {
    const byTurn = new Map<number, readonly (readonly ToolCallState[])[]>()
    for (const message of messages) {
      if (message.kind !== 'assistant' || message.turn === undefined) continue
      const rows = byTurn.get(message.turn) ?? []
      byTurn.set(message.turn, [...rows, message.toolCalls])
    }
    const collected = new Map<number, readonly string[]>()
    for (const [turn, rows] of byTurn) collected.set(turn, collectTurnDeliverables(rows))
    return collected
    // The whole-turn aggregation reads every assistant row's tool calls, so a
    // completed tool on any row of any turn must re-derive.
    // eslint-disable-next-line react-hooks/exhaustive-deps -- keyed on the messages array identity the reducer rebuilds
  }, [messages])

  return (
    <div
      className={css.scroll}
      ref={scrollRef}
      onScroll={onScroll}
    >
      <div className={css.column}>
        {rendered.flatMap((message) => {
          const role = foldView.roles[message.id]
          const controlTurn = foldView.controlTurns[message.id]
          const rows = []
          if (controlTurn !== undefined && foldView.counts[message.id] !== undefined) {
            rows.push(
              <div key={`${message.id}-process`} className={css.flowItem} data-chat-flow-kind="turn-process">
                <TurnProcessControl
                  turn={controlTurn}
                  counts={foldView.counts[message.id]}
                  open={foldView.expandedTurns.has(controlTurn)}
                  onToggle={(open) => { setOpen(sessionId, controlTurn, open) }}
                />
              </div>,
            )
          }
          const memberTurn = foldView.memberTurns[message.id]
          const collapsed = role === 'member' && memberTurn !== undefined
            && !foldView.expandedTurns.has(memberTurn)
          rows.push(
            <div
              key={message.id}
              className={css.flowItem}
              data-chat-flow-kind={message.kind === 'user' ? 'user' : 'assistant'}
              data-turn-process-member={memberTurn !== undefined ? String(memberTurn) : undefined}
              data-turn-process-answer={foldView.compactAnswers.has(message.id) ? '' : undefined}
              hidden={collapsed ? ('until-found' as unknown as boolean) : undefined}
            >
              <MessageItem
                message={message}
                hideReasoning={foldView.inlineReasoningAnswers.has(message.id)}
                showTail={foldView.tailAnchors.has(message.id)}
                producedFiles={foldView.tailAnchorTurns[message.id] !== undefined
                  ? producedByTurn.get(foldView.tailAnchorTurns[message.id]) ?? []
                  : []}
                sessionId={sessionId}
              />
            </div>,
          )
          return rows
        })}
        {conversation != null && conversation.turnRunning && (
          <RunningStatus
            key="running"
            startTime={conversation.turnStartedAt}
            paused={false}
            verb={runningVerb(verbs, messages, conversation.turnStartedAt)}
            t={chatT}
          />
        )}
        {conversation != null && conversation.lastError != null && (
          <div className={css.openError}>{conversation.lastError}</div>
        )}
        {!following && messages.length > 0 && (
          <div className={css.toBottomSlot}>
            <button type="button" className={css.toBottom} onClick={scrollToBottom} aria-label="回到底部">
              <IconChevronDownOutline14 />
            </button>
          </div>
        )}
      </div>
    </div>
  )
}

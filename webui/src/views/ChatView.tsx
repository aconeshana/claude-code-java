import { useEffect, useMemo, useRef, useState } from 'react'
import { IconChevronDownOutline14 } from '@primitives'
import type { ConversationState } from '../store/conversations'
import { deriveTurnProcessView, useTurnProcess } from '../store/turnProcess'
import css from '@chat-styles/ChatView.module.css'
import { MessageItem } from './MessageItem'
import { TurnProcessControl } from './TurnProcessControl'

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
 */
export function ChatView({ conversation }: { conversation: ConversationState | undefined }) {
  const scrollRef = useRef<HTMLDivElement>(null)
  const followingRef = useRef(true)
  const [following, setFollowing] = useState(true)
  const setOpen = useTurnProcess((state) => state.setOpen)
  const openTurns = useTurnProcess((state) => state.openTurns)
  const foldView = useMemo(
    () => deriveTurnProcessView(conversation, openTurns),
    [conversation, openTurns],
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
      if (Number.isFinite(turn)) setOpen(turn, true)
    }
    scroller.addEventListener('beforematch', onBeforeMatch)
    return () => { scroller.removeEventListener('beforematch', onBeforeMatch) }
  }, [setOpen])

  const messages = conversation?.messages ?? []

  return (
    <div
      className={css.scroll}
      ref={scrollRef}
      onScroll={onScroll}
    >
      <div className={css.column}>
        {messages.flatMap((message) => {
          const role = foldView.roles[message.id]
          const controlTurn = foldView.controlTurns[message.id]
          const rows = []
          if (controlTurn !== undefined && foldView.counts[message.id] !== undefined) {
            rows.push(
              <div key={`${message.id}-process`} className={css.flowItem}>
                <TurnProcessControl
                  turn={controlTurn}
                  counts={foldView.counts[message.id]}
                  open={foldView.expandedTurns.has(controlTurn)}
                  onToggle={(open) => { setOpen(controlTurn, open) }}
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
              data-turn-process-member={memberTurn !== undefined ? String(memberTurn) : undefined}
              data-turn-process-answer={foldView.compactAnswers.has(message.id) ? '' : undefined}
              hidden={collapsed ? ('until-found' as unknown as boolean) : undefined}
            >
              <MessageItem
                message={message}
                hideReasoning={foldView.inlineReasoningAnswers.has(message.id)}
              />
            </div>,
          )
          return rows
        })}
        {conversation != null && conversation.turnRunning && (
          <div className={`${css.flowItem} ${css.turnStatus}`}>…</div>
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

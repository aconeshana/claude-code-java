import { useEffect, useRef, useState } from 'react'
import { IconChevronDownOutline14 } from '@primitives'
import type { ConversationState } from '../store/conversations'
import css from '@chat-styles/ChatView.module.css'
import { MessageItem } from './MessageItem'

/**
 * Transcript scrollport over the vendored dsh ChatView styles. Follow
 * behavior is the simplified v1: stick to the bottom while the reader is
 * within the follow threshold of the end, surfacing a back-to-bottom control
 * once they scroll away from it.
 */
export function ChatView({ conversation }: { conversation: ConversationState | undefined }) {
  const scrollRef = useRef<HTMLDivElement>(null)
  const followingRef = useRef(true)
  const [following, setFollowing] = useState(true)

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

  const messages = conversation?.messages ?? []

  return (
    <div className={css.scroll} ref={scrollRef} onScroll={onScroll}>
      <div className={css.column}>
        {messages.map((message) => (
          <div key={message.id} className={css.flowItem}>
            <MessageItem message={message} />
          </div>
        ))}
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

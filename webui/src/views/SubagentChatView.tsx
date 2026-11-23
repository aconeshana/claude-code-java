import { useEffect, useRef, useState } from 'react'
import { IconChevronDownOutline14, IconRefreshOutline14 } from '@primitives'
import type { AgentRecord } from '@dsh-context/shared/types'
import { STATS_NS, statsDicts } from '../i18n/dictionaries/stats'
import { useTranslate } from '../i18n/useTranslate'
import { useAgentTranscript } from '../store/agentTranscript'
import { useSpinnerVerbs } from '../store/spinnerVerbs'
import { RunningStatus } from '@vendor/dsh-running-status/RunningStatus.tsx'
import css from '@chat-styles/ChatView.module.css'
import { MessageItem } from './MessageItem'
import { runningVerb } from './runningVerb'

/**
 * Full-column view of a single subagent's own conversation transcript, paired
 * with a bottom composer in `App.tsx` for continuing the subagent (续聊).
 *
 * Uses the exact same vendored `ChatView.module.css` scrollport and
 * `MessageItem` renderer as the main chat, polls the transcript while the
 * subagent is running, and auto-follows new messages at the bottom.
 */
export function SubagentChatView({
  sessionId,
  agentId,
  agent,
  promptError,
  promptBusy,
}: {
  sessionId: string
  agentId: string
  agent: AgentRecord | undefined
  promptError?: string | null | undefined
  promptBusy?: boolean | undefined
}) {
  const scrollRef = useRef<HTMLDivElement>(null)
  const followingRef = useRef(true)
  const [following, setFollowing] = useState(true)
  const [startedAt, setStartedAt] = useState<number>(() => agent?.time ?? Date.now())
  const chatT = useTranslate(STATS_NS, statsDicts)

  const key = `${sessionId}\u0000${agentId}`
  const entry = useAgentTranscript((state) => state.transcripts[key])
  const load = useAgentTranscript((state) => state.load)
  const reload = useAgentTranscript((state) => state.reload)
  const refresh = useAgentTranscript((state) => state.refresh)
  const verbs = useSpinnerVerbs((state) => state.vocabulary)
  const loadVerbs = useSpinnerVerbs((state) => state.load)

  useEffect(() => { void loadVerbs() }, [loadVerbs])

  // Initial load when entering a subagent.
  useEffect(() => {
    load(sessionId, agentId)
  }, [sessionId, agentId, load])

  const isRunning = promptBusy === true || entry?.running === true || agent?.status === 'running'

  useEffect(() => {
    if (isRunning) setStartedAt((prev) => prev || Date.now())
  }, [isRunning])

  // Poll the transcript every 1.5s while the subagent is running so live tool
  // calls and continuation responses stream into the view.
  useEffect(() => {
    if (!isRunning) return
    let cancelled = false
    const tick = (): void => {
      if (cancelled) return
      void refresh(sessionId, agentId)
    }
    const timer = window.setInterval(tick, 1_500)
    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [isRunning, sessionId, agentId, refresh])

  useEffect(() => {
    const scroller = scrollRef.current
    if (scroller == null || !followingRef.current) return
    scroller.scrollTop = scroller.scrollHeight
  }, [entry?.messages.length, isRunning])

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

  const messages = entry?.messages ?? []
  const verb = runningVerb(verbs, messages, startedAt)

  return (
    <div className={css.root}>
      <div ref={scrollRef} className={css.scroller} onScroll={onScroll}>
        <div className={css.column}>
          {promptError != null && (
            <div
              role="alert"
              style={{
                padding: '8px 12px',
                borderRadius: 10,
                background: 'var(--dsw-alias-bg-secondary)',
                color: 'var(--dsw-alias-state-error, #d93025)',
                fontSize: 13,
              }}
            >
              {promptError}
            </div>
          )}
          {entry?.status === 'loading' && messages.length === 0 && (
            <div style={{ padding: '24px 0', color: 'var(--dsw-alias-label-tertiary)', fontSize: 13 }}>
              正在加载子智能体对话…
            </div>
          )}
          {entry?.status === 'failed' && messages.length === 0 && (
            <div style={{
              padding: '16px 0',
              display: 'flex',
              alignItems: 'center',
              gap: 10,
              color: 'var(--dsw-alias-label-secondary)',
              fontSize: 13,
            }}>
              <span>无法加载子智能体对话：{entry.message ?? '未知错误'}</span>
              <button
                type="button"
                onClick={() => { reload(sessionId, agentId) }}
                style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 4,
                  padding: '4px 10px',
                  borderRadius: 8,
                  border: '1px solid var(--dsw-alias-border-l2)',
                  background: 'transparent',
                  color: 'var(--dsw-alias-label-primary)',
                  cursor: 'pointer',
                  fontSize: 12,
                }}
              >
                <IconRefreshOutline14 />
                重试
              </button>
            </div>
          )}
          {entry?.status === 'ready' && messages.length === 0 && !isRunning && (
            <div style={{ padding: '24px 0', color: 'var(--dsw-alias-label-tertiary)', fontSize: 13 }}>
              （子智能体暂无对话记录）
            </div>
          )}
          {messages.map((message) => (
            <MessageItem key={message.id} message={message} sessionId={sessionId} />
          ))}
          {isRunning && (
            <RunningStatus
              startTime={startedAt}
              paused={false}
              verb={verb}
              t={chatT}
            />
          )}
        </div>
      </div>
      {!following && (
        <button
          type="button"
          className={css.backToBottom}
          onClick={scrollToBottom}
          aria-label="回到底部"
        >
          <IconChevronDownOutline14 />
        </button>
      )}
    </div>
  )
}

import { useMemo, useState } from 'react'
import { DisclosureRow } from '@primitives'
import { MarkdownText } from '@vendor/ui-primitives/markdown/MarkdownText'
import type { MarkdownLabels } from '@vendor/ui-primitives/markdown/MarkdownText'
import css from '@chat-styles/MessageItem.module.css'
import reasoningCss from '@chat-styles/ReasoningRow.module.css'
import type { MessageState } from '../store/conversations'
import { markdownLabels } from './labels'
import { ToolCallRow } from './ToolCallRow'

/**
 * One transcript row: the user bubble (right-aligned, vendored dsh shape)
 * or the assistant stack (thinking disclosure + markdown + tool call rows).
 */
export function MessageItem({ message }: { message: MessageState }) {
  if (message.kind === 'user') {
    return (
      <div className={css.userRow}>
        <div className={css.userStack}>
          <div className={css.bubble}>{message.text}</div>
        </div>
      </div>
    )
  }
  return <AssistantItem message={message} />
}

function AssistantItem({ message }: { message: Extract<MessageState, { kind: 'assistant' }> }) {
  const [thinkingOpen, setThinkingOpen] = useState(false)
  const hasThinking = message.thinkingBlocks.length > 0
  const thinkingText = message.thinkingBlocks.join('\n\n')
  // One stable labels identity: MarkdownText's streaming cache resets when
  // the object identity changes.
  const labels = useMemo<MarkdownLabels>(() => markdownLabels, [])

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8, minWidth: 0 }}>
      {hasThinking && (
        <DisclosureRow
          icon={<span aria-hidden>◦</span>}
          title={message.open ? '正在思考…' : '已深度思考'}
          open={thinkingOpen}
          expandable
          onToggle={() => { setThinkingOpen((value) => !value) }}
        >
          <div className={reasoningCss.summaryText}>{thinkingText}</div>
        </DisclosureRow>
      )}
      {message.textBlocks.map((text: string, index: number) => (
        <MarkdownText
          key={index}
          text={text}
          streaming={message.open && index === message.textBlocks.length - 1}
          labels={labels}
        />
      ))}
      {message.toolCalls.map((call) => (
        <ToolCallRow key={call.toolUseId} call={call} />
      ))}
    </div>
  )
}

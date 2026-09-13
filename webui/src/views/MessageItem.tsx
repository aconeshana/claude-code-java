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
import { ToolCallRow } from './ToolCallRow'
import { MessageIconActions } from '../../vendor/dsh-stats-pills/MessageIconActions'
import { TurnTimePanel, TurnUsagePanel } from '../../vendor/dsh-stats-pills/TurnUsagePanel'
import turnTailCss from '@chat-styles/TurnTailNodeView.module.css'

/**
 * One transcript row: the user bubble (right-aligned, vendored dsh shape)
 * or the assistant stack (thinking disclosure + markdown + tool call rows)
 * plus the vendored dsh chrome rows — copy actions with a date-aware clock
 * under every user bubble, and the full turn-tail icon row (copy + usage
 * pills + trailing clock) on the settled turn's last assistant row.
 */
export function MessageItem({ message }: { message: MessageState }) {
  const statsT = useTranslate(STATS_NS, statsDicts)
  if (message.kind === 'user') {
    return (
      <div className={css.userRow}>
        <div className={css.userStack}>
          <div className={css.bubble}>{message.text}</div>
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
  return <AssistantItem message={message} t={statsT} />
}

function AssistantItem({ message, t }: {
  message: Extract<MessageState, { kind: 'assistant' }>
  t: ReturnType<typeof useTranslate>
}) {
  const defaultOpen = useTranscriptView((state) => state.mode === 'normal')
  // One stable labels identity: MarkdownText's streaming cache resets when
  // the object identity changes.
  const labels = useMemo<MarkdownLabels>(() => markdownLabels, [])
  const plainText = useMemo(
    () => message.textBlocks.join('\n\n'),
    [message.textBlocks],
  )

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8, minWidth: 0 }}>
      {message.thinkingBlocks.map((text: string, index: number) => (
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
        <ToolCallRow key={call.toolUseId} call={call} defaultOpen={defaultOpen} />
      ))}
      <DeliverablesRow toolCalls={message.toolCalls} />
      {/* The settled turn tail: the full vendored icon row — copy action,
          then the usage pill (provider buckets) and the time pill (turn
          wall time), then the trailing clock. A running turn has no tail
          yet, and an assistant row with no tail facts shows no row
          (upstream facts-absent semantics). */}
      {!message.open && (message.turnUsage !== undefined
        || message.runMs !== undefined || message.time !== undefined) && (
        <MessageIconActions
          text={plainText}
          time={message.time}
          clock="end"
          className={turnTailCss.actions}
          usageAction={(
            <>
              {message.turnUsage !== undefined && (
                <TurnUsagePanel usage={message.turnUsage} t={t} />
              )}
              {message.runMs !== undefined && (
                <TurnTimePanel runMs={message.runMs} t={t} />
              )}
            </>
          )}
          t={t}
        />
      )}
    </div>
  )
}

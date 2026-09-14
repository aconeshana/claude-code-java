import { memo } from 'react'
import { IconChevronDownOutline14 } from '@primitives'
import css from '@chat-styles/TurnProcessNodeView.module.css'
import type { TurnProcessCounts } from '../store/turnProcess'
import { useTranslate } from '../i18n/useTranslate'
import { STATS_NS, statsDicts } from '../i18n/dictionaries/stats'

/**
 * The turn-level process disclosure control, ported from dsh's
 * `TurnProcessNodeView`: the summary segments joined by the dictionary's
 * separator, zero-valued segments omitted, "已思考" when every count is
 * zero, and the rotating chevron. Sits between the opening user row and
 * the process rows it folds; clicking toggles the shared group.
 */
export const TurnProcessControl = memo(function TurnProcessControl({
  turn, counts, open, onToggle,
}: {
  turn: number
  counts: TurnProcessCounts
  open: boolean
  onToggle: (open: boolean) => void
}) {
  const t = useTranslate(STATS_NS, statsDicts)
  const labels: string[] = []
  if (counts.toolCallCount > 0) {
    labels.push(t(
      counts.toolCallCount === 1
        ? 'message.turnProcess.toolCalls.one'
        : 'message.turnProcess.toolCalls.other',
      { count: counts.toolCallCount },
    ))
  }
  if (counts.messageCount > 0) {
    labels.push(t(
      counts.messageCount === 1
        ? 'message.turnProcess.messages.one'
        : 'message.turnProcess.messages.other',
      { count: counts.messageCount },
    ))
  }
  if (counts.subagentCount > 0) {
    labels.push(t(
      counts.subagentCount === 1
        ? 'message.turnProcess.subagents.one'
        : 'message.turnProcess.subagents.other',
      { count: counts.subagentCount },
    ))
  }
  const label = labels.length === 0
    ? t('message.turnProcess.thoughtForAWhile')
    : labels.join(t('message.turnProcess.separator'))
  return (
    <button
      type="button"
      className={css.root}
      data-open={open || undefined}
      data-turn-process={turn}
      data-turn-process-messages={counts.messageCount}
      data-turn-process-tool-calls={counts.toolCallCount}
      data-turn-process-subagents={counts.subagentCount}
      aria-expanded={open}
      onClick={(event) => {
        event.currentTarget.focus()
        onToggle(!open)
      }}
    >
      <span className={css.label}>{label}</span>
      <IconChevronDownOutline14 className={css.chevron} />
    </button>
  )
})

import { useState } from 'react'
import { DisclosureRow, IconThinkOutline14 } from '@primitives'
import css from '@chat-styles/ReasoningRow.module.css'
import toolCss from '@chat-styles/ToolRow.module.css'

function firstLine(text: string): string {
  const newline = text.indexOf('\n')
  return newline === -1 ? text : text.slice(0, newline)
}

function latestLine(text: string): string {
  const visible = text.trimEnd()
  const newline = visible.lastIndexOf('\n')
  return newline === -1 ? visible : visible.slice(newline + 1)
}

/**
 * One assistant reasoning block as its own Think disclosure row. Each
 * `thinking` segment of a message renders an independent instance; only the
 * streaming tail segment is `running`.
 */
export function ReasoningRow({ text, running }: { text: string; running: boolean }) {
  const [expanded, setExpanded] = useState(false)
  const summary = (running ? latestLine(text) : firstLine(text)).replaceAll('**', '')

  return (
    <div
      className={css.root}
      data-variant="think"
      data-state={running ? 'running' : 'ok'}
      data-expanded={expanded || undefined}
    >
      {running && <span className={toolCss.visuallyHidden}>思考中…</span>}
      <DisclosureRow
        rowClassName={css.row}
        leadingClassName={css.leading}
        titleClassName={css.title}
        chevronClassName={css.chevron}
        icon={<IconThinkOutline14 size={14} />}
        title="思考"
        open={expanded}
        expandable
        expandOnRowClick
        onToggle={() => { setExpanded((value) => !value) }}
        collapsedContent={(
          <>
            <span className={css.separator} aria-hidden />
            <span className={css.summary} data-follow-end={running || undefined}>
              <span className={css.summaryText}>{summary}</span>
            </span>
          </>
        )}
      >
        <div className={css.thinkBody}>{text}</div>
      </DisclosureRow>
    </div>
  )
}

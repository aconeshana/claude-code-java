import { useState } from 'react'
import clsx from 'clsx'
import {
  DiffBlock, DisclosureRow, IconApiOutline14, ReadBlock, StateDot, TerminalBlock,
} from '@primitives'
import { JsonBlock } from '@vendor/ui-primitives/markdown/JsonBlock'
import css from '@chat-styles/ToolRow.module.css'
import type { ToolCallState } from '../store/conversations'
import { diffLabels, readLabels, terminalLabels } from './labels'

type ToolRowState = 'running' | 'error' | 'stopped' | 'ok'

function stateOf(call: ToolCallState): ToolRowState {
  if (call.status === 'pending') return 'running'
  if (call.status === 'failed') return 'error'
  return 'ok'
}

// This project's ToolCallState carries no interrupted/stopped status today;
// the 'stopped' branch is kept for structural parity with upstream's
// leadingFor and is currently unreachable.
function leadingFor(state: ToolRowState) {
  switch (state) {
    case 'error': return <StateDot state="error" />
    case 'stopped': return <StateDot state="warning" />
    default: return <IconApiOutline14 />
  }
}

function firstLine(text: string): string {
  const newline = text.indexOf('\n')
  return newline === -1 ? text : text.slice(0, newline)
}

function argsSummary(call: ToolCallState): string {
  const args = call.args
  if (args == null) return ''
  if (typeof args.command === 'string') return args.command
  if (typeof args.file_path === 'string') return args.file_path
  if (typeof args.path === 'string') return args.path
  if (typeof args.pattern === 'string') return args.pattern
  if (typeof args.query === 'string') return args.query
  return ''
}

/**
 * One tool call row: the collapsible summary header (vendored dsh
 * `ToolRow` shape) and, expanded, the args and result dispatched by the
 * projected result type.
 */
export function ToolCallRow({ call, defaultOpen = false }: { call: ToolCallState; defaultOpen?: boolean }) {
  const [expanded, setExpanded] = useState(defaultOpen)
  const state = stateOf(call)
  const hasArgs = call.args != null && Object.keys(call.args).length > 0
  const body = call.ready ? renderBody(call, state) : null
  const expandable = hasArgs || body !== null
  const open = expanded && expandable

  const failureLine = state === 'error' && call.resultError != null ? firstLine(call.resultError) : null
  // The wire name is the ToolRegistry registration name ("Bash", capitalized).
  const bashDescription = call.name === 'Bash' && typeof call.args?.description === 'string' && call.args.description.trim() !== ''
    ? call.args.description
    : null
  const summaryText = failureLine ?? bashDescription ?? argsSummary(call)

  return (
    <div className={css.root} data-tool={call.name} data-state={state}>
      <DisclosureRow
        rowClassName={css.row}
        leadingClassName={css.leading}
        titleClassName={css.title}
        chevronClassName={css.chevron}
        icon={leadingFor(state)}
        title={call.name}
        open={open}
        expandable={expandable}
        expandOnRowClick
        keepContentWhenOpen
        onToggle={() => { setExpanded((value) => !value) }}
        collapsedContent={summaryText !== '' && (
          <>
            <span className={css.sep} aria-hidden />
            <span className={clsx(css.summary, failureLine !== null && css.errorSummary)}>
              {summaryText}
            </span>
          </>
        )}
      >
        <div className={css.bodyWrap}>
          {hasArgs && (
            <JsonBlock
              label="输入"
              payload={call.args}
              truncatedLabel={(total) => `已截断，共 ${total} 字符`}
            />
          )}
          {body}
        </div>
      </DisclosureRow>
    </div>
  )
}

function renderBody(call: ToolCallState, state: ToolRowState) {
  const data = call.resultData ?? ''
  const lines = splitLines(data)
  switch (call.resultType) {
    case 'execute_command_tool_result':
      return (
        <TerminalBlock
          command={commandOf(call)}
          output={data}
          running={call.status === 'pending'}
          labels={terminalLabels}
          className={css.terminalBody}
        />
      )
    case 'read_file_tool_result':
      return (
        <ReadBlock
          label={readLabelOf(call)}
          labels={readLabels}
          lines={lines}
          totalLines={lines.length}
          className={css.readBody}
        />
      )
    case 'write_to_file_tool_result':
    case 'replace_in_file_tool_result':
      return (
        <DiffBlock
          diffs={[{ path: diffPathOf(call), oldText: null, newText: data }]}
          labels={diffLabels}
          className={css.diffBody}
        />
      )
    case 'task_tool_result':
      return (
        <ReadBlock
          label="任务输出"
          labels={readLabels}
          lines={lines}
          totalLines={lines.length}
          className={css.readBody}
        />
      )
    default:
      return data === ''
        ? null
        : (
          <div className={css.ioCard}>
            <div className={css.ioSection}>
              <span className={css.ioLabel}>输出</span>
              <span className={css.ioText} data-error={state === 'error' || undefined}>{data}</span>
            </div>
          </div>
        )
  }
}

function readLabelOf(call: ToolCallState): string {
  const path = call.args?.file_path
  return typeof path === 'string' ? path : 'Read'
}

function commandOf(call: ToolCallState): string {
  const command = call.args?.command
  return typeof command === 'string' ? command : ''
}

function diffPathOf(call: ToolCallState): string {
  const path = call.args?.file_path ?? call.args?.notebook_path
  return typeof path === 'string' ? path : 'file'
}

function splitLines(data: string): { number: number; text: string }[] {
  return data.split('\n').map((text, index) => ({ number: index + 1, text }))
}

import { useState } from 'react'
import {
  DiffBlock, IconChevronDownOutline14, ReadBlock, TerminalBlock,
} from '@primitives'
import { JsonBlock } from '@vendor/ui-primitives/markdown/JsonBlock'
import css from '@chat-styles/TurnProcessNodeView.module.css'
import type { ToolCallState } from '../store/conversations'
import { diffLabels, readLabels, terminalLabels } from './labels'

/**
 * One tool call row: the collapsible process header (vendored dsh shape)
 * and, expanded, the result block dispatched by the projected result type.
 * The input renders as JSON when it carries anything worth showing.
 */
export function ToolCallRow({ call }: { call: ToolCallState }) {
  const [open, setOpen] = useState(false)
  const title = toolTitle(call)

  return (
    <div>
      <button
        type="button"
        className={css.root}
        data-open={open || undefined}
        onClick={() => { setOpen((value) => !value) }}
      >
        <span className={css.chevron}><IconChevronDownOutline14 /></span>
        <span className={css.label}>{title}</span>
        <span style={{ marginLeft: 'auto', flex: 'none', fontSize: 12, color: 'var(--dsw-alias-label-quaternary)' }}>
          {call.status === 'pending' ? '运行中…' : call.status === 'failed' ? '失败' : '完成'}
        </span>
      </button>
      {open && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8, padding: '8px 0' }}>
          {call.args != null && Object.keys(call.args).length > 0 && (
            <JsonBlock
              label="输入"
              payload={call.args}
              truncatedLabel={(total) => `已截断，共 ${total} 字符`}
            />
          )}
          {call.ready && renderResult(call)}
        </div>
      )}
    </div>
  )
}

function toolTitle(call: ToolCallState): string {
  const args = call.args
  if (args == null) return call.name
  if (typeof args.command === 'string') return `${call.name} · ${args.command}`
  if (typeof args.file_path === 'string') return `${call.name} · ${args.file_path}`
  if (typeof args.path === 'string') return `${call.name} · ${args.path}`
  if (typeof args.pattern === 'string') return `${call.name} · ${args.pattern}`
  if (typeof args.query === 'string') return `${call.name} · ${args.query}`
  return call.name
}

function renderResult(call: ToolCallState) {
  const data = call.resultData ?? ''
  const lines = splitLines(data)
  switch (call.resultType) {
    case 'execute_command_tool_result':
      return <TerminalBlock command={commandOf(call)} output={data} labels={terminalLabels} />
    case 'read_file_tool_result':
      return (
        <ReadBlock
          label={readLabelOf(call)}
          labels={readLabels}
          lines={lines}
          totalLines={lines.length}
        />
      )
    case 'write_to_file_tool_result':
    case 'replace_in_file_tool_result':
      return (
        <DiffBlock
          diffs={[{ path: diffPathOf(call), oldText: null, newText: data }]}
          labels={diffLabels}
        />
      )
    case 'task_tool_result':
      return (
        <ReadBlock label="任务输出" labels={readLabels} lines={lines} totalLines={lines.length} />
      )
    default:
      return data === ''
        ? null
        : (
          <ReadBlock
            label="结果"
            labels={readLabels}
            lines={lines}
            totalLines={lines.length}
          />
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

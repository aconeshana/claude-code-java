import { useEffect, useRef, useState } from 'react'
import { useTranscriptView } from '../store/transcriptView'
import { openFileInRightDock } from '../store/rightSidebar'
import { useSessions } from '../store/sessions'
import clsx from 'clsx'
import {
  DiffBlock, DisclosureRow, IconApiOutline14, ReadBlock, SearchBlock, StateDot, TerminalBlock, WebBlock,
  type SearchFileGroup, type WebSourceView,
} from '@primitives'
import { JsonBlock } from '@vendor/ui-primitives/markdown/JsonBlock'
import css from '@chat-styles/ToolRow.module.css'
import type { ToolCallState } from '../store/conversations'
import { FileTypeIcon } from './FileTypeIcon'
import { diffLabels, readLabels, searchLabels, terminalLabels, webLabels } from './labels'

type ToolRowState = 'running' | 'error' | 'stopped' | 'ok'

const FILE_TOOLS = new Set(['Read', 'Edit', 'Write', 'NotebookEdit', 'MultiEdit'])

function stateOf(call: ToolCallState): ToolRowState {
  if (call.status === 'pending') return 'running'
  if (call.status === 'failed') return 'error'
  return 'ok'
}

function targetFilePathOf(call: ToolCallState): string | null {
  const args = call.args
  if (args == null) return null
  if (typeof args.file_path === 'string' && args.file_path.trim() !== '') return args.file_path.trim()
  if (typeof args.notebook_path === 'string' && args.notebook_path.trim() !== '') return args.notebook_path.trim()
  if (FILE_TOOLS.has(call.name) && typeof args.path === 'string' && args.path.trim() !== '') return args.path.trim()
  return null
}

function leadingFor(call: ToolCallState, state: ToolRowState) {
  if (state === 'error') return <StateDot state="error" />
  if (state === 'stopped') return <StateDot state="warning" />
  const filePath = FILE_TOOLS.has(call.name) ? targetFilePathOf(call) : null
  if (filePath !== null) return <FileTypeIcon path={filePath} size={15} />
  return <IconApiOutline14 />
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
  if (typeof args.url === 'string') return args.url
  return ''
}

/**
 * One tool call row: the collapsible summary header (vendored dsh
 * `ToolRow` shape) and, expanded, the args and specialized result view
 * (TerminalBlock, ReadBlock, DiffBlock, SearchBlock, WebBlock).
 */
export function ToolCallRow({ call, defaultOpen = false }: { call: ToolCallState; defaultOpen?: boolean }) {
  const [expanded, setExpanded] = useState(defaultOpen)
  const sessionId = useSessions((state) => state.selectedSessionId)
  const transcriptMode = useTranscriptView((state) => state.mode)
  const transcriptRevision = useTranscriptView((state) => state.revision)
  const lastRevision = useRef(transcriptRevision)
  useEffect(() => {
    if (transcriptRevision !== lastRevision.current) {
      lastRevision.current = transcriptRevision
      setExpanded(transcriptMode === 'normal')
    }
  }, [transcriptMode, transcriptRevision])
  const state = stateOf(call)
  const hasArgs = call.args != null && Object.keys(call.args).length > 0
  const body = call.ready ? renderBody(call, state) : null
  const expandable = hasArgs || body !== null
  const open = expanded && expandable

  const failureLine = state === 'error' && call.resultError != null ? firstLine(call.resultError) : null
  const bashDescription = call.name === 'Bash' && typeof call.args?.description === 'string' && call.args.description.trim() !== ''
    ? call.args.description
    : null
  const summaryText = failureLine ?? bashDescription ?? argsSummary(call)
  const filePath = FILE_TOOLS.has(call.name) ? targetFilePathOf(call) : null

  return (
    <div className={css.root} data-tool={call.name} data-state={state}>
      <DisclosureRow
        rowClassName={css.row}
        leadingClassName={css.leading}
        titleClassName={css.title}
        chevronClassName={css.chevron}
        icon={leadingFor(call, state)}
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
          {filePath !== null && sessionId !== null && (
            <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 4 }}>
              <button
                type="button"
                onClick={() => { openFileInRightDock(sessionId, filePath) }}
                style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 6,
                  padding: '3px 8px',
                  border: '0.5px solid var(--dsw-alias-border-l3)',
                  borderRadius: 6,
                  background: 'var(--dsw-alias-bg-layer-1)',
                  color: 'var(--dsw-alias-label-secondary)',
                  fontSize: 12,
                  cursor: 'pointer',
                }}
              >
                <FileTypeIcon path={filePath} size={13} />
                <span>在右侧栏预览文件</span>
              </button>
            </div>
          )}
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

  // Specialized views by tool name when resultType is generic
  if (call.name === 'Glob' && data.trim() !== '') {
    const paths = data.split('\n').map((l) => l.trim()).filter(Boolean)
    return (
      <SearchBlock
        kind="paths"
        paths={paths}
        total={paths.length}
        truncated={false}
        labels={searchLabels}
      />
    )
  }

  if (call.name === 'Grep' && data.trim() !== '') {
    const parsed = parseGrepOutput(data)
    if (parsed.kind === 'matches') {
      return (
        <SearchBlock
          kind="matches"
          files={parsed.groups}
          total={parsed.total}
          truncated={false}
          labels={searchLabels}
        />
      )
    }
    return (
      <SearchBlock
        kind="paths"
        paths={parsed.paths}
        total={parsed.paths.length}
        truncated={false}
        labels={searchLabels}
      />
    )
  }

  if (call.name === 'WebFetch') {
    const url = typeof call.args?.url === 'string' ? call.args.url : ''
    if (url !== '') {
      return (
        <WebBlock
          kind="fetch"
          url={url}
          statusCode={state === 'error' ? 500 : 200}
          truncated={false}
          labels={webLabels}
        />
      )
    }
  }

  if (call.name === 'WebSearch' && data.trim() !== '') {
    const sources = parseWebSources(data)
    return (
      <WebBlock
        kind="search"
        answer={data}
        sources={sources}
        truncated={false}
        labels={webLabels}
      />
    )
  }

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

function parseGrepOutput(raw: string):
  | { readonly kind: 'matches'; readonly groups: SearchFileGroup[]; readonly total: number }
  | { readonly kind: 'paths'; readonly paths: string[] } {
  const rawLines = raw.split('\n').filter((l) => l.trim() !== '')
  const byPath = new Map<string, SearchFileGroup>()
  let total = 0
  const lineRe = /^([^:\n]+):(\d+):(.*)$/
  for (const line of rawLines) {
    const m = lineRe.exec(line)
    if (m === null) continue
    const path = m[1]!
    const lineNumber = Number(m[2]!)
    const text = m[3]!
    let group = byPath.get(path)
    if (group === undefined) {
      group = { path, matches: [] }
      byPath.set(path, group)
    }
    group.matches.push({ lineNumber, line: text })
    total += 1
  }
  if (total > 0) {
    return { kind: 'matches', groups: [...byPath.values()], total }
  }
  return { kind: 'paths', paths: rawLines.map((l) => l.trim()) }
}

function parseWebSources(raw: string): WebSourceView[] {
  const seen = new Set<string>()
  const sources: WebSourceView[] = []
  const mdLinkRe = /\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g
  let m: RegExpExecArray | null
  while ((m = mdLinkRe.exec(raw)) !== null) {
    const title = m[1]!.trim()
    const url = m[2]!.trim()
    if (!seen.has(url)) {
      seen.add(url)
      sources.push({ url, title })
    }
  }
  if (sources.length === 0) {
    const urlRe = /https?:\/\/[^\s)>\]"']+/g
    while ((m = urlRe.exec(raw)) !== null) {
      const url = m[0]
      if (!seen.has(url)) {
        seen.add(url)
        sources.push({ url })
      }
    }
  }
  return sources.slice(0, 12)
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

/**
 * One tool result's text as numbered lines for {@link ReadBlock}. Shared with
 * `SubagentRow`, which renders a settled subagent's result through the same
 * card — one definition of how a result's text becomes numbered lines.
 */
export function splitLines(data: string): { number: number; text: string }[] {
  return data.split('\n').map((text, index) => ({ number: index + 1, text }))
}

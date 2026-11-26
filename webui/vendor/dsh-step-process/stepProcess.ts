/**
 * Vendored and adapted from deepseek-ai/deepseek-harness `packages/client/ui-chat/src/client/`:
 * - `contract/process-groups.ts` (`ProcessActivity`, `RunningProcessActivity`, `ProcessActivitySummary`)
 * - `conversation-nodes/process-activity.ts` (`activity`, `LIVE_TOOL_DETAIL_KEYS`, `normalizeLiveToolText`, `processActivity`)
 * - `chat/step-process.ts` (`processTitle`)
 *
 * Adapted to this project's `MessageState` / `ToolCallState` transcript rows and
 * Claude Code's PascalCase tool names (`Read`, `Bash`, `Edit`, `Write`, `Grep`,
 * `Glob`, `WebSearch`, `WebFetch`, `Agent`, `Task`, `TodoWrite`, `AskUserQuestion`)
 * alongside upstream's lowercase names.
 */

export type ProcessActivity =
  | 'read'
  | 'readImage'
  | 'search'
  | 'write'
  | 'edit'
  | 'commands'
  | 'code'
  | 'webSearch'
  | 'webFetch'
  | 'subagents'
  | 'plan'
  | 'questions'
  | 'tools'
  | 'context'

export type RunningProcessActivity = Exclude<ProcessActivity, 'context'>

export interface ProcessActivitySummary {
  readonly counts: readonly { readonly kind: ProcessActivity; readonly count: number }[]
  readonly running: RunningProcessActivity | undefined
  readonly runningDetail: string
  /** Latest human-readable detail across settled calls (for settled single-line context). */
  readonly lastDetail: string
  /** Total distinct tool calls inside this process group. */
  readonly totalCalls: number
}

export interface ProcessToolInput {
  readonly toolUseId: string
  readonly name: string
  readonly args: Readonly<Record<string, unknown>> | null
  readonly status: 'pending' | 'executed' | 'failed'
}

export interface ProcessStepInput {
  readonly thinkingBlocks: readonly string[]
  readonly toolCalls: readonly ProcessToolInput[]
  readonly open: boolean
  readonly synthetic?: boolean | undefined
}

export type StepProcessTranslate = (
  key: string,
  params?: Readonly<Record<string, string | number>>,
) => string

/** Map both Claude Code tool names and upstream dsh tool names onto ProcessActivity. */
export function activityOfTool(rawName: string): RunningProcessActivity {
  const name = rawName.toLowerCase()
  if (name === 'read' || name === 'read_file' || name === 'notebookread') return 'read'
  if (name === 'read_image') return 'readImage'
  if (name === 'grep' || name === 'glob' || name === 'toolsearch' || name.endsWith('_inspect')) return 'search'
  if (name === 'write' || name === 'write_to_file') return 'write'
  if (name === 'edit' || name === 'multiedit' || name === 'notebookedit' || name === 'apply_patch') return 'edit'
  if (
    name === 'bash'
    || name === 'powershell'
    || name === 'pwsh'
    || name === 'exec_command'
    || name === 'write_stdin'
    || name.startsWith('terminal_')
  ) return 'commands'
  if (name === 'run_code' || name === 'cua_run_code') return 'code'
  if (name === 'websearch' || name === 'web_search') return 'webSearch'
  if (name === 'webfetch' || name === 'web_fetch') return 'webFetch'
  if (name === 'agent' || name === 'task' || name === 'subagent' || name.startsWith('subagent_')) return 'subagents'
  if (
    name === 'todowrite'
    || name === 'todo_write'
    || name === 'taskcreate'
    || name === 'taskupdate'
    || name === 'enterplanmode'
    || name === 'exitplanmode'
    || name === 'create_goal'
    || name === 'update_goal'
    || name === 'get_goal'
  ) return 'plan'
  if (name === 'askuserquestion' || name === 'ask_user_question' || name === 'request_user_input') return 'questions'
  return 'tools'
}

const LIVE_TOOL_DETAIL_MAX_CHARS = 160
const LIVE_TOOL_DETAIL_KEYS = [
  'title', 'description', 'objective', 'task', 'task_name', 'name', 'question', 'questions', 'prompt', 'message',
  'command', 'cmd', 'queries', 'query', 'pattern', 'url', 'uri', 'file_path', 'path', 'target', 'action', 'status',
] as const

function normalizeLiveToolText(text: string): string {
  const normalized = text.replace(/\s+/g, ' ').trim()
  if (normalized.length <= LIVE_TOOL_DETAIL_MAX_CHARS) return normalized
  if (typeof Intl !== 'undefined' && typeof Intl.Segmenter === 'function') {
    const segmenter = new Intl.Segmenter(undefined, { granularity: 'grapheme' })
    const chars: string[] = []
    for (const { segment } of segmenter.segment(normalized)) {
      if (chars.length === LIVE_TOOL_DETAIL_MAX_CHARS) {
        return `${chars.slice(0, LIVE_TOOL_DETAIL_MAX_CHARS - 1).join('').trimEnd()}…`
      }
      chars.push(segment)
    }
    return normalized
  }
  return `${normalized.slice(0, LIVE_TOOL_DETAIL_MAX_CHARS - 1).trimEnd()}…`
}

function normalizeLiveToolDetail(value: unknown): string {
  const text = typeof value === 'string'
    ? value
    : Array.isArray(value) && value.every(item => typeof item === 'string')
      ? value.join(', ')
      : ''
  return normalizeLiveToolText(text)
}

function questionDetail(value: unknown): string {
  if (!Array.isArray(value)) return ''
  for (const item of value) {
    if (item === null || typeof item !== 'object') continue
    const detail = normalizeLiveToolDetail(Reflect.get(item, 'question'))
    if (detail !== '') return detail
  }
  return ''
}

/**
 * Upstream `liveToolDetail`: extract the first human-readable summary key
 * (`description`, `command`, `file_path`, `pattern`, `query`, etc.) from a
 * tool call's arguments.
 */
export function liveToolDetail(name: string, args: Readonly<Record<string, unknown>> | null): string {
  if (args != null) {
    for (const key of LIVE_TOOL_DETAIL_KEYS) {
      if (!(key in args)) continue
      const raw = args[key]
      const detail = key === 'questions' ? questionDetail(raw) : normalizeLiveToolDetail(raw)
      if (detail !== '') return detail
    }
  }
  return normalizeLiveToolText(name)
}

function liveReasoningDetail(steps: readonly ProcessStepInput[]): string {
  for (let i = steps.length - 1; i >= 0; i--) {
    const step = steps[i]!
    if (!step.open) continue
    for (let b = step.thinkingBlocks.length - 1; b >= 0; b--) {
      const block = step.thinkingBlocks[b]!
      const paragraphs = block.split(/\r?\n[\t ]*\r?\n/)
      for (let p = paragraphs.length - 1; p >= 0; p--) {
        const detail = normalizeLiveToolDetail(paragraphs[p]?.replaceAll('**', ''))
        if (detail !== '') return detail
      }
    }
  }
  return ''
}

/**
 * Rank categories by distinct call count, breaking ties by first appearance.
 * Mirrors upstream `processActivity` (`conversation-nodes/process-activity.ts`).
 */
export function summarizeProcessSteps(steps: readonly ProcessStepInput[]): ProcessActivitySummary {
  const counts = new Map<ProcessActivity, number>()
  const seen = new Set<string>()
  let running: RunningProcessActivity | undefined
  let runningDetail = ''
  let lastDetail = ''
  let totalCalls = 0

  for (const step of steps) {
    for (const call of step.toolCalls) {
      if (seen.has(call.toolUseId)) continue
      seen.add(call.toolUseId)
      totalCalls += 1
      const kind = activityOfTool(call.name)
      counts.set(kind, (counts.get(kind) ?? 0) + 1)
      const detail = liveToolDetail(call.name, call.args)
      if (detail !== '') lastDetail = detail
      if (call.status === 'pending') {
        running = kind
        runningDetail = detail
      }
    }
  }

  if (running === undefined) runningDetail = liveReasoningDetail(steps)
  if (steps.length > 0 && steps.every(step => step.synthetic === true)) {
    counts.set('context', steps.length)
  }

  return {
    counts: [...counts].map(([kind, count]) => ({ kind, count })).sort((a, b) => b.count - a.count),
    running,
    runningDetail,
    lastDetail,
    totalCalls,
  }
}

/**
 * Compose a closed group's localized title from its top three categories without counts.
 * Verbatim from upstream `packages/client/ui-chat/src/client/chat/step-process.ts`.
 */
export function processTitle(
  summary: ProcessActivitySummary,
  t: StepProcessTranslate,
): string {
  const labels = summary.counts.slice(0, 3).map(({ kind }) => t(`message.stepProcess.done.${kind}`))
  const first = labels[0]
  if (first === undefined) return t('message.stepProcess.done.thinking')
  const continuation = (label: string): string => label.charAt(0).toLowerCase() + label.slice(1)
  const second = labels[1]
  if (second === undefined) return first
  if (labels.length === 2) {
    const prefix = t('message.stepProcess.sharedPrefix')
    const shared = prefix !== '' && first.startsWith(prefix) && second.startsWith(prefix)
    return t('message.stepProcess.joinTwo', { first, second: continuation(shared ? second.slice(prefix.length) : second) })
  }
  const title = [first, ...labels.slice(1).map(continuation)].join(t('message.stepProcess.comma'))
  return summary.counts.length > 3 ? t('message.stepProcess.more', { title }) : title
}

import type { CatalogProject, CatalogSession, ScheduleTask } from '../api/types'

const SCHEDULED_PREFIXES = ['[定时任务]', '[定时任务#', '[定时]'] as const
const TASK_ID_TAG_RE = /^\[定时任务#([a-f0-9]{6,16})\]\s*/i
const LEGACY_PREFIX_RE = /^\[(?:定时任务|定时)\]\s*/

export interface ScheduledSessionRecord {
  readonly session: CatalogSession
  readonly projectPath: string
  readonly taskId: string | null
  readonly cleanSummary: string
}

export interface StrippedSessions {
  readonly regularProjects: readonly CatalogProject[]
  readonly scheduledRuns: readonly ScheduledSessionRecord[]
  readonly projectPathBySessionId: ReadonlyMap<string, string>
}

export interface TaskWithRuns {
  readonly task: ScheduleTask
  readonly runs: readonly ScheduledSessionRecord[]
}

export interface GroupedScheduledRuns {
  readonly byTask: readonly TaskWithRuns[]
  readonly unmatchedRuns: readonly ScheduledSessionRecord[]
}

/** True when a session was spawned by the scheduled-task daemon. */
export function isScheduledSession(session: CatalogSession): boolean {
  const candidates = [session.first_prompt, session.summary, session.custom_title]
  for (const text of candidates) {
    if (text == null) continue
    const trimmed = text.trim()
    if (SCHEDULED_PREFIXES.some((prefix) => trimmed.startsWith(prefix))) {
      return true
    }
  }
  return false
}

/** Extracts the embedded task ID from `[定时任务#<id>] ...` if present. */
export function extractTaskIdFromSession(session: CatalogSession): string | null {
  const candidates = [session.first_prompt, session.summary, session.custom_title]
  for (const text of candidates) {
    if (text == null) continue
    const match = TASK_ID_TAG_RE.exec(text.trim())
    if (match?.[1] != null) return match[1].toLowerCase()
  }
  return null
}

/** Strips `[定时任务]` / `[定时任务#<id>]` prefix for clean display in run lists. */
export function stripScheduledPrefix(text: string | undefined | null): string {
  if (text == null) return ''
  return text.trim().replace(TASK_ID_TAG_RE, '').replace(LEGACY_PREFIX_RE, '').trim()
}

function modifiedEpoch(session: CatalogSession): number {
  const parsed = Date.parse(session.modified_at)
  return Number.isNaN(parsed) ? 0 : parsed
}

/**
 * Removes scheduled-task sessions from the normal project sidebar tree and
 * returns them sorted reverse-chronologically for the dedicated Scheduled
 * Tasks workspace.
 */
export function stripScheduledSessions(projects: readonly CatalogProject[]): StrippedSessions {
  const regularProjects: CatalogProject[] = []
  const scheduledRuns: ScheduledSessionRecord[] = []
  const projectPathBySessionId = new Map<string, string>()

  for (const project of projects) {
    const regular: CatalogSession[] = []
    for (const session of project.sessions) {
      if (isScheduledSession(session)) {
        const rawSummary = session.custom_title ?? session.summary ?? session.first_prompt ?? ''
        scheduledRuns.push({
          session,
          projectPath: project.project_path,
          taskId: extractTaskIdFromSession(session),
          cleanSummary: stripScheduledPrefix(rawSummary),
        })
        projectPathBySessionId.set(session.id, project.project_path)
      } else {
        regular.push(session)
      }
    }
    if (regular.length > 0) {
      const removed = project.sessions.length - regular.length
      regularProjects.push({
        ...project,
        session_count: Math.max(regular.length, project.session_count - removed),
        sessions: regular,
      })
    }
  }

  scheduledRuns.sort((a, b) => modifiedEpoch(b.session) - modifiedEpoch(a.session))

  return { regularProjects, scheduledRuns, projectPathBySessionId }
}

/**
 * Collapses all historical runs under their owning scheduled task (reverse-chronological),
 * so 5 runs of one task appear as a single task entry with an expandable 5-run history.
 *
 * Matches by explicit `[定时任务#<id>]` tag first; falls back to prompt-prefix matching
 * for legacy `[定时任务] ` sessions created before task-ID tagging.
 */
export function groupRunsByTask(
  tasks: readonly ScheduleTask[],
  runs: readonly ScheduledSessionRecord[],
): GroupedScheduledRuns {
  const runsByTaskId = new Map<string, ScheduledSessionRecord[]>()
  for (const task of tasks) {
    runsByTaskId.set(task.id.toLowerCase(), [])
  }
  const unmatchedRuns: ScheduledSessionRecord[] = []

  const normalizedPrompts = tasks.map((task) => ({
    id: task.id.toLowerCase(),
    head: task.prompt.trim().replace(/\s+/g, ' ').slice(0, 40),
  }))

  for (const run of runs) {
    const explicitId = run.taskId
    if (explicitId != null && runsByTaskId.has(explicitId)) {
      runsByTaskId.get(explicitId)!.push(run)
      continue
    }
    const cleanHead = run.cleanSummary.replace(/\s+/g, ' ')
    const matched = cleanHead.length >= 8
      ? normalizedPrompts.find((entry) => entry.head.length >= 8 && cleanHead.startsWith(entry.head.slice(0, 24)))
      : undefined
    if (matched != null) {
      runsByTaskId.get(matched.id)!.push(run)
    } else {
      unmatchedRuns.push(run)
    }
  }

  const byTask: TaskWithRuns[] = tasks.map((task) => ({
    task,
    runs: runsByTaskId.get(task.id.toLowerCase()) ?? [],
  }))

  return { byTask, unmatchedRuns }
}

/** Extracts a concise single-line title from a task's prompt. */
export function taskDisplayTitle(prompt: string): string {
  const firstLine = prompt.trim().split(/\r?\n/, 1)[0] ?? ''
  const cleaned = firstLine.replace(/[：:]\s*$/, '').trim()
  if (cleaned.length <= 48) return cleaned || '未命名定时任务'
  return `${cleaned.slice(0, 48)}…`
}

const DOW_LABELS: Readonly<Record<string, string>> = {
  '0': '周日',
  '1': '周一',
  '2': '周二',
  '3': '周三',
  '4': '周四',
  '5': '周五',
  '6': '周六',
  '7': '周日',
  '1-5': '工作日',
  '0,6': '周末',
  '6,0': '周末',
}

function pad2(value: string): string {
  return value.length === 1 ? `0${value}` : value
}

/** Human-friendly Chinese description for common 5-field cron expressions. */
export function cronToHumanZh(cron: string): string {
  const parts = cron.trim().split(/\s+/)
  if (parts.length !== 5) return cron
  const [min, hour, dom, month, dow] = parts
  if (min == null || hour == null || dom == null || month == null || dow == null) return cron

  if (min.startsWith('*/') && hour === '*' && dom === '*' && month === '*' && dow === '*') {
    return `每 ${min.slice(2)} 分钟`
  }
  if (/^\d+$/.test(min) && hour === '*' && dom === '*' && month === '*' && dow === '*') {
    return `每小时 :${pad2(min)}`
  }
  if (/^\d+$/.test(min) && hour.startsWith('*/') && dom === '*' && month === '*' && dow === '*') {
    return `每 ${hour.slice(2)} 小时 :${pad2(min)}`
  }
  if (/^\d+$/.test(min) && /^\d+$/.test(hour)) {
    const time = `${pad2(hour)}:${pad2(min)}`
    if (dom === '*' && month === '*' && dow === '*') {
      return `每天 ${time}`
    }
    if (dom === '*' && month === '*' && DOW_LABELS[dow] != null) {
      return `${DOW_LABELS[dow]} ${time}`
    }
    if (/^\d+$/.test(dom) && month === '*' && dow === '*') {
      return `每月 ${dom} 日 ${time}`
    }
    if (/^\d+$/.test(dom) && /^\d+$/.test(month) && dow === '*') {
      return `${month}月${dom}日 ${time}`
    }
  }
  return cron
}

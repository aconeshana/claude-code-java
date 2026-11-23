import { describe, expect, it } from 'vitest'
import type { CatalogProject, CatalogSession, ScheduleTask } from '../api/types'
import {
  cronToHumanZh,
  extractTaskIdFromSession,
  groupRunsByTask,
  isScheduledSession,
  stripScheduledSessions,
} from './scheduledSessions'

function session(overrides: Partial<CatalogSession> & { id: string }): CatalogSession {
  return {
    summary: null,
    git_branch: null,
    custom_title: null,
    first_prompt: null,
    message_count: 2,
    modified_at: '2026-11-22T09:00:00.000Z',
    cwd: '/repo/demo',
    active: false,
    headless_open: false,
    remote: false,
    interactive: false,
    ...overrides,
  } as CatalogSession
}

function task(overrides: Partial<ScheduleTask> & { id: string }): ScheduleTask {
  return {
    cron: '0 9 * * *',
    prompt: '每日分析 vLLM 与 SGLang 社区 PR 机会',
    recurring: true,
    durable: true,
    created_at: 1700000000000,
    ...overrides,
  }
}

describe('scheduledSessions v2 helpers', () => {
  it('recognizes both tagged and legacy scheduled prefixes', () => {
    expect(isScheduledSession(session({ id: 'a', first_prompt: '[定时任务#7887dee5] daily check' }))).toBe(true)
    expect(isScheduledSession(session({ id: 'b', summary: '[定时任务] nightly check' }))).toBe(true)
    expect(isScheduledSession(session({ id: 'c', first_prompt: 'normal chat' }))).toBe(false)
  })

  it('extracts embedded task id from tagged sessions', () => {
    expect(extractTaskIdFromSession(session({ id: 'a', first_prompt: '[定时任务#7887dee5] hello' }))).toBe('7887dee5')
    expect(extractTaskIdFromSession(session({ id: 'b', first_prompt: '[定时任务] legacy' }))).toBeNull()
  })

  it('strips scheduled runs out of regular projects and orders them reverse-chronologically', () => {
    const projects: CatalogProject[] = [
      {
        project_path: '/repo/a',
        project_name: 'a',
        session_count: 3,
        sessions: [
          session({ id: 's-regular', first_prompt: 'fix bug', modified_at: '2026-11-22T08:00:00.000Z' }),
          session({ id: 's-old', first_prompt: '[定时任务#1111aaaa] run 1', modified_at: '2026-11-21T09:00:00.000Z' }),
          session({ id: 's-new', first_prompt: '[定时任务#1111aaaa] run 2', modified_at: '2026-11-22T09:00:00.000Z' }),
        ],
      },
    ]

    const { regularProjects, scheduledRuns, projectPathBySessionId } = stripScheduledSessions(projects)
    expect(regularProjects).toHaveLength(1)
    expect(regularProjects[0]!.sessions.map((s) => s.id)).toEqual(['s-regular'])
    expect(regularProjects[0]!.session_count).toBe(1)
    expect(scheduledRuns.map((r) => r.session.id)).toEqual(['s-new', 's-old'])
    expect(projectPathBySessionId.get('s-new')).toBe('/repo/a')
  })

  it('groups multiple runs under their owning task via task id or legacy prompt match', () => {
    const projects: CatalogProject[] = [
      {
        project_path: '/repo/a',
        project_name: 'a',
        session_count: 3,
        sessions: [
          session({ id: 'r1', first_prompt: '[定时任务#7887dee5] 每日分析 vLLM 与 SGLang 社区 PR 机会', modified_at: '2026-11-22T09:00:00.000Z' }),
          session({ id: 'r2', first_prompt: '[定时任务] 每日分析 vLLM 与 SGLang 社区 PR 机会：详细步骤', modified_at: '2026-11-21T09:00:00.000Z' }),
          session({ id: 'r3', first_prompt: '[定时任务#deadbeef] 已删除的任务历史', modified_at: '2026-11-20T09:00:00.000Z' }),
        ],
      },
    ]
    const { scheduledRuns } = stripScheduledSessions(projects)
    const grouped = groupRunsByTask([task({ id: '7887dee5' })], scheduledRuns)

    expect(grouped.byTask).toHaveLength(1)
    expect(grouped.byTask[0]!.runs.map((r) => r.session.id)).toEqual(['r1', 'r2'])
    expect(grouped.unmatchedRuns.map((r) => r.session.id)).toEqual(['r3'])
  })

  it('formats common cron expressions into concise Chinese labels', () => {
    expect(cronToHumanZh('0 9 * * *')).toBe('每天 09:00')
    expect(cronToHumanZh('30 8 * * 1-5')).toBe('工作日 08:30')
    expect(cronToHumanZh('0 18 * * 5')).toBe('周五 18:00')
    expect(cronToHumanZh('*/15 * * * *')).toBe('每 15 分钟')
    expect(cronToHumanZh('7 * * * *')).toBe('每小时 :07')
  })
})

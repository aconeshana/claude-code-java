/**
 * `workspace` namespace dictionaries, ported from dsh ui-workspace's
 * locales.ts by subtraction: only the keys the session tree's rows and
 * overflow control consume (no search/view-options/workspace-CRD copy —
 * those features are not ported; see webui/UPSTREAM.md).
 */
import type { LocaleDict, LocaleId } from '../types'

export const WORKSPACE_NS = 'workspace'

export const workspaceZh = {
  'section.sessions': '会话',
  'sessions.expand': '展开其余 {n} 个会话',
  'sessions.collapse': '收起',
  'empty.none': '暂无会话',
  'status.running': '进行中',
  'status.idle': '空闲',
  'status.completed': '已完成',
  'actions.newSession.aria': '在“{name}”中新建会话',
  'actions.close.aria': '关闭会话“{name}”',
  'refresh': '刷新会话列表',
  'newSession': '新会话',
  'time.now': '刚刚',
  'time.minutes': '{n}分钟',
  'time.hours': '{n}小时',
  'time.days': '{n}天',
  'time.months': '{n}个月',
  'time.years': '{n}年',
} as const

export const workspaceEn: Record<keyof typeof workspaceZh, string> = {
  'section.sessions': 'Sessions',
  'sessions.expand': 'Show {n} more sessions',
  'sessions.collapse': 'Show less',
  'empty.none': 'No sessions yet',
  'status.running': 'Running',
  'status.idle': 'Idle',
  'status.completed': 'Completed',
  'actions.newSession.aria': 'New session in {name}',
  'actions.close.aria': 'Close session {name}',
  'refresh': 'Refresh session list',
  'newSession': 'New session',
  'time.now': 'now',
  'time.minutes': '{n}min',
  'time.hours': '{n}h',
  'time.days': '{n}d',
  'time.months': '{n}mo',
  'time.years': '{n}y',
}

export const workspaceDicts: Readonly<Record<LocaleId, LocaleDict>> = { zh: workspaceZh, en: workspaceEn }

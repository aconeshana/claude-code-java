/**
 * `workspace` namespace dictionaries, ported from dsh ui-workspace's
 * locales.ts by subtraction: only the keys the session tree's rows and
 * overflow control consume (no search/view-options/workspace-CRD copy —
 * those features are not ported; see webui/UPSTREAM.md). `newSession`,
 * `refresh`, and the `toggle.*` pair belong to the sidebar shell itself
 * (ui-sidebar's own locales.ts), not ui-workspace, but Sidebar.tsx already
 * reads every sidebar-shell string from this one namespace.
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
  'actions.menu.aria': '会话“{name}”的更多操作',
  'actions.rename': '重命名',
  'actions.fork': '分叉会话',
  'actions.archive': '归档',
  'actions.delete': '删除',
  'rename.title': '重命名会话',
  'rename.placeholder': '会话标题',
  'rename.confirm': '保存',
  'rename.cancel': '取消',
  'delete.title': '删除会话',
  'delete.description': '此操作将永久删除该会话的记录，且无法撤销。',
  'delete.acknowledge': '我已知晓此操作不可撤销',
  'delete.confirm': '删除',
  'delete.cancel': '取消',
  'dialog.close.aria': '关闭对话框',
  'refresh': '刷新会话列表',
  'newSession': '新会话',
  'toggle.collapse': '收起侧边栏',
  'toggle.open': '展开侧边栏',
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
  'actions.menu.aria': 'More actions for session {name}',
  'actions.rename': 'Rename',
  'actions.fork': 'Fork session',
  'actions.archive': 'Archive',
  'actions.delete': 'Delete',
  'rename.title': 'Rename session',
  'rename.placeholder': 'Session title',
  'rename.confirm': 'Save',
  'rename.cancel': 'Cancel',
  'delete.title': 'Delete session',
  'delete.description': 'This permanently deletes this session\u2019s transcript. This cannot be undone.',
  'delete.acknowledge': 'I understand this cannot be undone',
  'delete.confirm': 'Delete',
  'delete.cancel': 'Cancel',
  'dialog.close.aria': 'Close dialog',
  'refresh': 'Refresh session list',
  'newSession': 'New session',
  'toggle.collapse': 'Collapse sidebar',
  'toggle.open': 'Expand sidebar',
  'time.now': 'now',
  'time.minutes': '{n}min',
  'time.hours': '{n}h',
  'time.days': '{n}d',
  'time.months': '{n}mo',
  'time.years': '{n}y',
}

export const workspaceDicts: Readonly<Record<LocaleId, LocaleDict>> = { zh: workspaceZh, en: workspaceEn }

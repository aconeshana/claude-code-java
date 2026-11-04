import type { LocaleDict, LocaleId } from '../types'

// Copy lifted verbatim from upstream ui-conversation's own dictionary
// (packages/client/ui-conversation/src/client/locales.ts at the pinned dsh
// commit); only the keys the to-do dock consumes are kept. The panel's own
// namespace is `conversation` upstream, but this project keeps one file per
// surface, so the keys live under the dock's own namespace here.
export const TODOS_NS = 'todos'

export const todosZh = {
  'todo.title': '任务',
  'todo.progress.done': '{done} 已完成',
  'todo.progress.active': '{active} 进行中',
  'todo.progress.pending': '{pending} 待处理',
  'todo.status.completed': '已完成',
  'todo.status.inProgress': '进行中',
  'todo.status.pending': '待处理',
} as const

export const todosEn: Record<keyof typeof todosZh, string> = {
  'todo.title': 'To-dos',
  'todo.progress.done': '{done} completed',
  'todo.progress.active': '{active} in progress',
  'todo.progress.pending': '{pending} pending',
  'todo.status.completed': 'Completed',
  'todo.status.inProgress': 'In progress',
  'todo.status.pending': 'Pending',
}

export const todosDicts: Readonly<Record<LocaleId, LocaleDict>> = { zh: todosZh, en: todosEn }
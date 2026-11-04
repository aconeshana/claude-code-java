import { useState } from 'react'
import { IconChecklistOutline14, IconChevronDownOutline14, IconChevronUpOutline14, StateDot, type StateDotState } from '@primitives'
import type { SessionTaskBoardItem, TaskStatus } from '../../api/types'
import { TODOS_NS, todosDicts } from '../../i18n/dictionaries/todos'
import { useTranslate } from '../../i18n/useTranslate'
import css from './TodoPanel.module.css'

/**
 * The to-do dock: the session's current plan, rendered above the composer.
 *
 * Vendored from upstream dsh
 * `packages/client/ui-conversation/src/client/skeleton/TodoPanel.tsx` (pinned
 * commit). The render, the collapse behaviour, the progress summary and the
 * status-dot mapping are upstream's; what is subtracted is upstream's slot
 * machinery — the cordis `ctx.slots.inject/register` entry, the
 * `useProjection('todos')` dock adapter and the `PropsLocale` seat. This
 * project has no slot registry, so the panel is mounted directly by `App.tsx`
 * (see `webui/UPSTREAM.md` for the recorded mount-point deviation) and takes
 * the list as a plain prop, and translations come from this project's own
 * `useTranslate` over the vendored `todo.*` keys.
 *
 * Upstream's `TodoItem.content` is the board entry's `subject` here: the
 * runtime port models the visible task title separately from its long-form
 * `description`, and the panel shows only the title.
 */

export interface TodoPanelProps {
  /** The session's current plan (empty renders nothing). */
  readonly tasks: readonly SessionTaskBoardItem[]
}

/** Local exhaustiveness helper — closed-union backstop for a forged status. */
function assertNever(value: never): never {
  throw new Error(`unreachable todo status: ${String(value)}`)
}

/** Map the task lifecycle state onto the shared compact status language. */
function statusDotState(status: TaskStatus): StateDotState {
  switch (status) {
    case 'completed': return 'done'
    case 'in_progress': return 'ongoing'
    case 'pending': return 'idle'
    default: return assertNever(status)
  }
}

/** The localized status announced beside one decorative marker. */
function statusLabel(status: TaskStatus, t: (key: string) => string): string {
  switch (status) {
    case 'completed': return t('todo.status.completed')
    case 'in_progress': return t('todo.status.inProgress')
    case 'pending': return t('todo.status.pending')
    default: return assertNever(status)
  }
}

/**
 * Header summary: "·"-joined per-status counts. Zero-count segments are
 * omitted as noise; a non-empty list always keeps at least one.
 */
function progressLabel(
  tasks: readonly SessionTaskBoardItem[],
  t: (key: string, params?: Readonly<Record<string, string | number>>) => string,
): string {
  const done = tasks.filter(item => item.status === 'completed').length
  const active = tasks.filter(item => item.status === 'in_progress').length
  const pending = tasks.length - done - active
  // En spaces (U+2002): HTML collapses runs of ASCII spaces, so widening the
  // separator's breathing room needs a literal wide space.
  return [
    ...done > 0 ? [t('todo.progress.done', { done })] : [],
    ...active > 0 ? [t('todo.progress.active', { active })] : [],
    ...pending > 0 ? [t('todo.progress.pending', { pending })] : [],
  ].join('\u2002·\u2002')
}

export function TodoPanel({ tasks }: TodoPanelProps) {
  const [collapsed, setCollapsed] = useState(true)
  const t = useTranslate(TODOS_NS, todosDicts)
  if (tasks.length === 0) return null

  return (
    <section className={css.root} data-testid="todo-panel" aria-label={t('todo.title')}>
      <div className={css.body}>
        <button
          type="button"
          className={css.header}
          aria-expanded={!collapsed}
          onClick={() => { setCollapsed(v => !v) }}
        >
          <span className={css.lead} aria-hidden><IconChecklistOutline14 /></span>
          <span className={css.title}>{t('todo.title')}</span>
          <span className={css.progress}>{progressLabel(tasks, t)}</span>
          <span className={css.chevron} aria-hidden>
            {collapsed ? <IconChevronUpOutline14 /> : <IconChevronDownOutline14 />}
          </span>
        </button>
        {!collapsed && (
          <ul className={css.list}>
            {tasks.map(item => (
              <li key={item.id} className={css.item} data-status={item.status}>
                <span className={css.glyph} role="img" aria-label={statusLabel(item.status, t)}>
                  <StateDot state={statusDotState(item.status)} />
                </span>
                <span className={css.content}>{item.subject}</span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </section>
  )
}
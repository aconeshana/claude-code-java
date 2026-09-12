import { useEffect, useState } from 'react'
import clsx from 'clsx'
import {
  IconAlarmClockOutline16, IconCloseOutline16, IconPlusOutline16, IconRefreshOutline16,
  IconTrashOutline16, Input, Switch,
} from '@primitives'
import css from '@chat-styles/SettingsRoot.module.css'
import sectionCss from '@chat-styles/GeneralSection.module.css'
import listCss from '@chat-styles/ScheduleCatalogAction.module.css'
import { useSchedule } from '../store/schedule'
import { useSessionContext } from '../store/sessionContext'
import type { ScheduleTask } from '../api/types'
import { MenuSelect, SettingsRow } from './SettingsPanel'
import localCss from './SchedulePanel.module.css'

/**
 * Scheduled-task dialog: lists the process's cron jobs (CronStore rows
 * projected by the gateway schedule port) and offers the add/remove
 * round-trips. Durable tasks survive restarts via
 * .claude/scheduled_tasks.json; non-durable ones live for this process only.
 *
 * Chrome (overlay/mask/panel/header/options scroll) is the same vendored
 * `SettingsRoot.module.css` shell `SettingsPanel.tsx` uses, minus the nav
 * rail — this panel has only one view. Field rows reuse dsh's own Settings
 * components verbatim: `GeneralSection.module.css`'s section, and
 * `PermissionRow.module.css`'s row/selector pattern for the model seat (the
 * same `MenuSelect` pill SettingsPanel's tier/mode pickers use). Task rows
 * reuse `ScheduleCatalogAction.module.css`'s row/status/statusDot/prompt/
 * metadata classes. dsh's own `ScheduleCatalogAction` is a read-only popover
 * with no mutation UI ("creating and deleting reminders remain with the
 * Schedule tools" — its README) and no `scheduledAt` per row (cron jobs
 * recur, they are not single dated reminders), so overdue/relative-time
 * styling does not apply here; this panel adds its own delete button,
 * add-task form, and per-task model override (a product-scope deviation:
 * dsh's schedule records carry no model — the fired prompt rides the
 * session's current model there).
 *
 * Real `.header` (SettingsRoot.module.css) is a strict 2-child row —
 * `.actions` then `.close` — because the title always lives in the nav
 * rail's `.navTitle`. This panel has no nav rail, so `localCss.headerLeft`
 * (flex:1, its own internal space-between) stands in as the header's first
 * child instead of `.actions` directly, keeping `.header` a 2-child row
 * while giving the title somewhere to live.
 */

/** "No override" choice id; absent on the wire (keeps the session model). */
const NO_MODEL = '__session__'

export function SchedulePanel({ open, onClose }: {
  open: boolean
  onClose: () => void
}) {
  const tasks = useSchedule((state) => state.tasks)
  const loading = useSchedule((state) => state.loading)
  const error = useSchedule((state) => state.error)
  const refresh = useSchedule((state) => state.refresh)
  const addTask = useSchedule((state) => state.addTask)
  const removeTask = useSchedule((state) => state.removeTask)
  const modelSelection = useSessionContext((state) => state.selection)
  const refreshSelection = useSessionContext((state) => state.refresh)

  const [cron, setCron] = useState('')
  const [prompt, setPrompt] = useState('')
  const [model, setModel] = useState<string | null>(null)
  const [recurring, setRecurring] = useState(true)
  const [durable, setDurable] = useState(false)
  const [adding, setAdding] = useState(false)

  const cronProblem = cron.trim() !== '' && cron.trim().split(/\s+/).length !== 5
    ? 'cron 表达式需要 5 个字段：分 时 日 月 周'
    : null
  const valid = cron.trim() !== '' && prompt.trim() !== '' && cronProblem == null && !adding

  useEffect(() => {
    if (!open) return
    void refresh()
    void refreshSelection()
  }, [open, refresh, refreshSelection])

  if (!open) return null

  const modelOptions = [
    { id: NO_MODEL, label: `跟随会话（${modelSelection?.current ?? '当前模型'}）` },
    ...(modelSelection?.models ?? []).map((choice) => ({ id: choice.name, label: choice.label })),
  ]
  const modelLabel = model == null
    ? `跟随会话（${modelSelection?.current ?? '当前模型'}）`
    : modelOptions.find((option) => option.id === model)?.label ?? model

  return (
    <div className={css.overlay} role="presentation">
      <div className={css.mask} aria-hidden="true" onClick={onClose} />
      <div className={css.panel} role="dialog" aria-modal="true" aria-label="定时任务">
        <div className={css.content}>
          <div className={css.header}>
            <div className={localCss.headerLeft}>
              <div className={localCss.headerTitle}>
                <IconAlarmClockOutline16 size={16} />
                定时任务
              </div>
              <div className={css.actions}>
                {loading && <span className={localCss.hint}>加载中…</span>}
                <button
                  type="button"
                  className={localCss.iconButton}
                  aria-label="重新加载"
                  onClick={() => { void refresh() }}
                >
                  <IconRefreshOutline16 size={14} />
                </button>
              </div>
            </div>
            <button type="button" className={css.close} onClick={onClose}>
              <IconCloseOutline16 size={14} />
              <span className={css.hiddenLabel}>关闭定时任务</span>
            </button>
          </div>
          <div className={css.options}>
            {error != null && <div className={localCss.error} role="alert">{error}</div>}

            <ul className={localCss.list}>
              {tasks.length === 0 && !loading && (
                <li className={localCss.empty}>暂无任务。</li>
              )}
              {tasks.map((task) => (
                <TaskRow key={task.id} task={task} onRemove={() => { void removeTask(task.id) }} />
              ))}
            </ul>

            <form
              className={localCss.form}
              onSubmit={(event) => {
                event.preventDefault()
                if (!valid) return
                setAdding(true)
                void addTask(cron.trim(), prompt.trim(), recurring, durable, model)
                  .then((added) => {
                    // A rejected add (400) leaves the input intact so the user
                    // can fix the expression instead of retyping it.
                    if (!added) return
                    setCron('')
                    setPrompt('')
                    setModel(null)
                    setRecurring(true)
                    setDurable(false)
                  })
                  .finally(() => { setAdding(false) })
              }}
            >
              <div className={sectionCss.section}>
                <SettingsRow title="cron 表达式" description="标准 5 字段：分 时 日 月 周，如 0 9 * * *">
                  <div className={localCss.fieldColumn}>
                    <Input
                      value={cron}
                      placeholder="0 9 * * *"
                      aria-label="cron 表达式"
                      aria-invalid={cronProblem != null || undefined}
                      onChange={(event) => { setCron(event.target.value) }}
                    />
                    {cronProblem != null && (
                      <div className={localCss.fieldError} role="alert">{cronProblem}</div>
                    )}
                  </div>
                </SettingsRow>
                <SettingsRow title="要执行的 prompt" description="每次触发时入队发给模型的指令">
                  <div className={localCss.fieldColumn}>
                    <textarea
                      className={localCss.textarea}
                      value={prompt}
                      placeholder="如：检查构建状态并汇报"
                      aria-label="prompt"
                      rows={2}
                      onChange={(event) => { setPrompt(event.target.value) }}
                    />
                  </div>
                </SettingsRow>
                <SettingsRow title="执行模型" description="触发时该任务使用的模型；跟随会话即用当时的会话模型">
                  <MenuSelect
                    value={model ?? NO_MODEL}
                    label={modelLabel}
                    options={modelOptions}
                    onChange={(id) => { setModel(id === NO_MODEL ? null : id) }}
                    ariaLabel="scheduled task model"
                  />
                </SettingsRow>
                <SettingsRow title="循环" description="开启则按周期反复触发；关闭则到点触发一次后自动删除">
                  <Switch
                    checked={recurring}
                    onChange={setRecurring}
                    label="循环触发"
                  />
                </SettingsRow>
                <SettingsRow title="持久" description="写入 .claude/scheduled_tasks.json，跨进程重启保留">
                  <Switch
                    checked={durable}
                    onChange={setDurable}
                    label="跨重启持久"
                  />
                </SettingsRow>
                <div className={localCss.submitRow}>
                  <button type="submit" className={localCss.submit} disabled={!valid}>
                    <IconPlusOutline16 size={14} />
                    添加任务
                  </button>
                </div>
              </div>
            </form>
          </div>
        </div>
      </div>
    </div>
  )
}

function TaskRow({ task, onRemove }: { task: ScheduleTask; onRemove: () => void }) {
  return (
    <li className={clsx(listCss.row, localCss.rowWrap)}>
      <span className={listCss.status}>
        <span className={listCss.statusDot} aria-hidden="true" />
        <span>{task.recurring ? '循环' : '单次'}{task.durable && ' · 持久'}</span>
      </span>
      <span className={listCss.prompt}>{task.prompt}</span>
      <span className={listCss.metadata}>
        <code>{task.cron}</code>
        <span aria-hidden="true">·</span>
        {task.model != null && (
          <>
            <span>{task.model}</span>
            <span aria-hidden="true">·</span>
          </>
        )}
        <span>id {task.id}</span>
        {task.last_fired_at != null && (
          <>
            <span aria-hidden="true">·</span>
            <span>上次触发 {new Date(task.last_fired_at).toLocaleString()}</span>
          </>
        )}
      </span>
      <button type="button" className={localCss.remove} aria-label={`删除任务 ${task.id}`} onClick={onRemove}>
        <IconTrashOutline16 size={14} />
      </button>
    </li>
  )
}

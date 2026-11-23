import { useEffect, useMemo, useState } from 'react'
import clsx from 'clsx'
import {
  Button,
  IconChevronDownOutline14,
  Input,
  Menu,
  Modal,
  StateDot,
  Switch,
  Tag,
  Tooltip,
  type MenuEntry,
  type StateDotState,
} from '@primitives'
import type { ScheduleNotificationMode, ScheduleTask, ScheduleTaskOptions } from '../api/types'
import { useModels } from '../store/models'
import { useSchedule } from '../store/schedule'
import { useScheduleView } from '../store/scheduleView'
import { useSessions, type SessionActivity } from '../store/sessions'
import {
  cronToHumanZh,
  groupRunsByTask,
  stripScheduledSessions,
  taskDisplayTitle,
  type ScheduledSessionRecord,
  type TaskWithRuns,
} from './scheduledSessions'
import { EffortSlider, effortDisplayLabel, isDefaultEffort, isMaxEffort } from './EffortSlider'
import { useTranslate } from '../i18n/useTranslate'
import { UI_NS, uiDicts } from '../i18n/dictionaries/ui'
import css from './ScheduledTasksWorkspace.module.css'

type TabKey = 'tasks' | 'runs'
type PresetKind = 'daily' | 'weekdays' | 'weekly' | 'hourly' | 'custom'

const EFFORT_CHOICES: readonly string[] = ['', 'low', 'medium', 'high', 'max']

const NOTIFICATION_OPTIONS: readonly { readonly id: ScheduleNotificationMode; readonly label: string }[] = [
  { id: 'always', label: '每次完成通知' },
  { id: 'on_error', label: '仅异常通知' },
  { id: 'off', label: '关闭通知' },
]

const WEEKDAY_OPTIONS: readonly { readonly id: string; readonly label: string }[] = [
  { id: '1', label: '周一' },
  { id: '2', label: '周二' },
  { id: '3', label: '周三' },
  { id: '4', label: '周四' },
  { id: '5', label: '周五' },
  { id: '6', label: '周六' },
  { id: '0', label: '周日' },
]

function ClockLoopIcon({ size = 16, className }: { size?: number; className?: string }) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none" className={className} aria-hidden="true">
      <path d="M13.25 8a5.25 5.25 0 1 1-1.4-3.57" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
      <path d="M12.2 2.5v2.3H9.9" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M8 5.2v2.9l2 1.2" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}

function PlayIcon({ size = 14 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none" aria-hidden="true">
      <path d="M5 3.6l7.5 4.4L5 12.4V3.6z" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" />
    </svg>
  )
}

function EditIcon({ size = 14 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none" aria-hidden="true">
      <path d="M11.3 2.7l2 2-7.5 7.5-2.6.6.6-2.6 7.5-7.5z" stroke="currentColor" strokeWidth="1.35" strokeLinejoin="round" />
    </svg>
  )
}

function TrashIcon({ size = 14 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none" aria-hidden="true">
      <path d="M3 4.5h10M6 4.5V3.2c0-.4.3-.7.7-.7h2.6c.4 0 .7.3.7.7v1.3M4.5 4.5l.6 8.3c0 .4.4.7.8.7h4.2c.4 0 .8-.3.8-.7l.6-8.3" stroke="currentColor" strokeWidth="1.35" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}

function ArrowRightIcon() {
  return (
    <svg width="13" height="13" viewBox="0 0 16 16" fill="none" className={css.runArrow} aria-hidden="true">
      <path d="M6 4l4 4-4 4" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}

/** Native DSH anchored dropdown built over @primitives Menu. */
function DshSelect({
  value,
  placeholder,
  options,
  onChange,
  ariaLabel,
  width,
}: {
  value: string
  placeholder: string
  options: readonly { readonly id: string; readonly label: string }[]
  onChange: (next: string) => void
  ariaLabel: string
  width?: number | string
}) {
  const [open, setOpen] = useState(false)
  const selected = options.find((opt) => opt.id === value)
  const items: MenuEntry[] = useMemo(
    () => options.map((opt) => ({ id: opt.id, label: opt.label })),
    [options],
  )
  return (
    <Menu
      open={open}
      portal
      align="start"
      items={items}
      selectedId={value}
      onSelect={(id) => {
        onChange(id)
        setOpen(false)
      }}
      onClose={() => { setOpen(false) }}
      anchor={(
        <button
          type="button"
          className={css.menuTrigger}
          style={width != null ? { width } : undefined}
          aria-label={ariaLabel}
          aria-expanded={open}
          onClick={() => { setOpen((v) => !v) }}
        >
          <span className={clsx(css.menuTriggerText, !selected?.id && css.menuTriggerMuted)}>
            {selected?.label ?? placeholder}
          </span>
          <IconChevronDownOutline14 size={14} />
        </button>
      )}
    />
  )
}

/** Independent left-rail entry aligned identically to ContextDashboardButton and Settings. */
export function ScheduledTasksNavButton({ wide = true }: { wide?: boolean }) {
  const uiT = useTranslate(UI_NS, uiDicts)
  const open = useScheduleView((s) => s.open)
  const toggleView = useScheduleView((s) => s.toggleView)
  const tasks = useSchedule((s) => s.tasks)
  const refreshTasks = useSchedule((s) => s.refresh)
  const projects = useSessions((s) => s.projects)
  const activity = useSessions((s) => s.activity)

  useEffect(() => {
    void refreshTasks()
  }, [refreshTasks])

  const hasLiveRun = useMemo(() => {
    const { scheduledRuns } = stripScheduledSessions(projects)
    return scheduledRuns.some((run) => activity[run.session.id]?.running === true || run.session.headless_open === true)
  }, [projects, activity])

  const label = uiT('sidebar.scheduled')
  return (
    <Tooltip label={label} delayMs={500} disabled={wide}>
      <button
        type="button"
        className={clsx(css.navButton, !wide && css.navButtonRail, open && css.navButtonActive)}
        aria-label={label}
        aria-current={open ? 'page' : undefined}
        onClick={() => { toggleView() }}
      >
        <ClockLoopIcon size={wide ? 16 : 18} className={css.navIcon} />
        {wide && <span className={css.navLabel}>{label}</span>}
        {wide && hasLiveRun && <StateDot state="ongoing" size={10} />}
        {wide && !hasLiveRun && tasks.length > 0 && (
          <span className={css.navCount}>{tasks.length}</span>
        )}
      </button>
    </Tooltip>
  )
}

function formatRelativeTime(iso: string): string {
  const ts = Date.parse(iso)
  if (Number.isNaN(ts)) return ''
  const diffSec = Math.max(0, Math.floor((Date.now() - ts) / 1000))
  if (diffSec < 60) return '刚刚'
  if (diffSec < 3600) return `${Math.floor(diffSec / 60)} 分钟前`
  if (diffSec < 86400) return `${Math.floor(diffSec / 3600)} 小时前`
  const d = new Date(ts)
  const mm = String(d.getMonth() + 1).padStart(2, '0')
  const dd = String(d.getDate()).padStart(2, '0')
  const hh = String(d.getHours()).padStart(2, '0')
  const mi = String(d.getMinutes()).padStart(2, '0')
  return `${mm}-${dd} ${hh}:${mi}`
}

function pad2(n: number): string {
  return n < 10 ? `0${n}` : String(n)
}

function buildCronFromPreset(preset: PresetKind, time: string, weekday: string, customCron: string): string {
  if (preset === 'custom') return customCron.trim()
  const [hhRaw = '09', mmRaw = '00'] = time.split(':')
  const hh = String(Number.parseInt(hhRaw, 10) || 0)
  const mm = String(Number.parseInt(mmRaw, 10) || 0)
  if (preset === 'daily') return `${mm} ${hh} * * *`
  if (preset === 'weekdays') return `${mm} ${hh} * * 1-5`
  if (preset === 'weekly') return `${mm} ${hh} * * ${weekday}`
  return `${mm} * * * *`
}

function parseCronToForm(cron: string): { preset: PresetKind; time: string; weekday: string; customCron: string } {
  const parts = cron.trim().split(/\s+/)
  if (parts.length === 5) {
    const [m = '', h = '', dom = '', mon = '', dow = ''] = parts
    const mNum = /^\d+$/.test(m) ? Number.parseInt(m, 10) : -1
    const hNum = /^\d+$/.test(h) ? Number.parseInt(h, 10) : -1
    if (mNum >= 0 && h === '*' && dom === '*' && mon === '*' && dow === '*') {
      return { preset: 'hourly', time: `00:${pad2(mNum)}`, weekday: '1', customCron: cron }
    }
    if (mNum >= 0 && hNum >= 0 && dom === '*' && mon === '*') {
      const t = `${pad2(hNum)}:${pad2(mNum)}`
      if (dow === '*') return { preset: 'daily', time: t, weekday: '1', customCron: cron }
      if (dow === '1-5') return { preset: 'weekdays', time: t, weekday: '1', customCron: cron }
      if (/^[0-6]$/.test(dow)) return { preset: 'weekly', time: t, weekday: dow, customCron: cron }
    }
  }
  return { preset: 'custom', time: '09:00', weekday: '1', customCron: cron }
}

function taskPromptExcerpt(prompt: string): string {
  const lines = prompt.trim().split(/\r?\n/).map((l) => l.trim()).filter(Boolean)
  if (lines.length <= 1) return ''
  return lines.slice(1).join(' ')
}

function projectBaseName(path: string): string {
  const trimmed = path.replace(/\/+$/, '')
  const idx = trimmed.lastIndexOf('/')
  return idx >= 0 ? trimmed.slice(idx + 1) : trimmed
}

export function ScheduledTasksWorkspace() {
  const uiT = useTranslate(UI_NS, uiDicts)
  const tasks = useSchedule((s) => s.tasks)
  const loading = useSchedule((s) => s.loading)
  const error = useSchedule((s) => s.error)
  const refreshTasks = useSchedule((s) => s.refresh)
  const addTask = useSchedule((s) => s.addTask)
  const updateTask = useSchedule((s) => s.updateTask)
  const toggleTask = useSchedule((s) => s.toggleTask)
  const removeTask = useSchedule((s) => s.removeTask)
  const runTask = useSchedule((s) => s.runTask)

  const models = useModels((s) => s.models)
  const refreshModels = useModels((s) => s.refresh)

  const projects = useSessions((s) => s.projects)
  const activity = useSessions((s) => s.activity)
  const selectByUser = useSessions((s) => s.selectByUser)
  const openSession = useSessions((s) => s.openSession)
  const createSession = useSessions((s) => s.createSession)
  const refreshSessions = useSessions((s) => s.refresh)
  const closeView = useScheduleView((s) => s.closeView)

  const [tab, setTab] = useState<TabKey>('tasks')
  const [modalOpen, setModalOpen] = useState(false)
  const [editingTask, setEditingTask] = useState<ScheduleTask | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<ScheduleTask | null>(null)
  const [runningId, setRunningId] = useState<string | null>(null)
  const [expandedTaskIds, setExpandedTaskIds] = useState<Record<string, boolean>>({})

  useEffect(() => {
    void refreshTasks()
    void refreshModels()
    void refreshSessions()
  }, [refreshTasks, refreshModels, refreshSessions])

  const { regularProjects, scheduledRuns } = useMemo(() => stripScheduledSessions(projects), [projects])
  const { byTask, unmatchedRuns } = useMemo(
    () => groupRunsByTask(tasks, scheduledRuns),
    [tasks, scheduledRuns],
  )

  const durableTasks = useMemo(() => byTask.filter((entry) => entry.task.durable), [byTask])
  const sessionTasks = useMemo(() => byTask.filter((entry) => !entry.task.durable), [byTask])

  function openCreateModal() {
    setEditingTask(null)
    setModalOpen(true)
  }

  function openEditModal(task: ScheduleTask) {
    setEditingTask(task)
    setModalOpen(true)
  }

  async function handleSaveModal(payload: {
    cron: string
    prompt: string
    recurring: boolean
    durable: boolean
    options: ScheduleTaskOptions
  }): Promise<boolean> {
    if (editingTask != null) {
      return updateTask(
        editingTask.id,
        payload.cron,
        payload.prompt,
        payload.recurring,
        payload.durable,
        payload.options,
      )
    }
    return addTask(
      payload.cron,
      payload.prompt,
      payload.recurring,
      payload.durable,
      payload.options.model ?? null,
      payload.options,
    )
  }

  async function handleRunNow(taskId: string) {
    setRunningId(taskId)
    await runTask(taskId)
    setExpandedTaskIds((prev) => ({ ...prev, [taskId]: true }))
    window.setTimeout(() => { void refreshSessions() }, 1200)
    setRunningId(null)
  }

  function handleOpenRun(record: ScheduledSessionRecord) {
    closeView()
    void (record.session.active || record.session.headless_open
      ? selectByUser(record.session.id)
      : openSession(record.session.id, record.projectPath))
  }

  function handleNaturalLanguageCreate() {
    closeView()
    void createSession(null)
  }

  function toggleTaskRuns(taskId: string) {
    setExpandedTaskIds((prev) => ({ ...prev, [taskId]: !prev[taskId] }))
  }

  return (
    <div className={css.root}>
      <header className={css.header}>
        <div className={css.headerLeft}>
          <h1 className={css.title}>{uiT('schedule.title')}</h1>
          <div className={css.segmented} role="tablist" aria-label={uiT('schedule.title')}>
            <button
              type="button"
              role="tab"
              aria-selected={tab === 'tasks'}
              className={clsx(css.segTab, tab === 'tasks' && css.segTabActive)}
              onClick={() => { setTab('tasks') }}
            >
              <span>{uiT('schedule.tabTasks')}</span>
              <span className={css.segCount}>{tasks.length}</span>
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={tab === 'runs'}
              className={clsx(css.segTab, tab === 'runs' && css.segTabActive)}
              onClick={() => { setTab('runs') }}
            >
              <span>{uiT('schedule.tabRuns')}</span>
              <span className={css.segCount}>{scheduledRuns.length}</span>
            </button>
          </div>
        </div>
        <div className={css.headerActions}>
          <Button variant="ghost" size="sm" onClick={handleNaturalLanguageCreate}>
            {uiT('schedule.nlCreate')}
          </Button>
          <Button variant="primary" size="sm" onClick={openCreateModal}>
            {uiT('schedule.new')}
          </Button>
        </div>
      </header>

      <div className={css.body}>
        {error != null && <div className={css.errorBanner}>{error}</div>}

        {tab === 'tasks' && (
          <div className={css.list}>
            {!loading && tasks.length === 0 && (
              <div className={css.emptyState}>
                <ClockLoopIcon size={22} />
                <span>暂无定时任务</span>
              </div>
            )}

            {durableTasks.length > 0 && (
              <>
                {sessionTasks.length > 0 && <div className={css.groupLabel}>常驻</div>}
                {durableTasks.map((entry) => (
                  <TaskRow
                    key={entry.task.id}
                    entry={entry}
                    activity={activity}
                    expanded={expandedTaskIds[entry.task.id] === true}
                    runningNow={runningId === entry.task.id}
                    onToggleRuns={() => { toggleTaskRuns(entry.task.id) }}
                    onToggleEnabled={(next) => { void toggleTask(entry.task.id, next) }}
                    onRunNow={() => { void handleRunNow(entry.task.id) }}
                    onEdit={() => { openEditModal(entry.task) }}
                    onDelete={() => { setDeleteTarget(entry.task) }}
                    onOpenRun={handleOpenRun}
                  />
                ))}
              </>
            )}

            {sessionTasks.length > 0 && (
              <>
                <div className={css.groupLabel}>会话级</div>
                {sessionTasks.map((entry) => (
                  <TaskRow
                    key={entry.task.id}
                    entry={entry}
                    activity={activity}
                    expanded={expandedTaskIds[entry.task.id] === true}
                    runningNow={runningId === entry.task.id}
                    onToggleRuns={() => { toggleTaskRuns(entry.task.id) }}
                    onToggleEnabled={(next) => { void toggleTask(entry.task.id, next) }}
                    onRunNow={() => { void handleRunNow(entry.task.id) }}
                    onEdit={() => { openEditModal(entry.task) }}
                    onDelete={() => { setDeleteTarget(entry.task) }}
                    onOpenRun={handleOpenRun}
                  />
                ))}
              </>
            )}

            {unmatchedRuns.length > 0 && (
              <>
                <div className={css.groupLabel}>历史记录</div>
                {unmatchedRuns.map((record) => (
                  <RunRow
                    key={record.session.id}
                    record={record}
                    fullWidth
                    isLive={activity[record.session.id]?.running === true || record.session.headless_open === true}
                    onOpen={() => { handleOpenRun(record) }}
                  />
                ))}
              </>
            )}
          </div>
        )}

        {tab === 'runs' && (
          <div className={css.list}>
            {scheduledRuns.length === 0 ? (
              <div className={css.emptyState}>
                <ClockLoopIcon size={22} />
                <span>暂无运行记录</span>
              </div>
            ) : (
              scheduledRuns.map((record) => (
                <RunRow
                  key={record.session.id}
                  record={record}
                  fullWidth
                  showTaskTag
                  isLive={activity[record.session.id]?.running === true || record.session.headless_open === true}
                  onOpen={() => { handleOpenRun(record) }}
                />
              ))
            )}
          </div>
        )}
      </div>

      <TaskEditorModal
        open={modalOpen}
        task={editingTask}
        models={models.map((m) => m.model_name)}
        projectPaths={regularProjects.map((p) => p.project_path)}
        onClose={() => { setModalOpen(false) }}
        onSave={handleSaveModal}
      />

      <Modal
        open={deleteTarget != null}
        onClose={() => { setDeleteTarget(null) }}
        title="删除定时任务"
        closeLabel="关闭"
        footer={(
          <>
            <Button variant="outline" onClick={() => { setDeleteTarget(null) }}>取消</Button>
            <Button
              variant="primary"
              style={{ background: '#dc2626', borderColor: '#dc2626' }}
              onClick={() => {
                const target = deleteTarget
                if (target == null) return
                setDeleteTarget(null)
                void removeTask(target.id)
              }}
            >
              确认删除
            </Button>
          </>
        )}
      >
        <p className={css.deleteTargetName}>
          确定要删除「{deleteTarget != null ? taskDisplayTitle(deleteTarget.prompt) : ''}」吗？删除后将停止后续定时触发，已有对话记录仍会保留。
        </p>
      </Modal>
    </div>
  )
}

function TaskEditorModal({
  open,
  task,
  models,
  projectPaths,
  onClose,
  onSave,
}: {
  open: boolean
  task: ScheduleTask | null
  models: readonly string[]
  projectPaths: readonly string[]
  onClose: () => void
  onSave: (payload: {
    cron: string
    prompt: string
    recurring: boolean
    durable: boolean
    options: ScheduleTaskOptions
  }) => Promise<boolean>
}) {
  const [preset, setPreset] = useState<PresetKind>('daily')
  const [time, setTime] = useState('09:00')
  const [weekday, setWeekday] = useState('1')
  const [customCron, setCustomCron] = useState('0 9 * * *')
  const [prompt, setPrompt] = useState('')
  const [recurring, setRecurring] = useState(true)
  const [durable, setDurable] = useState(true)
  const [model, setModel] = useState('')
  const [effort, setEffort] = useState('')
  const [workingDir, setWorkingDir] = useState('')
  const [newSessionPerRun, setNewSessionPerRun] = useState(true)
  const [notificationMode, setNotificationMode] = useState<ScheduleNotificationMode>('always')
  const [archiveOnSuccess, setArchiveOnSuccess] = useState(false)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    if (!open) return
    if (task != null) {
      const parsed = parseCronToForm(task.cron)
      setPreset(parsed.preset)
      setTime(parsed.time)
      setWeekday(parsed.weekday)
      setCustomCron(parsed.customCron)
      setPrompt(task.prompt)
      setRecurring(task.recurring)
      setDurable(task.durable)
      setModel(task.model ?? '')
      setEffort(task.effort ?? '')
      setWorkingDir(task.working_directory ?? '')
      setNewSessionPerRun(task.new_session_per_run !== false)
      setNotificationMode(task.notification_mode ?? 'off')
      setArchiveOnSuccess(task.archive_on_success === true)
    } else {
      setPreset('daily')
      setTime('09:00')
      setWeekday('1')
      setCustomCron('0 9 * * *')
      setPrompt('')
      setRecurring(true)
      setDurable(true)
      setModel('')
      setEffort('')
      setWorkingDir('')
      setNewSessionPerRun(true)
      setNotificationMode('always')
      setArchiveOnSuccess(false)
    }
  }, [open, task])

  const resolvedCron = buildCronFromPreset(preset, time, weekday, customCron)

  const modelOptions = useMemo(() => [
    { id: '', label: '默认模型' },
    ...models.map((name) => ({ id: name, label: name })),
  ], [models])

  const projectOptions = useMemo(() => {
    const unique = new Set(projectPaths)
    if (workingDir && !unique.has(workingDir)) unique.add(workingDir)
    return [
      { id: '', label: '默认工作目录' },
      ...Array.from(unique).map((p) => ({ id: p, label: projectBaseName(p) })),
    ]
  }, [projectPaths, workingDir])

  async function submit() {
    if (!resolvedCron || !prompt.trim() || saving) return
    setSaving(true)
    const ok = await onSave({
      cron: resolvedCron,
      prompt: prompt.trim(),
      recurring,
      durable,
      options: {
        model: model || null,
        effort: effort || null,
        working_directory: workingDir || null,
        new_session_per_run: newSessionPerRun,
        notification_mode: notificationMode,
        archive_on_success: newSessionPerRun ? archiveOnSuccess : false,
        enabled: task != null ? task.enabled !== false : true,
      },
    })
    setSaving(false)
    if (ok) onClose()
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      className={css.editorModal}
      title={task != null ? '编辑定时任务' : '新建定时任务'}
      closeLabel="关闭"
      footer={(
        <>
          <span className={css.cronPreview}>
            {cronToHumanZh(resolvedCron)} · <code className={css.mono}>{resolvedCron}</code>
          </span>
          <Button variant="outline" onClick={onClose}>取消</Button>
          <Button variant="primary" disabled={saving || !prompt.trim()} onClick={() => { void submit() }}>
            {saving ? '保存中' : task != null ? '保存' : '创建'}
          </Button>
        </>
      )}
    >
      <div className={css.modalForm}>
        <div className={css.field}>
          <span className={css.fieldLabel}>执行周期</span>
          <div className={css.scheduleLine}>
            <div className={css.presetGroup}>
              {([
                ['daily', '每天'],
                ['weekdays', '工作日'],
                ['weekly', '每周'],
                ['hourly', '每小时'],
                ['custom', 'Cron'],
              ] as const).map(([key, label]) => (
                <button
                  key={key}
                  type="button"
                  className={clsx(css.presetBtn, preset === key && css.presetBtnActive)}
                  onClick={() => { setPreset(key) }}
                >
                  {label}
                </button>
              ))}
            </div>

            {preset === 'weekly' && (
              <DshSelect
                value={weekday}
                placeholder="星期"
                options={WEEKDAY_OPTIONS}
                onChange={setWeekday}
                ariaLabel="选择星期"
                width={92}
              />
            )}

            {preset !== 'custom' ? (
              <input
                type="time"
                className={css.timeInput}
                value={time}
                onChange={(e) => { setTime(e.target.value) }}
                aria-label="执行时间"
              />
            ) : (
              <Input
                value={customCron}
                placeholder="0 9 * * *"
                onChange={(e) => { setCustomCron(e.currentTarget.value) }}
              />
            )}
          </div>
        </div>

        <div className={css.field}>
          <span className={css.fieldLabel}>任务指令</span>
          <textarea
            className={css.promptArea}
            value={prompt}
            onChange={(e) => { setPrompt(e.target.value) }}
            placeholder="输入定时执行的任务指令"
          />
        </div>

        <div className={css.divider} />

        <div className={css.fieldRow}>
          <div className={css.field}>
            <span className={css.fieldLabel}>模型</span>
            <DshSelect
              value={model}
              placeholder="默认模型"
              options={modelOptions}
              onChange={setModel}
              ariaLabel="选择模型"
            />
          </div>
          <div className={css.field}>
            <div className={css.fieldLabelRow}>
              <span className={css.fieldLabel}>思考强度</span>
              <span
                className={clsx(
                  css.effortValue,
                  !isDefaultEffort(effort) && (
                    isMaxEffort(effort, EFFORT_CHOICES) ? css.effortValueRainbow : css.effortValueBlue
                  ),
                )}
              >
                {effortDisplayLabel(effort)}
              </span>
            </div>
            <div className={css.sliderAlign}>
              <EffortSlider
                choices={EFFORT_CHOICES}
                value={effort}
                onChange={setEffort}
                compact
                ariaLabel="思考强度"
              />
            </div>
          </div>
        </div>

        <div className={css.fieldRow}>
          <div className={css.field}>
            <span className={css.fieldLabel}>通知模式</span>
            <DshSelect
              value={notificationMode}
              placeholder="通知模式"
              options={NOTIFICATION_OPTIONS}
              onChange={(next) => { setNotificationMode(next as ScheduleNotificationMode) }}
              ariaLabel="选择通知模式"
            />
          </div>
          <div className={css.field}>
            <span className={css.fieldLabel}>运行项目</span>
            <DshSelect
              value={workingDir}
              placeholder="默认工作目录"
              options={projectOptions}
              onChange={setWorkingDir}
              ariaLabel="选择运行项目"
            />
          </div>
        </div>

        <div className={css.divider} />

        <div className={css.switchList}>
          <div className={css.switchRow}>
            <span className={css.switchLabel}>每次运行开启新会话</span>
            <Switch checked={newSessionPerRun} onChange={setNewSessionPerRun} label="每次运行开启新会话" />
          </div>
          {newSessionPerRun && (
            <div className={css.switchRow}>
              <span className={css.switchLabel}>成功运行后自动归档</span>
              <Switch checked={archiveOnSuccess} onChange={setArchiveOnSuccess} label="成功运行后自动归档" />
            </div>
          )}
          <div className={css.switchRow}>
            <span className={css.switchLabel}>常驻守护（后台持久化）</span>
            <Switch checked={durable} onChange={setDurable} label="常驻守护" />
          </div>
          <div className={css.switchRow}>
            <span className={css.switchLabel}>循环执行</span>
            <Switch checked={recurring} onChange={setRecurring} label="循环执行" />
          </div>
        </div>
      </div>
    </Modal>
  )
}

function TaskRow({
  entry,
  activity,
  expanded,
  runningNow,
  onToggleRuns,
  onToggleEnabled,
  onRunNow,
  onEdit,
  onDelete,
  onOpenRun,
}: {
  entry: TaskWithRuns
  activity: Readonly<Record<string, SessionActivity>>
  expanded: boolean
  runningNow: boolean
  onToggleRuns: () => void
  onToggleEnabled: (next: boolean) => void
  onRunNow: () => void
  onEdit: () => void
  onDelete: () => void
  onOpenRun: (record: ScheduledSessionRecord) => void
}) {
  const { task, runs } = entry
  const [promptOpen, setPromptOpen] = useState(false)
  const enabled = task.enabled !== false
  const isLive = runs.some(
    (r) => activity[r.session.id]?.running === true || r.session.headless_open === true,
  )
  const dotState: StateDotState = isLive ? 'ongoing' : !enabled ? 'idle' : runs.length > 0 ? 'done' : 'idle'
  const excerpt = taskPromptExcerpt(task.prompt)
  const metaParts = [
    task.cron,
    ...(task.working_directory ? [projectBaseName(task.working_directory)] : []),
    ...(task.model ? [task.model] : []),
    ...(task.effort ? [task.effort] : []),
    ...(task.notification_mode === 'always' ? ['完成通知'] : task.notification_mode === 'on_error' ? ['异常通知'] : []),
  ]

  return (
    <div className={css.taskBlock}>
      <div className={clsx(css.taskRow, !enabled && css.taskRowDisabled)}>
        <div
          className={css.taskPrimary}
          onClick={() => { setPromptOpen((v) => !v) }}
          role="button"
          tabIndex={0}
          onKeyDown={(e) => {
            if (e.key === 'Enter' || e.key === ' ') {
              e.preventDefault()
              setPromptOpen((v) => !v)
            }
          }}
        >
          <span className={css.dotWrap}>
            <StateDot state={dotState} size={10} />
          </span>
          <div className={css.taskText}>
            <div className={css.taskTitleRow}>
              <span className={css.taskTitle}>{taskDisplayTitle(task.prompt)}</span>
              {isLive && <Tag tone="success">运行中</Tag>}
              {!enabled && <Tag tone="neutral">已暂停</Tag>}
            </div>
            {excerpt !== '' && <span className={css.taskExcerpt}>{excerpt}</span>}
          </div>
        </div>

        <div className={css.taskSchedule}>
          <div className={css.scheduleTop}>
            <span className={css.scheduleHuman}>{cronToHumanZh(task.cron)}</span>
            {task.durable && enabled && <Tag tone="info">常驻</Tag>}
          </div>
          <span className={clsx(css.scheduleMeta, css.mono)}>{metaParts.join(' · ')}</span>
        </div>

        <button
          type="button"
          className={clsx(
            css.runsToggle,
            runs.length > 0 && css.runsToggleActive,
            runs.length === 0 && css.runsToggleDisabled,
          )}
          aria-expanded={expanded}
          disabled={runs.length === 0}
          onClick={onToggleRuns}
        >
          <span>{runs.length} 次</span>
          {runs.length > 0 && (
            <IconChevronDownOutline14
              size={13}
              className={clsx(css.chevron, expanded && css.chevronOpen)}
            />
          )}
        </button>

        <div className={css.rowActions}>
          <Tooltip label={enabled ? '点击暂停任务' : '点击启用任务'} delayMs={300}>
            <Switch
              checked={enabled}
              onChange={onToggleEnabled}
              label={enabled ? '暂停定时任务' : '启用定时任务'}
            />
          </Tooltip>
          <Tooltip label="立即运行" delayMs={300}>
            <button
              type="button"
              className={clsx(css.iconBtn, css.iconBtnPlay)}
              aria-label="立即运行"
              disabled={runningNow}
              onClick={onRunNow}
            >
              <PlayIcon />
            </button>
          </Tooltip>
          <Tooltip label="编辑" delayMs={300}>
            <button
              type="button"
              className={clsx(css.iconBtn, css.iconBtnEdit)}
              aria-label="编辑"
              onClick={onEdit}
            >
              <EditIcon />
            </button>
          </Tooltip>
          <Tooltip label="删除" delayMs={300}>
            <button
              type="button"
              className={clsx(css.iconBtn, css.iconBtnDanger)}
              aria-label="删除"
              onClick={onDelete}
            >
              <TrashIcon />
            </button>
          </Tooltip>
        </div>
      </div>

      {promptOpen && <div className={css.promptFull}>{task.prompt}</div>}

      {expanded && runs.length > 0 && (
        <div className={css.runsRail}>
          {runs.map((record) => (
            <RunRow
              key={record.session.id}
              record={record}
              isLive={activity[record.session.id]?.running === true || record.session.headless_open === true}
              onOpen={() => { onOpenRun(record) }}
            />
          ))}
        </div>
      )}
    </div>
  )
}

function RunRow({
  record,
  isLive,
  fullWidth = false,
  showTaskTag = false,
  onOpen,
}: {
  record: ScheduledSessionRecord
  isLive: boolean
  fullWidth?: boolean
  showTaskTag?: boolean
  onOpen: () => void
}) {
  return (
    <button
      type="button"
      className={clsx(css.runRow, fullWidth && css.runRowFull)}
      onClick={onOpen}
    >
      <StateDot state={isLive ? 'ongoing' : 'done'} size={9} />
      <span className={css.runSummary}>
        {record.cleanSummary || `会话 ${record.session.id.slice(0, 8)}`}
      </span>
      {showTaskTag && record.taskId != null && (
        <span className={clsx(css.runTaskRef, css.mono)}>#{record.taskId}</span>
      )}
      <span className={clsx(css.runTime, isLive && css.runTimeLive)}>
        {isLive ? '运行中' : formatRelativeTime(record.session.modified_at)}
      </span>
      <ArrowRightIcon />
    </button>
  )
}

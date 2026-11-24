import { useMemo, useState, type ReactNode } from 'react'
import clsx from 'clsx'
import {
  Button,
  IconCheckOutline14,
  IconChevronDownOutline14,
  IconChevronUpOutline14,
  IconCloseOutline16,
  IconEditOutline16,
  StateDot,
  Tooltip,
} from '@primitives'
import type { MessageState } from '../store/conversations'
import { extractLatestPlan, useGoalPlanDock } from '../store/goalPlanDock'
import { usePermissionMode } from '../store/permissionMode'
import { toast } from '../store/toast'
import css from './GoalPlanDock.module.css'

/**
 * Goal Dock Card pinned above the composer when Goal Mode is active for the
 * current session. Supports inline goal editing, completion toggle, and exit.
 */
export function GoalDockCard({ sessionId }: { readonly sessionId: string | null }): ReactNode {
  const goal = useGoalPlanDock((state) => (sessionId != null ? state.goals[sessionId] : undefined))
  const setGoal = useGoalPlanDock((state) => state.setGoal)
  const toggleGoalStatus = useGoalPlanDock((state) => state.toggleGoalStatus)
  const clearGoal = useGoalPlanDock((state) => state.clearGoal)
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState('')

  if (sessionId == null || goal == null) return null

  const saveEdit = () => {
    const trimmed = draft.trim()
    if (trimmed !== '') {
      setGoal(sessionId, trimmed)
      toast.success('已更新当前目标')
    }
    setEditing(false)
  }

  return (
    <section className={css.root} data-testid="goal-dock-card" aria-label="目标模式">
      <div className={css.body}>
        {editing || goal.text === '' ? (
          <div className={css.goalInputRow}>
            <span className={clsx(css.badge, css.goalBadge)}>
              <StateDot state="ongoing" />
              目标模式
            </span>
            <input
              type="text"
              className={css.goalInput}
              value={editing ? draft : goal.text}
              placeholder="输入本次会话的北极星目标，按 Enter 锁定…"
              autoFocus
              onChange={(e) => {
                if (!editing) setEditing(true)
                setDraft(e.target.value)
              }}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.nativeEvent.isComposing) {
                  e.preventDefault()
                  saveEdit()
                } else if (e.key === 'Escape') {
                  if (goal.text === '') clearGoal(sessionId)
                  setEditing(false)
                }
              }}
            />
            <Button variant="primary" size="sm" onClick={saveEdit}>锁定</Button>
            <button
              type="button"
              className={css.iconBtn}
              aria-label="退出目标模式"
              onClick={() => {
                if (goal.text === '') clearGoal(sessionId)
                setEditing(false)
              }}
            >
              <IconCloseOutline16 size={14} />
            </button>
          </div>
        ) : (
          <div className={css.headerRow}>
            <span className={clsx(css.badge, css.goalBadge)}>
              <StateDot state={goal.status === 'completed' ? 'done' : 'ongoing'} />
              {goal.status === 'completed' ? '目标已完成' : '目标模式'}
            </span>
            <span
              className={clsx(css.summary, goal.status === 'completed' && css.summaryDone)}
              title={goal.text}
            >
              {goal.text}
            </span>
            <div className={css.iconActions}>
              <Tooltip label={goal.status === 'completed' ? '重新激活目标' : '标记目标完成'} side="top">
                <button
                  type="button"
                  className={css.iconBtn}
                  aria-label={goal.status === 'completed' ? '重新激活目标' : '标记目标完成'}
                  data-goal-action="toggle-complete"
                  onClick={() => {
                    toggleGoalStatus(sessionId)
                    toast.success(goal.status === 'completed' ? '已重新激活目标' : '已标记目标完成')
                  }}
                >
                  <IconCheckOutline14 size={14} />
                </button>
              </Tooltip>
              <Tooltip label="编辑目标" side="top">
                <button
                  type="button"
                  className={css.iconBtn}
                  aria-label="编辑目标"
                  data-goal-action="edit"
                  onClick={() => {
                    setDraft(goal.text)
                    setEditing(true)
                  }}
                >
                  <IconEditOutline16 size={14} />
                </button>
              </Tooltip>
              <Tooltip label="退出目标模式" side="top">
                <button
                  type="button"
                  className={css.iconBtn}
                  aria-label="退出目标模式"
                  data-goal-action="close"
                  onClick={() => {
                    clearGoal(sessionId)
                    toast.info('已退出目标模式')
                  }}
                >
                  <IconCloseOutline16 size={14} />
                </button>
              </Tooltip>
            </div>
          </div>
        )}
      </div>
    </section>
  )
}

/**
 * Plan Mode & Plan Review/Approval Dock Card pinned above the composer.
 * Appears automatically whenever the session is in `plan` permission mode OR
 * has an un-dismissed `ExitPlanMode` plan ready for user approval.
 */
export function PlanDockCard({
  sessionId,
  messages,
  onApprovePlan,
}: {
  readonly sessionId: string | null
  readonly messages: readonly MessageState[]
  readonly onApprovePlan: (prompt: string) => void
}): ReactNode {
  const permState = usePermissionMode((state) => state.state)
  const selectPermissionMode = usePermissionMode((state) => state.select)
  const dismissedPlanId = useGoalPlanDock((state) =>
    sessionId != null ? state.dismissedPlans[sessionId] : undefined)
  const dismissPlan = useGoalPlanDock((state) => state.dismissPlan)
  const [expanded, setExpanded] = useState(true)
  const [busy, setBusy] = useState(false)

  const inPlanMode = permState?.current === 'plan'
  const extractedPlan = useMemo(() => extractLatestPlan(messages), [messages])
  const hasPendingPlan = extractedPlan !== null && extractedPlan.id !== dismissedPlanId

  if (sessionId == null || (!inPlanMode && !hasPendingPlan)) return null

  const approveAndExecute = async () => {
    if (busy) return
    setBusy(true)
    try {
      if (inPlanMode) {
        await selectPermissionMode('default')
      }
      if (extractedPlan !== null) {
        dismissPlan(sessionId, extractedPlan.id)
      }
      onApprovePlan('已批准上述规划方案，请开始按步骤执行。')
      toast.success('已批准计划并切换到执行模式')
    } catch (err: unknown) {
      toast.error(err instanceof Error ? `切换执行模式失败：${err.message}` : '切换执行模式失败')
    } finally {
      setBusy(false)
    }
  }

  const exitPlanMode = async () => {
    if (busy) return
    setBusy(true)
    try {
      if (inPlanMode) {
        await selectPermissionMode('default')
      }
      if (extractedPlan !== null) {
        dismissPlan(sessionId, extractedPlan.id)
      }
      toast.info('已退出规划模式')
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className={css.root} data-testid="plan-dock-card" aria-label="规划模式与计划审批">
      <div className={css.body}>
        <div className={css.headerRow}>
          <button
            type="button"
            className={css.headerToggle}
            aria-expanded={expanded}
            onClick={() => { setExpanded((v) => !v) }}
          >
            <span className={clsx(css.badge, css.planBadge)}>
              <StateDot state={hasPendingPlan ? 'ongoing' : 'idle'} />
              {hasPendingPlan ? '待审批计划' : '规划模式'}
            </span>
            <span className={css.summary}>
              {hasPendingPlan
                ? (extractedPlan.steps.length > 0
                    ? `共 ${extractedPlan.steps.length} 个执行步骤 · 等待审阅批准`
                    : '规划草案已就绪 · 等待审阅批准')
                : '当前为只读规划模式，制定方案后可一键批准执行'}
            </span>
            <span className={css.muted} aria-hidden>
              {expanded ? <IconChevronDownOutline14 /> : <IconChevronUpOutline14 />}
            </span>
          </button>
          <Tooltip label="退出规划模式" side="top">
            <button
              type="button"
              className={css.iconBtn}
              aria-label="退出规划模式"
              data-plan-action="exit"
              disabled={busy}
              onClick={() => { void exitPlanMode() }}
            >
              <IconCloseOutline16 size={14} />
            </button>
          </Tooltip>
        </div>

        {expanded && (
          <>
            {hasPendingPlan && extractedPlan.steps.length > 0 ? (
              <ol className={css.planList}>
                {extractedPlan.steps.map((step, i) => (
                  <li key={i} className={css.planStep}>
                    <span className={css.stepNum}>{i + 1}</span>
                    <span>{step}</span>
                  </li>
                ))}
              </ol>
            ) : hasPendingPlan ? (
              <pre className={css.planRaw}>{extractedPlan.raw}</pre>
            ) : null}

            <div className={css.footerActions}>
              <Button
                variant="ghost"
                size="sm"
                disabled={busy}
                onClick={() => { void exitPlanMode() }}
              >
                退出规划
              </Button>
              <Button
                variant="primary"
                size="sm"
                disabled={busy}
                data-plan-action="approve"
                onClick={() => { void approveAndExecute() }}
              >
                {busy ? '切换中…' : '批准并开始执行'}
              </Button>
            </div>
          </>
        )}
      </div>
    </section>
  )
}

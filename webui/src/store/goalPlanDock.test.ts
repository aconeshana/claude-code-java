import { beforeEach, describe, expect, it } from 'vitest'
import {
  extractLatestPlan,
  parseGoalSlashInput,
  useGoalPlanDock,
} from './goalPlanDock'

beforeEach(() => {
  window.localStorage.clear()
  useGoalPlanDock.getState().resetAll()
})

describe('goalPlanDock store & parsers', () => {
  it('parses /goal command text and ignores unrelated inputs', () => {
    expect(parseGoalSlashInput('/goal 实现多源 @ 引用并完成端到端验收')).toBe('实现多源 @ 引用并完成端到端验收')
    expect(parseGoalSlashInput('/goal')).toBe('')
    expect(parseGoalSlashInput('/plan design')).toBeNull()
    expect(parseGoalSlashInput('normal prompt')).toBeNull()
  })

  it('manages per-session Goal lifecycle (set, complete, reopen, clear)', () => {
    const store = useGoalPlanDock.getState()
    store.setGoal('s1', '完成三栏右侧栏对齐')
    expect(useGoalPlanDock.getState().goals['s1']?.text).toBe('完成三栏右侧栏对齐')
    expect(useGoalPlanDock.getState().goals['s1']?.status).toBe('active')
    expect(useGoalPlanDock.getState().goals['s2']).toBeUndefined()

    store.toggleGoalStatus('s1')
    expect(useGoalPlanDock.getState().goals['s1']?.status).toBe('completed')

    store.toggleGoalStatus('s1')
    expect(useGoalPlanDock.getState().goals['s1']?.status).toBe('active')

    store.clearGoal('s1')
    expect(useGoalPlanDock.getState().goals['s1']).toBeUndefined()
  })

  it('extracts plan content from ExitPlanMode tool call in transcript', () => {
    const plan = extractLatestPlan([
      {
        id: 'm1',
        kind: 'assistant',
        open: false,
        textBlocks: ['Here is the plan.'],
        thinkingBlocks: [],
        toolCalls: [
          {
            toolUseId: 'tu-1',
            name: 'ExitPlanMode',
            args: { plan: '1. 创建 GoalDockCard\n2. 创建 PlanDockCard\n3. 端到端验证' },
            status: 'executed',
            ready: true,
            resultType: null,
            resultData: null,
            resultError: null,
            locations: null,
            transcriptPath: null,
            progress: [],
          },
        ],
      },
    ])
    expect(plan).not.toBeNull()
    expect(plan?.id).toBe('tu-1')
    expect(plan?.steps).toEqual([
      '创建 GoalDockCard',
      '创建 PlanDockCard',
      '端到端验证',
    ])
  })

  it('tracks dismissed plan ids per session without affecting other sessions', () => {
    useGoalPlanDock.getState().dismissPlan('s1', 'tu-1')
    expect(useGoalPlanDock.getState().dismissedPlans['s1']).toBe('tu-1')
    expect(useGoalPlanDock.getState().dismissedPlans['s2']).toBeUndefined()
  })
})

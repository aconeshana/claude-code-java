import { create } from 'zustand'
import type { MessageState } from './conversations'

export type GoalStatus = 'active' | 'completed'

export interface SessionGoal {
  readonly text: string
  readonly status: GoalStatus
  readonly updatedAt: number
}

export interface ExtractedPlan {
  readonly id: string
  readonly raw: string
  readonly steps: readonly string[]
}

const STORAGE_KEY = 'poco.goal-plan-dock.v1'

interface PersistedShape {
  goals: Record<string, SessionGoal>
  dismissedPlans: Record<string, string>
}

function loadPersisted(): PersistedShape {
  if (typeof window === 'undefined') return { goals: {}, dismissedPlans: {} }
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY)
    if (raw === null) return { goals: {}, dismissedPlans: {} }
    const parsed = JSON.parse(raw) as Partial<PersistedShape>
    return {
      goals: parsed.goals && typeof parsed.goals === 'object' ? parsed.goals : {},
      dismissedPlans: parsed.dismissedPlans && typeof parsed.dismissedPlans === 'object'
        ? parsed.dismissedPlans
        : {},
    }
  } catch {
    return { goals: {}, dismissedPlans: {} }
  }
}

function savePersisted(shape: PersistedShape): void {
  if (typeof window === 'undefined') return
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(shape))
  } catch {
    // Ignore storage quota errors.
  }
}

/**
 * Parse a `/goal ...` slash command line. Returns the trimmed goal text
 * (possibly empty for bare `/goal`), or null if the input is not a `/goal` command.
 */
export function parseGoalSlashInput(input: string): string | null {
  const trimmed = input.trim()
  if (trimmed === '/goal') return ''
  if (trimmed.startsWith('/goal ')) return trimmed.slice('/goal '.length).trim()
  return null
}

function extractSteps(raw: string): string[] {
  const lines = raw.split('\n').map((l) => l.trim()).filter(Boolean)
  const steps: string[] = []
  for (const line of lines) {
    const numbered = /^\d+[.)]\s+(.+)$/.exec(line)
    if (numbered !== null) {
      steps.push(numbered[1]!.trim())
      continue
    }
    const bullet = /^[-*•]\s+(?:\[[ xX]\]\s+)?(.+)$/.exec(line)
    if (bullet !== null) {
      steps.push(bullet[1]!.trim())
    }
  }
  return steps.slice(0, 12)
}

/**
 * Scan the conversation transcript from newest to oldest for the latest
 * `ExitPlanMode` tool call carrying a plan string.
 */
export function extractLatestPlan(messages: readonly MessageState[]): ExtractedPlan | null {
  for (let i = messages.length - 1; i >= 0; i -= 1) {
    const msg = messages[i]
    if (msg === undefined || msg.kind !== 'assistant') continue
    for (let j = msg.toolCalls.length - 1; j >= 0; j -= 1) {
      const call = msg.toolCalls[j]
      if (call === undefined) continue
      if (call.name === 'ExitPlanMode') {
        const planText = typeof call.args?.plan === 'string'
          ? call.args.plan.trim()
          : (call.resultData ?? '').trim()
        if (planText !== '') {
          return {
            id: call.toolUseId,
            raw: planText,
            steps: extractSteps(planText),
          }
        }
      }
    }
  }
  return null
}

interface GoalPlanDockState {
  readonly goals: Readonly<Record<string, SessionGoal>>
  readonly dismissedPlans: Readonly<Record<string, string>>
  readonly setGoal: (sessionId: string, text: string) => void
  readonly toggleGoalStatus: (sessionId: string) => void
  readonly clearGoal: (sessionId: string) => void
  readonly dismissPlan: (sessionId: string, planId: string) => void
  readonly resetAll: () => void
}

const initial = loadPersisted()

export const useGoalPlanDock = create<GoalPlanDockState>((set, get) => ({
  goals: initial.goals,
  dismissedPlans: initial.dismissedPlans,

  setGoal: (sessionId, text) => {
    const nextGoals: Record<string, SessionGoal> = {
      ...get().goals,
      [sessionId]: { text, status: 'active', updatedAt: Date.now() },
    }
    savePersisted({ goals: nextGoals, dismissedPlans: { ...get().dismissedPlans } })
    set({ goals: nextGoals })
  },

  toggleGoalStatus: (sessionId) => {
    const current = get().goals[sessionId]
    if (current === undefined) return
    const nextStatus: GoalStatus = current.status === 'completed' ? 'active' : 'completed'
    const nextGoals: Record<string, SessionGoal> = {
      ...get().goals,
      [sessionId]: { ...current, status: nextStatus, updatedAt: Date.now() },
    }
    savePersisted({ goals: nextGoals, dismissedPlans: { ...get().dismissedPlans } })
    set({ goals: nextGoals })
  },

  clearGoal: (sessionId) => {
    const nextGoals: Record<string, SessionGoal> = { ...get().goals }
    delete nextGoals[sessionId]
    savePersisted({ goals: nextGoals, dismissedPlans: { ...get().dismissedPlans } })
    set({ goals: nextGoals })
  },

  dismissPlan: (sessionId, planId) => {
    const nextDismissed: Record<string, string> = {
      ...get().dismissedPlans,
      [sessionId]: planId,
    }
    savePersisted({ goals: { ...get().goals }, dismissedPlans: nextDismissed })
    set({ dismissedPlans: nextDismissed })
  },

  resetAll: () => {
    savePersisted({ goals: {}, dismissedPlans: {} })
    set({ goals: {}, dismissedPlans: {} })
  },
}))

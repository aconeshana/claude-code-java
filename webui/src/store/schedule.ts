import { create } from 'zustand'
import { addScheduleTask, fetchScheduleTasks, removeScheduleTask } from '../api/client'
import type { ScheduleTask } from '../api/types'

/**
 * Scheduled-task panel state: the process-wide cron job list plus add/remove
 * methods. The gateway is the source of truth; every mutation refreshes the
 * list from the listing endpoint.
 */

export interface ScheduleStore {
  readonly tasks: readonly ScheduleTask[]
  readonly loading: boolean
  readonly error: string | null
  refresh(): Promise<void>
  addTask(cron: string, prompt: string, recurring: boolean, durable: boolean, model: string | null): Promise<boolean>
  removeTask(id: string): Promise<void>
}

export const useSchedule = create<ScheduleStore>((set, get) => ({
  tasks: [],
  loading: false,
  error: null,

  async refresh() {
    set({ loading: true })
    try {
      const listing = await fetchScheduleTasks()
      set({ tasks: listing.tasks, loading: false, error: null })
    } catch (failure) {
      set({ loading: false, error: messageOf(failure) })
    }
  },

  async addTask(cron, prompt, recurring, durable, model) {
    try {
      await addScheduleTask(cron, prompt, recurring, durable, model)
      await get().refresh()
      return true
    } catch (failure) {
      set({ error: messageOf(failure) })
      return false
    }
  },

  async removeTask(id) {
    try {
      await removeScheduleTask(id)
      set({ tasks: get().tasks.filter((task) => task.id !== id), error: null })
    } catch (failure) {
      set({ error: messageOf(failure) })
    }
  },
}))

function messageOf(failure: unknown): string {
  return failure instanceof Error ? failure.message : String(failure)
}

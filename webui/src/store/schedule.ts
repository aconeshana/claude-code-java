import { create } from 'zustand'
import {
  addScheduleTask,
  fetchScheduleTasks,
  removeScheduleTask,
  runScheduleTask,
  toggleScheduleTask,
  updateScheduleTask,
} from '../api/client'
import type { ScheduleTask, ScheduleTaskOptions } from '../api/types'

/**
 * Scheduled-task panel state: the process-wide cron job list plus add/edit/remove
 * methods. The gateway is the source of truth; every mutation refreshes the
 * list from the listing endpoint.
 */

export interface ScheduleStore {
  readonly tasks: readonly ScheduleTask[]
  readonly loading: boolean
  readonly error: string | null
  refresh(): Promise<void>
  addTask(
    cron: string,
    prompt: string,
    recurring: boolean,
    durable: boolean,
    model: string | null,
    options?: ScheduleTaskOptions,
  ): Promise<boolean>
  updateTask(
    id: string,
    cron: string,
    prompt: string,
    recurring: boolean,
    durable: boolean,
    options?: ScheduleTaskOptions,
  ): Promise<boolean>
  toggleTask(id: string, enabled: boolean): Promise<boolean>
  removeTask(id: string): Promise<void>
  runTask(id: string): Promise<boolean>
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

  async addTask(cron, prompt, recurring, durable, model, options) {
    try {
      await addScheduleTask(cron, prompt, recurring, durable, model, options)
      await get().refresh()
      return true
    } catch (failure) {
      set({ error: messageOf(failure) })
      return false
    }
  },

  async updateTask(id, cron, prompt, recurring, durable, options) {
    try {
      await updateScheduleTask(id, cron, prompt, recurring, durable, options)
      await get().refresh()
      return true
    } catch (failure) {
      set({ error: messageOf(failure) })
      return false
    }
  },

  async toggleTask(id, enabled) {
    const previous = get().tasks
    set({
      tasks: previous.map((t) => (t.id === id ? { ...t, enabled } : t)),
      error: null,
    })
    try {
      await toggleScheduleTask(id, enabled)
      await get().refresh()
      return true
    } catch (failure) {
      set({ tasks: previous, error: messageOf(failure) })
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

  async runTask(id) {
    try {
      await runScheduleTask(id)
      set({ error: null })
      return true
    } catch (failure) {
      set({ error: messageOf(failure) })
      return false
    }
  },
}))

function messageOf(failure: unknown): string {
  return failure instanceof Error ? failure.message : String(failure)
}

import { create } from 'zustand'

export type ToastKind = 'info' | 'success' | 'warning' | 'error'

export interface ToastItem {
  readonly id: number
  readonly kind: ToastKind
  readonly message: string
  readonly holdMs: number
}

interface ToastStoreState {
  readonly queue: readonly ToastItem[]
  readonly push: (kind: ToastKind, message: string, holdMs?: number) => number
  readonly dismiss: (id: number) => void
  readonly clear: () => void
}

let nextToastId = 1

export const useToast = create<ToastStoreState>((set) => ({
  queue: [],
  push: (kind, message, holdMs) => {
    const id = nextToastId++
    const resolvedHold = holdMs ?? (kind === 'error' ? 4500 : 2800)
    set((state) => ({
      // Keep at most 4 queued items to avoid backlog storms.
      queue: [...state.queue.slice(-3), { id, kind, message, holdMs: resolvedHold }],
    }))
    return id
  },
  dismiss: (id) => {
    set((state) => ({ queue: state.queue.filter((item) => item.id !== id) }))
  },
  clear: () => {
    set({ queue: [] })
  },
}))

export const toast = {
  info: (message: string, holdMs?: number) => useToast.getState().push('info', message, holdMs),
  success: (message: string, holdMs?: number) => useToast.getState().push('success', message, holdMs),
  warning: (message: string, holdMs?: number) => useToast.getState().push('warning', message, holdMs),
  error: (message: string, holdMs?: number) => useToast.getState().push('error', message, holdMs),
} as const

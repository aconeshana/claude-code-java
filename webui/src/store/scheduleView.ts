import { create } from 'zustand'

interface ScheduleViewState {
  readonly open: boolean
  openView(): void
  closeView(): void
  toggleView(): void
}

/** Controls whether the main workspace displays the dedicated Scheduled Tasks center. */
export const useScheduleView = create<ScheduleViewState>((set) => ({
  open: false,
  openView: () => { set({ open: true }) },
  closeView: () => { set({ open: false }) },
  toggleView: () => { set((state) => ({ open: !state.open })) },
}))

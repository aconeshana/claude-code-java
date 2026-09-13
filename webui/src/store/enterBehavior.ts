/**
 * Busy-Enter delivery preference (mirrors dsh ui-conversation
 * submission-settings.ts's BusyEnterBehavior).
 *
 * The gateway has no route to inject a message into a running turn, so
 * `steer` has no backend hook here — it behaves identically to sending
 * immediately. `queue` is implemented for real: InputBar holds the draft
 * client-side while a turn is running and flushes it once the turn ends.
 */
import { create } from 'zustand'

export const BUSY_ENTER_BEHAVIORS = ['queue', 'steer'] as const

export type BusyEnterBehavior = (typeof BUSY_ENTER_BEHAVIORS)[number]

const STORAGE_KEY = 'webui-enter-behavior'
const DEFAULT_BEHAVIOR: BusyEnterBehavior = 'queue'

function readBehavior(): BusyEnterBehavior {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return (BUSY_ENTER_BEHAVIORS as readonly string[]).includes(raw ?? '')
      ? (raw as BusyEnterBehavior)
      : DEFAULT_BEHAVIOR
  } catch {
    return DEFAULT_BEHAVIOR
  }
}

export interface EnterBehaviorStore {
  readonly behavior: BusyEnterBehavior
  setBehavior(next: BusyEnterBehavior): void
}

export const useEnterBehavior = create<EnterBehaviorStore>(set => ({
  behavior: readBehavior(),

  setBehavior(next) {
    try {
      localStorage.setItem(STORAGE_KEY, next)
    } catch { /* storage unavailable (private mode): preference stays session-only */ }
    set({ behavior: next })
  },
}))

window.addEventListener('storage', (event) => {
  if (event.key === STORAGE_KEY) useEnterBehavior.setState({ behavior: readBehavior() })
})

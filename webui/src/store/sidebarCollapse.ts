/**
 * Sidebar collapse (rail) preference, ported from dsh ui-sidebar's
 * SidebarRoot.tsx collapse state — see webui/UPSTREAM.md for the port.
 *
 * Upstream holds this in a cross-slot `ui-layout` service (shared with
 * drag-resize and the right sidebar this app does not have). This app has no
 * such layout-owner layer, so a persisted local preference stands in for it,
 * mirroring the existing store/transcriptView.ts pattern.
 */
import { create } from 'zustand'

const STORAGE_KEY = 'webui-sidebar-collapsed'

function readCollapsed(): boolean {
  try {
    return localStorage.getItem(STORAGE_KEY) === 'true'
  } catch {
    return false
  }
}

function writeCollapsed(next: boolean): void {
  try {
    localStorage.setItem(STORAGE_KEY, String(next))
  } catch { /* storage unavailable (private mode): preference stays session-only */ }
}

export interface SidebarCollapseStore {
  readonly collapsed: boolean
  toggle(): void
  setCollapsed(next: boolean): void
}

export const useSidebarCollapse = create<SidebarCollapseStore>((set, get) => ({
  collapsed: readCollapsed(),

  toggle() {
    const next = !get().collapsed
    writeCollapsed(next)
    set({ collapsed: next })
  },

  setCollapsed(next) {
    writeCollapsed(next)
    set({ collapsed: next })
  },
}))

window.addEventListener('storage', (event) => {
  if (event.key === STORAGE_KEY) useSidebarCollapse.setState({ collapsed: readCollapsed() })
})

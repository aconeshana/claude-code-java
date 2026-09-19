import type { ReactNode } from 'react'
import css from '@chat-styles/AppFrame.module.css'

/** Default wide-sidebar track width; Sidebar.tsx freezes its content to this width while collapsing. */
export const SIDEBAR_WIDTH = 260

/** Rail track width, matching SidebarRoot.module.css's collapsed geometry (56px). */
const SIDEBAR_RAIL_WIDTH = 56

/**
 * Two-column application frame: the session sidebar and the conversation
 * column, over the vendored dsh ui-layout frame grid (fixed sidebar track
 * via inline style; drag-to-resize is not part of v1). The grid track
 * transitions between the wide and rail width — `.frame`'s
 * `transition: grid-template-columns` (vendored verbatim) does the slide the
 * sidebar's collapse animation clips against.
 */
export function AppFrame({ sidebar, sidebarWidth = SIDEBAR_WIDTH, collapsed = false, children }: {
  sidebar: ReactNode
  sidebarWidth?: number
  collapsed?: boolean
  children: ReactNode
}) {
  return (
    <div
      className={css.frame}
      style={{ gridTemplateColumns: `${collapsed ? SIDEBAR_RAIL_WIDTH : sidebarWidth}px minmax(0, 1fr)` }}
    >
      <div className={css.sidebarCol}>{sidebar}</div>
      <div className={css.centerCol}>{children}</div>
    </div>
  )
}

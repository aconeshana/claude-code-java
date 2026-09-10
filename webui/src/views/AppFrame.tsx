import type { ReactNode } from 'react'
import css from '@chat-styles/AppFrame.module.css'

/**
 * Two-column application frame: the session sidebar and the conversation
 * column, over the vendored dsh ui-layout frame grid (fixed sidebar track
 * via inline style; drag-to-resize is not part of v1).
 */
export function AppFrame({ sidebar, sidebarWidth = 260, children }: {
  sidebar: ReactNode
  sidebarWidth?: number
  children: ReactNode
}) {
  return (
    <div
      className={css.frame}
      style={{ gridTemplateColumns: `${sidebarWidth}px minmax(0, 1fr)` }}
    >
      <div className={css.sidebarCol}>{sidebar}</div>
      <div className={css.centerCol}>{children}</div>
    </div>
  )
}

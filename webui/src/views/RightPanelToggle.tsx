import type { ReactNode } from 'react'
import { IconPanelLeftOutline16, Tooltip } from '@primitives'
import { useRightSidebar } from '../store/rightSidebar'
import { useSessions } from '../store/sessions'
import css from './RightPanelToggle.module.css'

/**
 * Conversation header's right-sidebar expand button (matches upstream
 * `ui-sidebar-right/ExpandButton.tsx`): visible whenever a session is selected
 * and the right dock is collapsed; hides while the dock is open (its own tab
 * strip carries the collapse & fullscreen controls).
 */
export function RightPanelToggle(): ReactNode {
  const sessionId = useSessions((state) => state.selectedSessionId)
  const expanded = useRightSidebar((state) =>
    sessionId != null ? state.surfaces[sessionId]?.state.expanded === true : false)
  const expand = useRightSidebar((state) => state.expand)

  if (sessionId == null || expanded) return null
  return (
    <Tooltip label="展开右侧栏" side="bottom" delayMs={400}>
      <button
        type="button"
        className={css.toggle}
        aria-label="展开右侧栏"
        aria-pressed={false}
        data-right-panel-toggle
        onClick={() => { expand(sessionId) }}
      >
        <IconPanelLeftOutline16 className={css.icon} />
      </button>
    </Tooltip>
  )
}

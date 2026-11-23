import type { ReactNode } from 'react'
import {
  IconBranchOutline16,
  IconCodeOutline16,
  IconFolderOpenOutline16,
} from '@primitives'
import { IconUsersOutline } from '@vendor/ui-subagent/IconUsersOutline'
import guideCss from '@chat-styles/GuideBody.module.css'
import type { PaneId, TabId } from '@vendor/ui-dockkit'

export interface GuideQuickItem {
  readonly id: string
  readonly title: string
  readonly description: string
  readonly icon: ReactNode
  readonly onOpen: () => void
}

export function GuideTab({
  paneId,
  tabId,
  items,
}: {
  readonly paneId: PaneId
  readonly tabId: TabId
  readonly items: readonly GuideQuickItem[]
}): ReactNode {
  const showDescriptions = items.length <= 4
  return (
    <div className={guideCss.guide} data-guide-tab={tabId} data-pane-id={paneId} style={{ height: '100%' }}>
      {items.map((item) => (
        <button
          key={item.id}
          type="button"
          className={guideCss.entry}
          onClick={item.onOpen}
        >
          <span className={guideCss.entryIcon}>{item.icon}</span>
          <span className={guideCss.entryText}>
            <span className={guideCss.entryTitle}>{item.title}</span>
            {showDescriptions && item.description !== '' && (
              <span className={guideCss.entryDescription}>{item.description}</span>
            )}
          </span>
        </button>
      ))}
    </div>
  )
}

export const GUIDE_ICONS = {
  files: <IconFolderOpenOutline16 size={18} />,
  review: <IconBranchOutline16 size={18} />,
  subagent: <IconUsersOutline size={18} />,
  shell: <IconCodeOutline16 size={18} />,
} as const

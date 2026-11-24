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

export function CompassGlyph({ size = 16, className }: { readonly size?: number; readonly className?: string }): ReactNode {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none" aria-hidden="true" className={className}>
      <path d="M8 14C11.3137 14 14 11.3137 14 8C14 4.68629 11.3137 2 8 2C4.68629 2 2 4.68629 2 8C2 11.3137 4.68629 14 8 14Z" stroke="currentColor" />
      <path d="M10.6101 5.39014L8.99014 8.99014L5.39014 10.6101L7.01014 7.01014L10.6101 5.39014Z" fill="currentColor" />
    </svg>
  )
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
      <span
        className={guideCss.hero}
        aria-hidden="true"
        style={{ color: 'var(--dsw-specific-guide-hero, var(--dsw-alias-border-l1))' }}
      >
        <CompassGlyph size={56} />
      </span>
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

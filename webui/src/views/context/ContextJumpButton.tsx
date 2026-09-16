/**
 * The assistant-row action that jumps to the Context tab at this reply's
 * turn — claude-code-java seat of dsh-context's `contextJump.tsx`. Seated
 * in the settled turn tail's icon row (beside copy, where upstream's
 * `conversation.chat.assistant-actions` slot puts it). It records the
 * reply's timestamp + turn in the store relay and flips the conversation
 * view; ContextView resolves and pins the turn (`jump.ts`).
 */

import type { ReactElement } from 'react'
import { Tooltip } from '@primitives'
import { useContextTimeline } from '../../store/contextTimeline'
import { useConversationView } from '../../store/conversationView'
import { useContextKit } from './useContextKit'
import '@dsh-context/index'

/** The jump glyph: the plugin's mini stacked composition bars (upstream JumpIcon). */
function JumpIcon(): ReactElement {
  return (
    <svg width="16" height="16" viewBox="0 0 16 16" className="fill-none" aria-hidden="true">
      <rect x="2" y="3" width="12" height="2" rx="1" className="fill-current" />
      <rect x="2" y="7" width="8.5" height="2" rx="1" className="fill-current" />
      <rect x="2" y="11" width="5.5" height="2" rx="1" className="fill-current" />
    </svg>
  )
}

export function ContextJumpButton({ sessionId, time, turn }: {
  sessionId: string | null
  time?: number | undefined
  turn?: number | undefined
}): ReactElement | null {
  const { kit } = useContextKit()
  const requestJump = useContextTimeline((state) => state.requestJump)
  const setView = useConversationView((state) => state.setView)
  if (sessionId === null) return null
  const label = kit.t('jump.title')
  const jump = (): void => {
    if (time !== undefined || turn !== undefined) requestJump(sessionId, { time, turn })
    setView(sessionId, 'context')
  }
  return (
    <Tooltip label={label} side="bottom">
      <button
        type="button"
        className="lc-jump hover:bg-(--dsw-alias-interactive-bg-hover) hover:text-(--dsw-alias-label-secondary)"
        aria-label={label}
        onClick={jump}
      >
        <JumpIcon />
      </button>
    </Tooltip>
  )
}

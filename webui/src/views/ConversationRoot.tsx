import { useEffect, useRef, type ReactNode } from 'react'
import css from '@chat-styles/ConversationRoot.module.css'

/**
 * Conversation column shell over the vendored dsh ui-conversation root:
 * the breadcrumb header, the transcript scrollport, and the composer seat.
 * The root block carries the shared width-axis variables
 * (--dsh-chat-content-width etc.) that every vendored chat style resolves
 * against, so this component must stay the owner of that class.
 */
export function ConversationRoot({ title, subtitle, transcript, composer }: {
  title: string
  subtitle?: string | undefined
  transcript: ReactNode
  composer: ReactNode
}) {
  const rootRef = useRef<HTMLDivElement>(null)
  const composerRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const el = rootRef.current
    if (el == null) return
    // --dsh-chat-content-width clamps 64% of this live column width; without
    // publishing it, the clamp floor (680px) never grows past a narrow window.
    const observer = new ResizeObserver(([entry]) => {
      if (entry != null) el.style.setProperty('--dsh-conversation-column-width', `${entry.contentRect.width}px`)
    })
    observer.observe(el)
    return () => { observer.disconnect() }
  }, [])

  useEffect(() => {
    const root = rootRef.current
    const seat = composerRef.current
    if (root == null || seat == null) return
    // ChatView's back-to-bottom button floats at composer-height + 16px; a
    // growing textarea (up to 200px) must keep that offset accurate.
    const observer = new ResizeObserver(([entry]) => {
      if (entry != null) root.style.setProperty('--dsh-composer-height', `${entry.contentRect.height}px`)
    })
    observer.observe(seat)
    return () => { observer.disconnect() }
  }, [])

  return (
    <div ref={rootRef} className={css.root}>
      <div className={css.header}>
        <div className={css.titleRow}>
          <div className={css.titleCluster}>
            <nav className={css.crumbs}>
              <span className={`${css.crumb} ${css.crumbCurrent}`}>{title}</span>
              {subtitle != null && <span className={css.crumbSubagent}>{subtitle}</span>}
            </nav>
          </div>
        </div>
      </div>
      {transcript}
      <div ref={composerRef}>{composer}</div>
    </div>
  )
}

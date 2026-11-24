import { useEffect, useRef, type ReactNode } from 'react'
import css from '@chat-styles/ConversationRoot.module.css'

/**
 * Conversation column shell over the vendored dsh ui-conversation root:
 * the breadcrumb header, the transcript scrollport, and the composer seat.
 * The root block carries the shared width-axis variables
 * (--dsh-chat-content-width etc.) that every vendored chat style resolves
 * against, so this component must stay the owner of that class.
 */
export type ConversationView = 'chat' | 'trajectory' | 'context'

export interface BreadcrumbChild {
  readonly title: string
  readonly badge?: string | undefined
  readonly onExit: () => void
}

export function ConversationRoot({ title, subtitle, breadcrumbChild, transcript, composer, view, onView, trajectory, context, tabLabels, headerActions }: {
  title: string
  subtitle?: string | undefined
  /**
   * When viewing a subagent conversation or background shell, renders the
   * vendored breadcrumb lineage (`Parent / Child`) so clicking the parent crumb
   * returns to the main conversation.
   */
  breadcrumbChild?: BreadcrumbChild | undefined
  transcript: ReactNode
  composer: ReactNode
  /**
   * The header's actions band, trailing the title cluster — dsh's
   * `conversation.session.header.actions` slot, where upstream seats the
   * subagent descendant-count catalog beside the background-task control.
   */
  headerActions?: ReactNode | undefined
  /**
   * The active face of the column (dsh's `conversation.view` tab ring:
   * Chat | Trajectory | Context). Omitted = chat only, no tab strip.
   */
  view?: ConversationView | undefined
  onView?: ((view: ConversationView) => void) | undefined
  /** The Trajectory tab's body (with optional floating composer overlay). */
  trajectory?: ReactNode
  /** The Context tab's body (rendered in place of the transcript + composer). */
  context?: ReactNode
  tabLabels?: { chat: string; trajectory?: string; context: string } | undefined
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
    if (root == null) return
    if (seat == null) {
      root.style.setProperty('--dsh-composer-height', '0px')
      return
    }
    // ChatView's back-to-bottom button floats at composer-height + 16px; a
    // growing textarea (up to 200px) must keep that offset accurate.
    const observer = new ResizeObserver(([entry]) => {
      if (entry != null) root.style.setProperty('--dsh-composer-height', `${entry.contentRect.height}px`)
    })
    observer.observe(seat)
    return () => { observer.disconnect() }
    // The composer seat remounts when the Context tab hands the column back.
  }, [view, composer])

  return (
    <div ref={rootRef} className={css.root} data-phase={view === 'trajectory' ? 'active' : undefined}>
      <div className={css.header}>
        <div className={css.titleRow}>
          <div className={css.titleCluster}>
            <nav className={css.crumbs} aria-label="会话路径">
              {breadcrumbChild == null ? (
                <>
                  <span className={`${css.crumb} ${css.crumbCurrent}`}>{title}</span>
                  {subtitle != null && <span className={css.crumbSubagent}>{subtitle}</span>}
                </>
              ) : (
                <>
                  <button
                    type="button"
                    className={css.crumb}
                    onClick={breadcrumbChild.onExit}
                    title={`返回 ${title}`}
                  >
                    {title}
                  </button>
                  <span className={css.crumbSep} aria-hidden>/</span>
                  <span className={css.crumbSeg}>
                    <span className={`${css.crumb} ${css.crumbCurrent}`} title={breadcrumbChild.title}>
                      {breadcrumbChild.title}
                    </span>
                    {breadcrumbChild.badge != null && (
                      <span className={css.crumbSubagent}>{breadcrumbChild.badge}</span>
                    )}
                  </span>
                </>
              )}
            </nav>
          </div>
          {headerActions != null && <div className={css.headerActions}>{headerActions}</div>}
        </div>
        {view != null && onView != null && (
          <div className={css.tabs} role="tablist">
            <button
              type="button"
              role="tab"
              aria-selected={view === 'chat'}
              className={`${css.tab} ${view === 'chat' ? css.tabActive : ''}`}
              onClick={() => { onView('chat') }}
            >{tabLabels?.chat ?? 'Chat'}</button>
            <button
              type="button"
              role="tab"
              aria-selected={view === 'trajectory'}
              className={`${css.tab} ${view === 'trajectory' ? css.tabActive : ''}`}
              onClick={() => { onView('trajectory') }}
            >{tabLabels?.trajectory ?? 'Trajectory'}</button>
            <button
              type="button"
              role="tab"
              aria-selected={view === 'context'}
              className={`${css.tab} ${view === 'context' ? css.tabActive : ''}`}
              onClick={() => { onView('context') }}
            >{tabLabels?.context ?? 'Context'}</button>
          </div>
        )}
      </div>
      {view === 'context'
        ? <div className={css.viewArea}>{context}</div>
        : view === 'trajectory'
          ? (
            <div className={css.scrollBody}>
              <div data-slot="conversation.session" style={{ display: 'contents' }}>
                <div className={css.viewArea}>{trajectory}</div>
              </div>
              {composer != null && <div ref={composerRef} className={css.composerSeat}>{composer}</div>}
            </div>
          )
          : (
            <>
              {transcript}
              <div ref={composerRef}>{composer}</div>
            </>
          )}
    </div>
  )
}

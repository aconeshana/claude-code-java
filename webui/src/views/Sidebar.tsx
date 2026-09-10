import { useMemo } from 'react'
import { Pill, StateDot } from '@primitives'
import css from '@chat-styles/SidebarRoot.module.css'
import listCss from './Sidebar.module.css'
import { useSessions } from '../store/sessions'

/**
 * Session sidebar: the /resume project→session tree over the vendored dsh
 * sidebar shell. `active` marks the TUI's conversation; `headless_open`
 * marks a gateway headless session.
 */
export function Sidebar() {
  const projects = useSessions((state) => state.projects)
  const selectedId = useSessions((state) => state.selectedSessionId)
  const select = useSessions((state) => state.select)
  const openSession = useSessions((state) => state.openSession)
  const closeSession = useSessions((state) => state.closeSession)
  const refresh = useSessions((state) => state.refresh)
  const loading = useSessions((state) => state.loading)
  const error = useSessions((state) => state.error)

  const sorted = useMemo(
    () => [...projects].sort((a, b) => a.project_name.localeCompare(b.project_name)),
    [projects],
  )

  return (
    <div className={css.root}>
      <div className={listCss.header}>
        <span className={listCss.title}>会话</span>
        <button
          type="button"
          className={listCss.refresh}
          onClick={() => { void refresh() }}
          title="刷新会话列表"
        >
          {loading ? '…' : '刷新'}
        </button>
      </div>
      {error != null && <div className={listCss.error}>{error}</div>}
      <div className={listCss.scroll}>
        {sorted.length === 0 && (
          <div className={listCss.empty}>暂无会话。在 TUI 中开始对话后点击刷新。</div>
        )}
        {sorted.map((project) => (
          <section key={project.project_path} className={listCss.project}>
            <div className={listCss.projectName} title={project.project_path}>
              {project.project_name}
            </div>
            {project.sessions.map((session) => (
              <div key={session.id} className={listCss.sessionRow}>
                <button
                  type="button"
                  className={session.id === selectedId
                    ? `${listCss.session} ${listCss.sessionSelected}`
                    : listCss.session}
                  onClick={() => {
                    // A TUI-history session that isn't live yet needs the
                    // headless open round-trip before its snapshot/mirror
                    // frames exist; active or already-open sessions just
                    // switch straight to their snapshot.
                    void (session.active || session.headless_open
                      ? select(session.id)
                      : openSession(session.id, project.project_path))
                  }}
                >
                  <span className={listCss.sessionMeta}>
                    {session.active && <StateDot state="done" />}
                    {session.headless_open && <StateDot state="ongoing" />}
                  </span>
                  <span className={listCss.sessionLabel}>
                    {session.custom_title ?? session.summary ?? session.first_prompt
                      ?? session.id.slice(0, 8)}
                  </span>
                  <span className={listCss.sessionCount}>{session.message_count}</span>
                </button>
                {session.headless_open && !session.active && (
                  <button
                    type="button"
                    className={listCss.close}
                    title="关闭 headless 会话"
                    onClick={() => { void closeSession(session.id) }}
                  >
                    ×
                  </button>
                )}
              </div>
            ))}
          </section>
        ))}
      </div>
      <div className={listCss.footer}>
        <Pill>Claude Code</Pill>
      </div>
    </div>
  )
}

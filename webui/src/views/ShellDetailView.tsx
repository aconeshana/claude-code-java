import { useEffect, useRef } from 'react'
import { IconRefreshOutline14, IconStopFill16, StateDot, TerminalBlock, type TerminalBlockLabels } from '@primitives'
import { useShells } from '../store/shells'
import css from '@chat-styles/ChatView.module.css'

const TERMINAL_LABELS: TerminalBlockLabels = {
  signal: (signal) => `信号 ${signal}`,
  exitCode: (code) => `退出码 ${code}`,
  running: '运行中',
  failed: '已失败',
  done: '已完成',
  copy: '复制',
  copied: '已复制',
  noOutput: '（暂无输出）',
  collapseAria: '收起输出',
  collapse: '收起',
  expandAria: (hidden) => `展开其余 ${hidden} 行`,
  expand: (hidden) => `展开其余 ${hidden} 行`,
}

function statusText(status: string, exitCode?: number): string {
  switch (status) {
    case 'running': return '正在运行'
    case 'completed': return exitCode != null ? `已完成 (退出码 ${exitCode})` : '已完成'
    case 'failed': return exitCode != null ? `已失败 (退出码 ${exitCode})` : '已失败'
    case 'killed': return '已停止'
    default: return status
  }
}

/**
 * Full-column detail view for one background shell task, entered from the
 * header's `ShellCatalogMenu`. Renders the command metadata, Stop/Refresh
 * controls, and live-polled terminal output using vendored `TerminalBlock`.
 */
export function ShellDetailView({ taskId }: { taskId: string }) {
  const scrollRef = useRef<HTMLDivElement>(null)
  const detail = useShells((state) => state.details[taskId])
  const loading = useShells((state) => state.detailLoading[taskId] === true)
  const error = useShells((state) => state.detailErrors[taskId])
  const loadDetail = useShells((state) => state.loadDetail)
  const stop = useShells((state) => state.stop)

  useEffect(() => {
    void loadDetail(taskId)
  }, [taskId, loadDetail])

  const running = detail?.status === 'running'

  useEffect(() => {
    if (!running) return
    const timer = window.setInterval(() => {
      void loadDetail(taskId, true)
    }, 1_500)
    return () => { window.clearInterval(timer) }
  }, [running, taskId, loadDetail])

  useEffect(() => {
    if (!running) return
    const scroller = scrollRef.current
    if (scroller != null) scroller.scrollTop = scroller.scrollHeight
  }, [detail?.output, running])

  return (
    <div className={css.root}>
      <div ref={scrollRef} className={css.scroller}>
        <div className={css.column} style={{ gap: 12, paddingTop: 16, paddingBottom: 32 }}>
          <div style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 12,
            padding: '10px 14px',
            borderRadius: 12,
            border: '1px solid var(--dsw-alias-border-l2)',
            background: 'var(--dsw-alias-bg-secondary)',
          }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
              <StateDot state={running ? 'ongoing' : detail?.status === 'failed' ? 'error' : 'done'} />
              <span style={{ fontSize: 13, fontWeight: 500, color: 'var(--dsw-alias-label-primary)' }}>
                {detail != null ? statusText(detail.status, detail.exit_code) : loading ? '正在加载…' : '终端详情'}
              </span>
              {detail?.truncated && (
                <span style={{ fontSize: 12, color: 'var(--dsw-alias-label-tertiary)' }}>
                  （仅展示末尾 256 KB 输出）
                </span>
              )}
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, flex: 'none' }}>
              <button
                type="button"
                onClick={() => { void loadDetail(taskId) }}
                style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 4,
                  padding: '4px 10px',
                  borderRadius: 8,
                  border: '1px solid var(--dsw-alias-border-l2)',
                  background: 'transparent',
                  color: 'var(--dsw-alias-label-primary)',
                  cursor: 'pointer',
                  fontSize: 12,
                }}
              >
                <IconRefreshOutline14 />
                刷新
              </button>
              {running && (
                <button
                  type="button"
                  onClick={() => { void stop(taskId) }}
                  style={{
                    display: 'inline-flex',
                    alignItems: 'center',
                    gap: 4,
                    padding: '4px 10px',
                    borderRadius: 8,
                    border: '1px solid var(--dsw-alias-state-error, #d93025)',
                    background: 'transparent',
                    color: 'var(--dsw-alias-state-error, #d93025)',
                    cursor: 'pointer',
                    fontSize: 12,
                  }}
                >
                  <IconStopFill16 size={12} />
                  停止进程
                </button>
              )}
            </div>
          </div>

          {error != null && (
            <div role="alert" style={{ fontSize: 13, color: 'var(--dsw-alias-state-error, #d93025)' }}>
              无法加载终端输出：{error}
            </div>
          )}

          {detail != null && (
            <TerminalBlock
              command={detail.command || detail.description || detail.id}
              output={detail.output}
              exitCode={detail.exit_code}
              running={false}
              maxLines={Number.POSITIVE_INFINITY}
              labels={TERMINAL_LABELS}
            />
          )}
        </div>
      </div>
    </div>
  )
}

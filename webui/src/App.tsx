import { useCallback, useEffect, useState } from 'react'
import { submitTurn } from './api/client'
import { subscribeMirror, type MirrorConnection } from './api/events'
import { captureTokenFromUrl, currentToken, initTokenSync } from './api/token'
import type { UserContentBlock } from './api/types'
import { useApprovals } from './store/approvals'
import { useAuth } from './store/auth'
import { useConversations } from './store/conversations'
import { useSessions } from './store/sessions'
import { AppFrame } from './views/AppFrame'
import { ApprovalCard } from './views/ApprovalCard'
import { ChatView } from './views/ChatView'
import { ConversationRoot } from './views/ConversationRoot'
import { InputBar } from './views/InputBar'
import { Sidebar } from './views/Sidebar'

/**
 * App shell: sidebar + conversation column over the vendored dsh layout,
 * with the mirror stream feeding the conversation/approval stores and the
 * composer submitting through the Messages protocol face.
 */
export function App() {
  const [connected, setConnected] = useState<'connecting' | 'open' | 'error'>('connecting')

  useEffect(() => {
    captureTokenFromUrl()
    const captured = currentToken()
    if (captured != null) useAuth.getState().setToken(captured)
    initTokenSync((token) => { useAuth.getState().setToken(token) })
  }, [])

  const token = useAuth((state) => state.token)

  useEffect(() => {
    if (token == null) return
    const sessions = useSessions.getState()
    void sessions.refresh().then(() => { void sessions.selectActiveOrFirst() })

    const connection: MirrorConnection = subscribeMirror((frame) => {
      useConversations.getState().applyFrame(frame)
      useApprovals.getState().applyFrame(frame)
    }, setConnected)

    // Session switches from the TUI side surface as activated frames; keep
    // the catalog fresh so the sidebar follows along.
    const catalogTimer = window.setInterval(() => {
      void useSessions.getState().refresh()
    }, 10_000)

    return () => {
      connection.close()
      window.clearInterval(catalogTimer)
    }
  }, [token])

  const selectedId = useSessions((state) => state.selectedSessionId)
  const conversation = useConversations((state) =>
    state.conversations[selectedId ?? ''])
  const asks = useApprovals((state) => state.asks)
  const visibleAsk = asks.find((ask) => ask.session_id === selectedId) ?? asks[0]

  const onSubmit = useCallback((content: string | readonly UserContentBlock[]) => {
    void submitTurn(selectedId, content).catch((failure: unknown) => {
      console.error('turn submission failed', failure)
    })
  }, [selectedId])

  if (token == null) {
    return <MissingToken />
  }

  return (
    <AppFrame sidebar={<Sidebar />}>
      <ConversationRoot
        title={selectedId == null ? 'Claude Code' : selectedId.slice(0, 8)}
        subtitle={connected === 'open' ? '已连接' : connected === 'error' ? '重连中…' : '连接中…'}
        transcript={<ChatView conversation={conversation} />}
        composer={
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            {visibleAsk != null && (
              <ApprovalCard
                ask={visibleAsk}
                onDecision={async (ask, allowed) => {
                  const { respondPermission } = await import('./api/client')
                  await respondPermission({
                    request_id: ask.request_id,
                    session_id: ask.session_id,
                    allowed,
                  })
                }}
              />
            )}
            <InputBar
              disabled={selectedId == null}
              busy={conversation?.turnRunning === true}
              placeholder={selectedId == null ? '先在侧栏选择一个会话' : '输入消息，Enter 发送'}
              onSubmit={onSubmit}
              sessionId={selectedId}
            />
          </div>
        }
      />
    </AppFrame>
  )
}

function MissingToken() {
  return (
    <div style={{
      display: 'flex',
      flexDirection: 'column',
      alignItems: 'center',
      justifyContent: 'center',
      gap: 12,
      minHeight: '100vh',
      background: 'var(--dsw-alias-bg-base)',
      color: 'var(--dsw-alias-label-primary)',
      fontSize: 'var(--dsh-content-font-size, 14px)',
      textAlign: 'center',
      padding: 24,
    }}>
      <h1 style={{ fontSize: 20, fontWeight: 510, margin: 0 }}>Claude Code</h1>
      <p style={{ color: 'var(--dsw-alias-label-secondary)', margin: 0, lineHeight: 1.6 }}>
        缺少启动 token。<br />
        请在 TUI 中运行 <code>/web</code>，用输出的完整 URL 打开本页面。
      </p>
    </div>
  )
}

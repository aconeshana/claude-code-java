import { useCallback, useEffect, useState } from 'react'
import { cancelTurn, submitTurn } from './api/client'
import { subscribeMirror, type MirrorConnection } from './api/events'
import { captureTokenFromUrl, currentToken, initTokenSync } from './api/token'
import { ApiError, type UserContentBlock } from './api/types'
import { CONTEXT_NS, contextDicts } from './i18n/dictionaries/context'
import { useTranslate } from './i18n/useTranslate'
import { pendingAskFor, useApprovals } from './store/approvals'
import { useAuth } from './store/auth'
import { useContextTimeline, useSessionAgents } from './store/contextTimeline'
import { dropForeignSubagentFocus, useSubagentFocus } from './store/subagentFocus'
import { useConversations } from './store/conversations'
import { useConversationView } from './store/conversationView'
import { usePermissionMode } from './store/permissionMode'
import { useSessions } from './store/sessions'
import { useSidebarCollapse } from './store/sidebarCollapse'
import { useTaskBoard } from './store/taskBoard'
import { AppFrame } from './views/AppFrame'
import { ApprovalCard } from './views/ApprovalCard'
import { ChatView } from './views/ChatView'
import { ConversationRoot } from './views/ConversationRoot'
import { InputBar } from './views/InputBar'
import { QuestionCard } from './views/QuestionCard'
import { Sidebar } from './views/Sidebar'
import { TodoPanel } from './views/todo/TodoPanel'
import { ContextDashboard } from './views/context/ContextDashboard'
import { ContextModal } from './views/context/ContextModal'
import { RightPanel } from './views/RightPanel'
import { RightPanelToggle } from './views/RightPanelToggle'
import { ContextView } from './views/context/ContextView'
import { SubagentCatalogMenu } from '@vendor/ui-subagent/SubagentCatalogMenu.tsx'

/**
 * App shell: sidebar + conversation column over the vendored dsh layout,
 * with the mirror stream feeding the conversation/approval stores and the
 * composer submitting through the Messages protocol face.
 */
export function App() {
  const [connected, setConnected] = useState<'connecting' | 'open' | 'error'>('connecting')
  const sidebarCollapsed = useSidebarCollapse((state) => state.collapsed)

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
      useContextTimeline.getState().applyFrame(frame)
      useSessions.getState().applyFrame(frame)
      usePermissionMode.getState().applyFrame(frame)
      useTaskBoard.getState().applyFrame(frame)
    }, setConnected)

    // Session switches from the TUI arrive as `session.activated` frames and
    // are handled above; this only keeps counts and titles from drifting.
    const catalogTimer = window.setInterval(() => {
      void useSessions.getState().refresh()
    }, 10_000)

    return () => {
      connection.close()
      window.clearInterval(catalogTimer)
    }
  }, [token])

  const selectedId = useSessions((state) => state.selectedSessionId)

  // The to-do board's cold load: mirror frames only carry revisions the model
  // produced after this client connected, so a switch to a session whose list
  // is already standing needs its own read. The endpoint answers with the same
  // shape the frames carry, and a stale answer for a session we have left is
  // discarded by the store's own binding.
  useEffect(() => {
    void useTaskBoard.getState().load(selectedId)
  }, [selectedId])

  const conversation = useConversations((state) =>
    state.conversations[selectedId ?? ''])
  const asks = useApprovals((state) => state.asks)
  const visibleAsk = pendingAskFor(asks, selectedId)

  const onSubmit = useCallback((content: string | readonly UserContentBlock[]) => {
    void submitTurn(selectedId, content).catch((failure: unknown) => {
      console.error('turn submission failed', failure)
    })
  }, [selectedId])

  // The composer's stop control: the same user-cancel interrupt the TUI's
  // Ctrl+C drives. The 404 for an id no live session answers to is a benign
  // race (the turn ended between render and click), so it is not surfaced.
  const onStop = useCallback(() => {
    void cancelTurn({ session_id: selectedId }).catch((failure: unknown) => {
      if (failure instanceof ApiError && failure.status === 404) return
      console.error('turn cancellation failed', failure)
    })
  }, [selectedId])

  // Chat | Context tab ring (per session) and the `/context` modal: the
  // vendored dsh-context surfaces. `/context` is a client-side command, it
  // never becomes a turn.
  const view = useConversationView((state) => state.views[selectedId ?? ''] ?? 'chat')
  const setView = useConversationView((state) => state.setView)
  const [contextModalOpen, setContextModalOpen] = useState(false)
  const onClientCommand = useCallback((command: string): boolean => {
    if (command !== '/context') return false
    setContextModalOpen(true)
    return true
  }, [])
  const contextT = useTranslate(CONTEXT_NS, contextDicts)
  const board = useTaskBoard((state) => state.board)

  // The subagent catalog in the header. Its record read is the same per-session
  // detail ledger the Context tab trails, so the catalog is a viewer in its own
  // right (a session whose tab was never opened still loads its agents).
  const { agents, failed: agentsFailed, retry: retryAgents } = useSessionAgents(selectedId)
  const openSubagent = useSubagentFocus((state) => state.open)
  // The catalog's prop is "no mark" as `undefined`, its own vocabulary; the
// store's is `null`. Convert here rather than widening the vendored prop.
  const currentAgent = useSubagentFocus((state) => state.current)
  const currentAgentId = currentAgent ?? undefined
  // An address into a session the reader has left would expand an unrelated
  // row that happens to reuse the id, so a switch drops it.
  useEffect(() => { dropForeignSubagentFocus(selectedId) }, [selectedId])

  if (token == null) {
    return <MissingToken />
  }

  return (
    <AppFrame sidebar={<Sidebar />} collapsed={sidebarCollapsed} right={(owner) => <RightPanel {...owner} />}>
      <ConversationRoot
        title={selectedId == null ? 'Pokemon Code' : selectedId.slice(0, 8)}
        subtitle={connected === 'open' ? '已连接' : connected === 'error' ? '重连中…' : '连接中…'}
        view={view}
        onView={(next) => { setView(selectedId, next) }}
        tabLabels={{ chat: contextT('shell.tab.chat'), context: contextT('shell.tab.context') }}
        headerActions={(
          <>
            <RightPanelToggle />
            <SubagentCatalogMenu
              agents={agents}
              currentAgentId={currentAgentId}
              failed={agentsFailed}
              onRetry={retryAgents}
              onOpen={(agentId) => {
                if (selectedId === null) return
                // Show the transcript the row lives in before addressing it, or
                // the request lands on a hidden tab.
                setView(selectedId, 'chat')
                openSubagent(selectedId, agentId)
              }}
            />
          </>
        )}
        context={<ContextView sessionId={selectedId} />}
        transcript={
          <ChatView key={selectedId ?? 'none'} conversation={conversation} sessionId={selectedId} />
        }
        composer={
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            {visibleAsk != null && (visibleAsk.questions?.length ?? 0) > 0 && (
              <QuestionCard
                key={visibleAsk.request_id}
                ask={visibleAsk}
                onSubmit={async (ask, updatedInput) => {
                  const { respondPermission } = await import('./api/client')
                  await respondPermission({
                    request_id: ask.request_id,
                    session_id: ask.session_id,
                    allowed: true,
                    updated_input: updatedInput,
                  })
                }}
                onCancel={async (ask) => {
                  const { respondPermission } = await import('./api/client')
                  await respondPermission({
                    request_id: ask.request_id,
                    session_id: ask.session_id,
                    allowed: false,
                  })
                }}
              />
            )}
            {visibleAsk != null && (visibleAsk.questions?.length ?? 0) === 0 && (
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
            {board != null && !board.hidden && <TodoPanel tasks={board.tasks} />}
            <InputBar
              disabled={selectedId == null}
              busy={conversation?.turnRunning === true}
              placeholder={selectedId == null ? '先在侧栏选择一个会话' : '输入消息，Enter 发送'}
              onSubmit={onSubmit}
              onStop={onStop}
              sessionId={selectedId}
              onClientCommand={onClientCommand}
            />
          </div>
        }
      />
      <ContextModal
        sessionId={selectedId}
        open={contextModalOpen}
        onClose={() => { setContextModalOpen(false) }}
      />
      <ContextDashboard />
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
      <h1 style={{ fontSize: 20, fontWeight: 510, margin: 0 }}>Pokemon Code</h1>
      <p style={{ color: 'var(--dsw-alias-label-secondary)', margin: 0, lineHeight: 1.6 }}>
        缺少启动 token。<br />
        请在 TUI 中运行 <code>/web</code>，用输出的完整 URL 打开本页面。
      </p>
    </div>
  )
}

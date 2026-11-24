import { useCallback, useEffect, useState } from 'react'
import { cancelTurn, promptAgent, stopAgent, submitTurn } from './api/client'
import { subscribeMirror, type MirrorConnection } from './api/events'
import { captureTokenFromUrl, currentToken, initTokenSync } from './api/token'
import { ApiError, type UserContentBlock } from './api/types'
import { CONTEXT_NS, contextDicts } from './i18n/dictionaries/context'
import { UI_NS, uiDicts } from './i18n/dictionaries/ui'
import { useTranslate } from './i18n/useTranslate'
import { pendingAskFor, useApprovals } from './store/approvals'
import { pendingSudoFor, useSudoAsks } from './store/sudoAsks'
import { useAuth } from './store/auth'
import { useAgentTranscript } from './store/agentTranscript'
import { useContextTimeline, useSessionAgents } from './store/contextTimeline'
import { dropForeignSubagentFocus, useSubagentFocus } from './store/subagentFocus'
import { useConversations } from './store/conversations'
import { useConversationView } from './store/conversationView'
import { usePermissionMode } from './store/permissionMode'
import { isInteractive as sessionIsInteractive, useSessions } from './store/sessions'
import { useShells } from './store/shells'
import { useSidebarCollapse } from './store/sidebarCollapse'
import { useScheduleView } from './store/scheduleView'
import { useTaskBoard } from './store/taskBoard'
import { useCcConnect } from './store/ccConnect'
import { AppFrame } from './views/AppFrame'
import { ApprovalCard } from './views/ApprovalCard'
import { SudoCard } from './views/SudoCard'
import { ChatView } from './views/ChatView'
import { ConversationRoot, type BreadcrumbChild } from './views/ConversationRoot'
import { HeroHome } from './views/HeroHome'
import { InputBar } from './views/InputBar'
import { QuestionCard } from './views/QuestionCard'
import { ShellCatalogMenu } from './views/ShellCatalogMenu'
import { ShellDetailView } from './views/ShellDetailView'
import { Sidebar } from './views/Sidebar'
import { ScheduledTasksWorkspace } from './views/ScheduledTasksWorkspace'
import { SubagentChatView } from './views/SubagentChatView'
import { TodoPanel } from './views/todo/TodoPanel'
import { ContextDashboard } from './views/context/ContextDashboard'
import { ContextModal } from './views/context/ContextModal'
import { openShellInRightDock, openSubagentInRightDock } from './store/rightSidebar'
import { toast } from './store/toast'
import { parseGoalSlashInput, useGoalPlanDock } from './store/goalPlanDock'
import { GoalDockCard, PlanDockCard } from './views/GoalPlanDock'
import { RightPanel } from './views/RightPanel'
import { RightPanelToggle } from './views/RightPanelToggle'
import { ToastHost } from './views/ToastHost'
import { ContextView } from './views/context/ContextView'
import { TrajectoryPane } from './views/trajectory/TrajectoryPane'
import { useTrajectory } from './store/trajectory'
import { SubagentCatalogMenu } from '@vendor/ui-subagent/SubagentCatalogMenu.tsx'

type InspectedTarget =
  | { readonly kind: 'subagent'; readonly agentId: string }
  | { readonly kind: 'shell'; readonly taskId: string }
  | null

function plainTextOf(content: string | readonly UserContentBlock[]): string {
  if (typeof content === 'string') return content
  return content
    .filter((block): block is { readonly type: 'text'; readonly text: string } => block.type === 'text')
    .map((block) => block.text)
    .join('\n')
    .trim()
}

/**
 * App shell: sidebar + conversation column over the vendored dsh layout,
 * with the mirror stream feeding the conversation/approval stores and the
 * composer submitting through the Messages protocol face.
 */
export function App() {
  const [connected, setConnected] = useState<'connecting' | 'open' | 'error'>('connecting')
  const sidebarCollapsed = useSidebarCollapse((state) => state.collapsed)
  const scheduleViewOpen = useScheduleView((state) => state.open)

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
    void sessions.refresh()
    void useShells.getState().refresh()
    void useCcConnect.getState().refresh()

    const connection: MirrorConnection = subscribeMirror((frame) => {
      useConversations.getState().applyFrame(frame)
      useApprovals.getState().applyFrame(frame)
      useSudoAsks.getState().applyFrame(frame)
      useContextTimeline.getState().applyFrame(frame)
      useSessions.getState().applyFrame(frame)
      usePermissionMode.getState().applyFrame(frame)
      useTaskBoard.getState().applyFrame(frame)
      useCcConnect.getState().applyFrame(frame)
      useTrajectory.getState().applyFrame(frame)
      if (frame.event === 'turn.completed' || frame.event === 'tool.started' || frame.event === 'tool.completed') {
        void useShells.getState().refresh()
      }
    }, setConnected)

    // Session switches from the TUI arrive as `session.activated` frames and
    // are handled above; this only keeps counts and titles from drifting.
    const catalogTimer = window.setInterval(() => {
      void useSessions.getState().refresh()
      void useShells.getState().refresh()
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
  const sudoAsks = useSudoAsks((state) => state.asks)
  const visibleSudo = pendingSudoFor(sudoAsks, selectedId)

  const onSubmit = useCallback((
    content: string | readonly UserContentBlock[],
    options?: { readonly steer?: boolean },
  ) => {
    const run = async () => {
      if (options?.steer === true && selectedId != null) {
        await cancelTurn({ session_id: selectedId }).catch((failure: unknown) => {
          if (failure instanceof ApiError && failure.status === 404) return
          console.error('steer cancellation failed', failure)
        })
        await new Promise((resolve) => { window.setTimeout(resolve, 120) })
      }
      await submitTurn(selectedId, content)
    }
    void run().catch((failure: unknown) => {
      console.error('turn submission failed', failure)
      toast.error(failure instanceof Error ? `发送失败：${failure.message}` : '消息发送失败')
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
    if (command === '/context') {
      setContextModalOpen(true)
      return true
    }
    const goalText = parseGoalSlashInput(command)
    if (goalText !== null && selectedId != null) {
      useGoalPlanDock.getState().setGoal(selectedId, goalText)
      if (goalText !== '') toast.success(`已设定目标：${goalText}`)
      return true
    }
    if (command.trim() === '/plan' && selectedId != null) {
      void usePermissionMode.getState().select('plan').then(() => {
        toast.info('已进入规划模式')
      })
      return true
    }
    return false
  }, [selectedId])
  const contextT = useTranslate(CONTEXT_NS, contextDicts)
  const uiT = useTranslate(UI_NS, uiDicts)
  const board = useTaskBoard((state) => state.board)

  // The subagent catalog and background shell catalog in the header.
  const { agents, failed: agentsFailed, retry: retryAgents } = useSessionAgents(selectedId)
  const openSubagent = useSubagentFocus((state) => state.open)
  const clearSubagentFocus = useSubagentFocus((state) => state.clear)
  const currentAgent = useSubagentFocus((state) => state.current)
  const shells = useShells((state) => state.shells)
  const shellsFailed = useShells((state) => state.failed)
  const refreshShells = useShells((state) => state.refresh)
  const catalogProjects = useSessions((state) => state.projects)
  const interactive = sessionIsInteractive(catalogProjects, selectedId)

  const [inspected, setInspected] = useState<InspectedTarget>(null)
  const [agentPromptBusy, setAgentPromptBusy] = useState(false)
  const [agentPromptError, setAgentPromptError] = useState<string | null>(null)

  // Session switches return to the main conversation and drop foreign state.
  useEffect(() => {
    dropForeignSubagentFocus(selectedId)
    setInspected(null)
    setAgentPromptError(null)
    setAgentPromptBusy(false)
  }, [selectedId])

  const inspectedAgentId = inspected?.kind === 'subagent' ? inspected.agentId : null
  const inspectedAgentRecord = inspectedAgentId == null
    ? undefined
    : agents?.find((a) => (a.agentId ?? a.id) === inspectedAgentId)
  const inspectedAgentTranscript = useAgentTranscript((state) =>
    selectedId != null && inspectedAgentId != null
      ? state.transcripts[`${selectedId}\u0000${inspectedAgentId}`]
      : undefined)
  const inspectedAgentRunning = agentPromptBusy
    || inspectedAgentTranscript?.running === true
    || inspectedAgentRecord?.status === 'running'

  const inspectedShellId = inspected?.kind === 'shell' ? inspected.taskId : null
  const inspectedShell = inspectedShellId == null
    ? undefined
    : shells.find((s) => s.id === inspectedShellId)

  const onSubmitAgent = useCallback((content: string | readonly UserContentBlock[]) => {
    if (selectedId == null || inspectedAgentId == null) return
    const text = plainTextOf(content)
    if (text === '') return
    setAgentPromptError(null)
    setAgentPromptBusy(true)
    useAgentTranscript.getState().appendOptimisticUser(selectedId, inspectedAgentId, text)
    void promptAgent(selectedId, inspectedAgentId, text)
      .then(() => {
        void useAgentTranscript.getState().refresh(selectedId, inspectedAgentId)
      })
      .catch((failure: unknown) => {
        useAgentTranscript.getState().setRunning(selectedId, inspectedAgentId, false)
        setAgentPromptError(failure instanceof Error ? failure.message : '续聊发送失败')
      })
      .finally(() => {
        setAgentPromptBusy(false)
      })
  }, [selectedId, inspectedAgentId])

  const onStopAgent = useCallback(() => {
    if (selectedId == null || inspectedAgentId == null) return
    void stopAgent(selectedId, inspectedAgentId)
      .then(() => { void useAgentTranscript.getState().refresh(selectedId, inspectedAgentId) })
      .catch((failure: unknown) => {
        if (failure instanceof ApiError && failure.status === 404) return
        setAgentPromptError(failure instanceof Error ? failure.message : '停止子智能体失败')
      })
  }, [selectedId, inspectedAgentId])

  const currentAgentId = inspectedAgentId ?? currentAgent ?? undefined
  const trajectoryDrillStack = useTrajectory((state) =>
    selectedId != null ? (state.drillStackBySession[selectedId] ?? []) : [])
  const activeTrajectorySubagent = view === 'trajectory' && trajectoryDrillStack.length > 0
    ? trajectoryDrillStack[trajectoryDrillStack.length - 1]
    : undefined

  const breadcrumbChild: BreadcrumbChild | undefined = inspected?.kind === 'subagent'
    ? {
        title: inspectedAgentRecord?.description || inspectedAgentRecord?.type || inspected.agentId,
        badge: '子智能体',
        onExit: () => {
          setInspected(null)
          setAgentPromptError(null)
          clearSubagentFocus()
        },
      }
    : inspected?.kind === 'shell'
      ? {
          title: inspectedShell?.command || inspectedShell?.description || inspected.taskId,
          badge: '后台终端',
          onExit: () => { setInspected(null) },
        }
      : activeTrajectorySubagent !== undefined && selectedId != null
        ? {
            title: activeTrajectorySubagent.label,
            badge: '子代理轨迹',
            onExit: () => {
              useTrajectory.getState().popSubagentDrill(selectedId)
            },
          }
        : undefined

  if (token == null) {
    return <MissingToken />
  }

  return (
    <AppFrame sidebar={<Sidebar />} collapsed={sidebarCollapsed} right={(owner) => <RightPanel {...owner} />}>
      {scheduleViewOpen ? (
        <ScheduledTasksWorkspace />
      ) : selectedId == null ? (
        <HeroHome onClientCommand={onClientCommand} />
      ) : (
      <ConversationRoot
        title={selectedId == null ? 'Pokemon Code' : selectedId.slice(0, 8)}
        subtitle={connected === 'open' ? uiT('status.connected') : connected === 'error' ? uiT('status.reconnecting') : uiT('status.connecting')}
        breadcrumbChild={breadcrumbChild}
        view={inspected == null ? view : 'chat'}
        onView={(next) => {
          setInspected(null)
          if (selectedId != null && next !== 'trajectory') {
            useTrajectory.getState().clearSubagentDrill(selectedId)
          }
          setView(selectedId, next)
        }}
        tabLabels={{
          chat: contextT('shell.tab.chat'),
          trajectory: contextT('shell.tab.trajectory'),
          context: contextT('shell.tab.context'),
        }}
        headerActions={(
          <>
            <ShellCatalogMenu
              shells={shells}
              currentShellId={inspectedShellId ?? undefined}
              failed={shellsFailed}
              onRetry={() => { void refreshShells() }}
              onOpen={(taskId) => {
                if (selectedId === null) {
                  setInspected({ kind: 'shell', taskId })
                  return
                }
                const shell = shells.find((s) => s.id === taskId)
                openShellInRightDock(selectedId, taskId, shell?.command || shell?.description || taskId)
              }}
            />
            <SubagentCatalogMenu
              agents={agents}
              currentAgentId={currentAgentId}
              failed={agentsFailed}
              onRetry={retryAgents}
              onOpen={(agentId) => {
                if (selectedId === null) return
                openSubagent(selectedId, agentId)
                const rec = agents?.find((a) => (a.agentId ?? a.id) === agentId)
                openSubagentInRightDock(selectedId, agentId, rec?.description || rec?.type || agentId)
              }}
            />
            <RightPanelToggle />
          </>
        )}
        trajectory={<TrajectoryPane sessionId={selectedId} />}
        context={<ContextView sessionId={selectedId} />}
        transcript={
          inspected?.kind === 'subagent' && selectedId != null ? (
            <SubagentChatView
              key={`${selectedId}:${inspected.agentId}`}
              sessionId={selectedId}
              agentId={inspected.agentId}
              agent={inspectedAgentRecord}
              promptError={agentPromptError}
              promptBusy={agentPromptBusy}
            />
          ) : inspected?.kind === 'shell' ? (
            <ShellDetailView key={inspected.taskId} taskId={inspected.taskId} />
          ) : (
            <ChatView key={selectedId ?? 'none'} conversation={conversation} sessionId={selectedId} />
          )
        }
        composer={
          inspected?.kind === 'shell' || activeTrajectorySubagent !== undefined ? null : inspected?.kind === 'subagent' ? (
            <InputBar
              disabled={selectedId == null || !interactive}
              busy={inspectedAgentRunning}
              placeholder={!interactive
                ? '此会话没有正在运行的主机，仅供查看'
                : '向子智能体发送续聊消息，Enter 发送'}
              onSubmit={onSubmitAgent}
              onStop={onStopAgent}
              sessionId={selectedId}
            />
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            {visibleSudo != null && (
              <SudoCard
                key={visibleSudo.request_id}
                ask={visibleSudo}
                onRespond={async (ask, password) => {
                  const { respondSudo } = await import('./api/client')
                  await respondSudo(password == null
                    ? { request_id: ask.request_id, session_id: ask.session_id, behavior: 'cancel' }
                    : {
                        request_id: ask.request_id,
                        session_id: ask.session_id,
                        behavior: 'submit',
                        password,
                      })
                }}
              />
            )}
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
            <GoalDockCard sessionId={selectedId} />
            <PlanDockCard
              sessionId={selectedId}
              messages={conversation?.messages ?? []}
              onApprovePlan={(prompt) => { onSubmit(prompt) }}
            />
            {board != null && !board.hidden && <TodoPanel tasks={board.tasks} />}
            <InputBar
              disabled={selectedId == null}
              busy={conversation?.turnRunning === true}
              placeholder={selectedId == null ? uiT('composer.placeholderNone') : uiT('composer.placeholder')}
              onSubmit={onSubmit}
              onStop={onStop}
              sessionId={selectedId}
              onClientCommand={onClientCommand}
            />
          </div>
          )
        }
      />
      )}
      <ContextModal
        sessionId={selectedId}
        open={contextModalOpen}
        onClose={() => { setContextModalOpen(false) }}
      />
      <ContextDashboard />
      <ToastHost />
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

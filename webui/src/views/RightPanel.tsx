import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { ReviewTab, resolveIndex } from '@vendor/ui-deliverables/ReviewTab'
import {
  DockSurface,
  FloatLayer,
  dockPaneCount,
  findTabPane,
  type DockLabels,
  type TabRecord,
} from '@vendor/ui-dockkit'
import { layoutActions } from '@vendor/ui-layout'
import {
  IconFullscreenOutline16,
  IconPanelLeftOutline16,
  Tooltip,
} from '@primitives'
import { cancelTurn, promptAgent, stopAgent } from '../api/client'
import { ApiError, type UserContentBlock } from '../api/types'
import { DELIVERABLES_NS, deliverablesDicts } from '../i18n/dictionaries/deliverables'
import { useTranslate } from '../i18n/useTranslate'
import { useAgentTranscript } from '../store/agentTranscript'
import { changesDiffStore, changesSummaryStore, useChangesDiff, useChangesSummary } from '../store/changes'
import { useSessionAgents } from '../store/contextTimeline'
import { useConversations } from '../store/conversations'
import { useFilePreviewSeat } from '../store/filePreview'
import {
  RIGHT_DOCK_MAX_PANES,
  RIGHT_DOCK_MIN_FRACTION,
  canAddTabInPane,
  canCloseTabInSurface,
  createDockIntents,
  openFileInRightDock,
  openFilesTreeInRightDock,
  openReviewInRightDock,
  openShellInRightDock,
  openSubagentInRightDock,
  useRightSidebar,
} from '../store/rightSidebar'
import { reviewStore, reviewTabId, useOpenReview, useReviewSeat, useReviewTabState } from '../store/review'
import { isInteractive as sessionIsInteractive, useSessions } from '../store/sessions'
import { useShells } from '../store/shells'
import type { RightbarOwnerProps } from './AppFrame'
import { FilePreview } from './FilePreview'
import { InputBar } from './InputBar'
import { ShellDetailView } from './ShellDetailView'
import { SubagentChatView } from './SubagentChatView'
import { CompassGlyph, GUIDE_ICONS, GuideTab, type GuideQuickItem } from './rightbar/GuideTab'
import { FileTypeIcon } from './FileTypeIcon'
import { WorkspaceFilesTab } from './rightbar/WorkspaceFilesTab'
import css from './RightPanel.module.css'

const DOCK_LABELS: DockLabels = {
  emptyPane: '暂无打开的标签页',
  splitPane: '向右分屏',
  splitPaneDisabled: '最多支持左右两栏分屏',
  splitPaneNarrow: '面板宽度不足以分屏',
  closeTab: '关闭标签',
  addTab: '新建标签页',
  dockFloat: '停靠回侧栏',
  closeFloat: '关闭悬浮窗',
  dropZone: {
    center: '移入当前分栏',
    left: '在左侧分屏',
    right: '在右侧分屏',
    top: '在上方分屏',
    bottom: '在下方分屏',
  },
}

function plainTextOf(content: string | readonly UserContentBlock[]): string {
  if (typeof content === 'string') return content
  return content
    .filter((block): block is { readonly type: 'text'; readonly text: string } => block.type === 'text')
    .map((block) => block.text)
    .join('\n')
    .trim()
}

/**
 * Right third-column multi-tab docking workspace powered by vendored
 * `ui-dockkit`. Hosts Guide, Workspace Files, Review Diff, File Preview,
 * Subagent Chat, and Background Shell tabs with split panes, drag-and-drop,
 * floating panels, and push/fullscreen presentations.
 */
export function RightPanel({ canShow }: RightbarOwnerProps): ReactNode {
  const selectedSessionId = useSessions((state) => state.selectedSessionId)
  const openReview = useOpenReview()
  const closeReview = useReviewSeat((state) => state.closeReview)
  const previewSeat = useFilePreviewSeat((state) => state.preview)
  const openPreview = useFilePreviewSeat((state) => state.openPreview)
  const closePreview = useFilePreviewSeat((state) => state.closePreview)
  const sessionId = selectedSessionId
  const ensure = useRightSidebar((state) => state.ensure)
  const surface = useRightSidebar((state) => (sessionId != null ? state.surfaces[sessionId] : undefined))
  const collapse = useRightSidebar((state) => state.collapse)
  const togglePresentation = useRightSidebar((state) => state.togglePresentation)
  const closeOtherTabs = useRightSidebar((state) => state.closeOtherTabs)
  const closeTabsToRight = useRightSidebar((state) => state.closeTabsToRight)
  const floatTab = useRightSidebar((state) => state.floatTab)

  const t = useTranslate(DELIVERABLES_NS, deliverablesDicts)
  const { agents } = useSessionAgents(sessionId)
  const shells = useShells((state) => state.shells)
  const conversation = useConversations((state) => (sessionId != null ? state.conversations[sessionId] : undefined))

  useEffect(() => {
    if (sessionId != null) ensure(sessionId)
    return () => {
      if (selectedSessionId == null) {
        useReviewSeat.setState({ open: null })
      }
    }
  }, [selectedSessionId, sessionId, ensure])

  const standaloneOccupied = selectedSessionId == null && (openReview !== null || previewSeat !== null)
  const expanded = surface?.state.expanded === true
  const hasFloats = (surface?.state.floats.length ?? 0) > 0
  const fullscreen = expanded && (surface?.presentation === 'fullscreen' || !canShow)

  useEffect(() => {
    if (standaloneOccupied) {
      layoutActions.openRightbar(true, false)
      return () => { layoutActions.closeRightbar() }
    }
    if (!expanded || sessionId == null) {
      layoutActions.closeRightbar()
      return
    }
    // Wide-screen push reserves a track; narrow auto-fullscreen takes no track.
    layoutActions.openRightbar(canShow, fullscreen)
    return () => { layoutActions.closeRightbar() }
  }, [standaloneOccupied, expanded, canShow, fullscreen, sessionId])

  const intents = useMemo(
    () => (sessionId != null ? createDockIntents(sessionId) : null),
    [sessionId],
  )

  const latestTurn = useMemo(() => {
    if (openReview != null && openReview.sessionId === sessionId) return openReview.turn
    const msgCount = conversation?.messages.filter((m) => m.kind === 'assistant').length ?? 0
    return msgCount > 0 ? msgCount : 1
  }, [openReview, sessionId, conversation])

  const renderTab = useCallback((tab: TabRecord): ReactNode => {
    if (sessionId == null || intents == null) return null

    if (tab.kind === 'guide') {
      const pane = findTabPane(surface!.state, tab.id)
      const firstAgent = agents?.[0]
      const firstShell = shells[0]
      const items: GuideQuickItem[] = [
        {
          id: 'files',
          title: '工作区文件',
          description: '浏览当前项目目录树并点击预览任意源码文件',
          icon: GUIDE_ICONS.files,
          onOpen: () => { openFilesTreeInRightDock(sessionId, pane.id, tab.id) },
        },
        {
          id: 'review',
          title: '改动评审',
          description: '查看本会话文件变更摘要与逐行 Diff',
          icon: GUIDE_ICONS.review,
          onOpen: () => {
            openReviewInRightDock(sessionId, latestTurn)
            intents.closeTab(tab.id)
          },
        },
      ]
      if (firstAgent != null) {
        const aid = firstAgent.agentId ?? firstAgent.id
        const label = firstAgent.description || firstAgent.type || aid
        items.push({
          id: 'subagent',
          title: `子智能体 · ${label}`,
          description: '在右侧栏并排查看子智能体进度并发送续聊',
          icon: GUIDE_ICONS.subagent,
          onOpen: () => { openSubagentInRightDock(sessionId, aid, label, pane.id, tab.id) },
        })
      }
      if (firstShell != null) {
        items.push({
          id: 'shell',
          title: `后台 Shell · ${firstShell.command || firstShell.id}`,
          description: '实时查看后台命令输出日志与运行状态',
          icon: GUIDE_ICONS.shell,
          onOpen: () => {
            openShellInRightDock(sessionId, firstShell.id, firstShell.command || firstShell.id, pane.id, tab.id)
          },
        })
      }
      return <GuideTab paneId={pane.id} tabId={tab.id} items={items} />
    }

    if (tab.kind === 'files') {
      return (
        <WorkspaceFilesTab
          sessionId={sessionId}
          onOpenFile={(filePath) => { openFileInRightDock(sessionId, filePath) }}
        />
      )
    }

    if (tab.kind === 'review') {
      const parts = tab.contentId.split(':')
      const revSession = parts[1] || sessionId
      const turnNum = Number(parts[2]) || latestTurn
      const rTabId = reviewTabId(revSession, turnNum)
      const isMatchingOpen = openReview != null && openReview.sessionId === revSession && openReview.turn === turnNum
      return (
        <div className={css.tabPaneBody}>
          <ReviewLoader
            key={rTabId}
            tabId={rTabId}
            sessionId={revSession}
            turn={turnNum}
            fileIndex={isMatchingOpen ? openReview.index : undefined}
            navigationRevision={isMatchingOpen ? openReview.revision : 0}
            onClose={() => { intents.closeTab(tab.id) }}
            openFile={(sid, path) => { openFileInRightDock(sid, path) }}
            t={t}
          />
        </div>
      )
    }

    if (tab.kind === 'file') {
      // contentId format: file:<sessionId>:<path>
      const rest = tab.contentId.startsWith('file:') ? tab.contentId.slice('file:'.length) : tab.contentId
      const sep = rest.indexOf(':')
      const fileSession = sep > 0 ? rest.slice(0, sep) : sessionId
      const filePath = sep > 0 ? rest.slice(sep + 1) : rest
      return (
        <div className={css.tabPaneBody}>
          <FilePreview
            key={`${fileSession}\u0000${filePath}`}
            sessionId={fileSession}
            path={filePath}
            onClose={() => { intents.closeTab(tab.id) }}
          />
        </div>
      )
    }

    if (tab.kind === 'subagent') {
      const rest = tab.contentId.startsWith('subagent:') ? tab.contentId.slice('subagent:'.length) : tab.contentId
      const sep = rest.indexOf(':')
      const subSession = sep > 0 ? rest.slice(0, sep) : sessionId
      const agentId = sep > 0 ? rest.slice(sep + 1) : rest
      return <SidebarSubagentTab key={`${subSession}:${agentId}`} sessionId={subSession} agentId={agentId} />
    }

    if (tab.kind === 'shell') {
      const taskId = tab.contentId.startsWith('shell:') ? tab.contentId.slice('shell:'.length) : tab.contentId
      return (
        <div className={css.tabPaneBody}>
          <div className={css.tabScrollBody}>
            <ShellDetailView key={taskId} taskId={taskId} />
          </div>
        </div>
      )
    }

    return <div className={css.emptyNotice}>未知标签类型：{tab.kind}</div>
  }, [sessionId, intents, surface, agents, shells, latestTurn, openReview, t])

  const renderTabMenuItems = useCallback((tab: TabRecord, dismiss: () => void): ReactNode => {
    if (sessionId == null || surface == null) return null
    const pane = findTabPane(surface.state, tab.id)
    const idx = pane.tabs.indexOf(tab.id)
    const hasOthers = pane.tabs.length > 1
    const hasRight = idx >= 0 && idx < pane.tabs.length - 1
    const canFloat = pane.host === 'dock' && !(pane.tabs.length === 1 && tab.kind === 'guide')
    return (
      <>
        {hasOthers && (
          <button
            type="button"
            role="menuitem"
            className={css.menuItem}
            onClick={() => { closeOtherTabs(sessionId, tab.id); dismiss() }}
          >
            关闭其他标签
          </button>
        )}
        {hasRight && (
          <button
            type="button"
            role="menuitem"
            className={css.menuItem}
            onClick={() => { closeTabsToRight(sessionId, tab.id); dismiss() }}
          >
            关闭右侧标签
          </button>
        )}
        {canFloat && (
          <button
            type="button"
            role="menuitem"
            className={css.menuItem}
            onClick={() => { floatTab(sessionId, tab.id); dismiss() }}
          >
            拖出为悬浮窗
          </button>
        )}
      </>
    )
  }, [sessionId, surface, closeOtherTabs, closeTabsToRight, floatTab])

  if (selectedSessionId == null) {
    if (!standaloneOccupied || !canShow) return null
    const standaloneTabId = openReview === null ? null : reviewTabId(openReview.sessionId, openReview.turn)
    return (
      <div className={css.panel} data-right-panel>
        {openReview !== null && standaloneTabId !== null && (
          <div hidden={previewSeat !== null}>
            <ReviewLoader
              key={standaloneTabId}
              tabId={standaloneTabId}
              sessionId={openReview.sessionId}
              turn={openReview.turn}
              fileIndex={openReview.index}
              navigationRevision={openReview.revision}
              onClose={closeReview}
              openFile={openPreview}
              t={t}
            />
          </div>
        )}
        {previewSeat !== null && (
          <FilePreview
            key={`${previewSeat.sessionId}\u0000${previewSeat.path}`}
            sessionId={previewSeat.sessionId}
            path={previewSeat.path}
            onClose={closePreview}
          />
        )}
      </div>
    )
  }

  if (sessionId == null || surface == null || intents == null) return null
  if (!expanded && !hasFloats) return null

  const canSplitMore = dockPaneCount(surface.state) < RIGHT_DOCK_MAX_PANES

  const chrome = (
    <div className={css.chromeGroup}>
      {canShow && (
        <Tooltip label={fullscreen ? '退出全屏' : '全屏展开'} side="bottom" delayMs={400}>
          <button
            type="button"
            className={css.chromeBtn}
            aria-label={fullscreen ? '退出全屏' : '全屏展开'}
            data-right-panel-fullscreen-toggle
            onClick={() => { togglePresentation(sessionId) }}
          >
            <IconFullscreenOutline16 size={15} />
          </button>
        </Tooltip>
      )}
      <Tooltip label="收起右侧栏" side="bottom" delayMs={400}>
        <button
          type="button"
          className={css.chromeBtn}
          aria-label="收起右侧栏"
          data-right-panel-collapse
          onClick={() => { collapse(sessionId) }}
        >
          <IconPanelLeftOutline16 size={15} className={css.collapseIcon} />
        </button>
      </Tooltip>
    </div>
  )

  return (
    <>
      {expanded && (
        <div
          className={css.panel}
          data-right-panel
          data-fullscreen={fullscreen || undefined}
        >
          <div className={css.surfaceWrap}>
            <DockSurface
              state={surface.state}
              canSplit={canSplitMore}
              dropZones="horizontal"
              minPaneFraction={RIGHT_DOCK_MIN_FRACTION}
              canAddTab={(paneId) => canAddTabInPane(surface.state, paneId)}
              canCloseTab={(tabId) => canCloseTabInSurface(surface.state, tabId)}
              intents={intents}
              labels={DOCK_LABELS}
              renderTab={renderTab}
              renderTabTitle={(tab) => {
                if (tab.kind === 'guide') {
                  return (
                    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5, minWidth: 0 }}>
                      <CompassGlyph size={14} />
                      <span style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>{tab.title}</span>
                    </span>
                  )
                }
                if (tab.kind === 'file') {
                  return (
                    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5, minWidth: 0 }}>
                      <FileTypeIcon path={tab.title} size={14} />
                      <span style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>{tab.title}</span>
                    </span>
                  )
                }
                return tab.title
              }}
              renderTabMenuItems={renderTabMenuItems}
              chrome={chrome}
            />
          </div>
        </div>
      )}
      {hasFloats && (
        <FloatLayer
          state={surface.state}
          intents={intents}
          labels={DOCK_LABELS}
          renderTab={renderTab}
        />
      )}
    </>
  )
}

function SidebarSubagentTab({
  sessionId,
  agentId,
}: {
  readonly sessionId: string
  readonly agentId: string
}): ReactNode {
  const { agents } = useSessionAgents(sessionId)
  const agent = agents?.find((a) => (a.agentId ?? a.id) === agentId)
  const transcript = useAgentTranscript((s) => s.transcripts[`${sessionId}\u0000${agentId}`])
  const projects = useSessions((s) => s.projects)
  const interactive = sessionIsInteractive(projects, sessionId)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const running = busy || transcript?.running === true || agent?.status === 'running'

  const onSubmit = useCallback((content: string | readonly UserContentBlock[]) => {
    const text = plainTextOf(content)
    if (text === '') return
    setError(null)
    setBusy(true)
    useAgentTranscript.getState().appendOptimisticUser(sessionId, agentId, text)
    void promptAgent(sessionId, agentId, text)
      .then(() => { void useAgentTranscript.getState().refresh(sessionId, agentId) })
      .catch((failure: unknown) => {
        useAgentTranscript.getState().setRunning(sessionId, agentId, false)
        setError(failure instanceof Error ? failure.message : '续聊发送失败')
      })
      .finally(() => { setBusy(false) })
  }, [sessionId, agentId])

  const onStop = useCallback(() => {
    void stopAgent(sessionId, agentId)
      .then(() => { void useAgentTranscript.getState().refresh(sessionId, agentId) })
      .catch((failure: unknown) => {
        if (failure instanceof ApiError && failure.status === 404) return
        setError(failure instanceof Error ? failure.message : '停止子智能体失败')
      })
  }, [sessionId, agentId])

  return (
    <div className={css.tabPaneBody}>
      <div className={css.tabScrollBody}>
        <SubagentChatView
          sessionId={sessionId}
          agentId={agentId}
          agent={agent}
          promptError={error}
          promptBusy={busy}
        />
      </div>
      <div className={css.tabComposerWrap}>
        <InputBar
          disabled={!interactive}
          busy={running}
          placeholder={!interactive ? '此会话无活动主机，仅供查看' : '向子智能体发送续聊消息，Enter 发送'}
          onSubmit={onSubmit}
          onStop={onStop}
          sessionId={sessionId}
        />
      </div>
    </div>
  )
}

function ReviewLoader({ tabId, sessionId, turn, fileIndex, navigationRevision, onClose, openFile, t }: {
  tabId: string
  sessionId: string
  turn: number
  fileIndex: number | undefined
  navigationRevision: number
  onClose: () => void
  openFile: (sessionId: string, path: string) => void
  t: (key: string, params?: Readonly<Record<string, string | number>>) => string
}) {
  const state = useReviewTabState(tabId)
  const summary = useChangesSummary(sessionId, turn)
  const index = resolveIndex(state, summary)
  const path = typeof summary === 'object' ? summary.files[index]?.path : undefined
  const diff = useChangesDiff(sessionId, path ?? '')
  const loadSummary = useCallback(
    (id: string, at: number) => changesSummaryStore.load(id, at), [])
  const loadDiff = useCallback(
    (id: string, file: string) => changesDiffStore.load(id, file), [])
  // Suppress unused import lint if cancelTurn is kept for future parity
  void cancelTurn
  return (
    <ReviewTab
      store={reviewStore}
      state={state}
      tabId={tabId}
      sessionId={sessionId}
      turn={turn}
      fileIndex={fileIndex}
      navigationRevision={navigationRevision}
      summary={summary}
      diff={path === undefined ? undefined : diff}
      index={index}
      loadSummary={loadSummary}
      loadDiff={loadDiff}
      openFile={openFile}
      onClose={onClose}
      t={t}
    />
  )
}

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { TRAJECTORY_NS, trajectoryDicts } from '../../i18n/dictionaries/trajectory'
import { useTranslate } from '../../i18n/useTranslate'
import { useTrajectory } from '../../store/trajectory'
import type { RawTrajectoryRequestNumber, RawTrajectoryTurn } from '../../api/trajectory'
import { TrajectoryToolbar } from '@vendor/ui-trajectory/TrajectoryToolbar.tsx'
import { TrajectoryTimeline } from '@vendor/ui-trajectory/TrajectoryTimeline.tsx'
import {
  TrajectoryTable,
  type TrajectoryRequestNumber,
} from '@vendor/ui-trajectory/TrajectoryTable.tsx'
import {
  trajectoryTimelineFocusIndexes,
  type TrajectoryTimelineMode,
  type TrajectoryTimeRange,
} from '@vendor/ui-trajectory/timeline.ts'
import { TrajectorySearchIndex } from '@vendor/ui-trajectory/trajectory-search-index.ts'
import { formatElapsedSeconds, trajectoryRecordId } from '@vendor/ui-trajectory/trajectory-record.ts'
import type {
  ImageAttachmentRef,
  TrajectoryGroupModel,
  TrajectoryImageSlotProps,
  TrajectoryTurnModel,
} from '@vendor/ui-trajectory/shim-types.ts'
import type { TrajectoryTranslate } from '@vendor/ui-trajectory/locales.ts'
import viewsCss from '@vendor/ui-trajectory/views.module.css'

function mapTurns(
  rawTurns: readonly RawTrajectoryTurn[],
  t: TrajectoryTranslate,
): readonly TrajectoryTurnModel[] {
  return rawTurns.map((turn) => ({
    turn: turn.turn,
    groups: turn.groups.map((grp): TrajectoryGroupModel => {
      const title = grp.kind === 'step'
        ? t('group.step', { step: grp.step ?? 1 })
        : grp.kind === 'compaction'
          ? t('group.compaction', { seq: grp.seq ?? 1 })
          : t('group.message')
      const description = grp.durationMs !== undefined && grp.durationMs >= 0
        ? formatElapsedSeconds(grp.durationMs / 1000, t)
        : undefined
      return {
        title,
        ...(description === undefined ? {} : { description }),
        cells: grp.cells.map((cell) => {
          if (
            cell.kind === 'message'
            && cell.text === ''
            && !cell.outputDetail
            && !cell.thinkingDetail
          ) {
            return { ...cell, text: t('layout.toolCallOnly') }
          }
          return cell
        }),
      }
    }),
  }))
}

function mapRequestNumbers(
  rawReqs: readonly RawTrajectoryRequestNumber[],
  t: TrajectoryTranslate,
): readonly TrajectoryRequestNumber[] {
  return rawReqs.flatMap((req): TrajectoryRequestNumber[] => {
    const base = {
      number: req.number,
      ...(req.status === undefined ? {} : { status: req.status }),
      ...(req.startedAt === undefined ? {} : { startedAt: req.startedAt }),
      ...(req.completedAt === undefined ? {} : { completedAt: req.completedAt }),
      ...(req.error === undefined ? {} : { error: req.error }),
      ...(req.errorCode === undefined ? {} : { errorCode: req.errorCode }),
      ...(req.retry === undefined ? {} : { retry: req.retry }),
      ...(req.maxRetries === undefined ? {} : { maxRetries: req.maxRetries }),
      ...(req.retryDelayMs === undefined ? {} : { retryDelayMs: req.retryDelayMs }),
      ...(req.provider === undefined ? {} : { provider: req.provider }),
      ...(req.model === undefined ? {} : { model: req.model }),
      ...(req.requestConfig === undefined ? {} : { requestConfig: req.requestConfig }),
      ...(req.usage === undefined ? {} : { usage: req.usage }),
      ...(req.cumulativeUsage === undefined ? {} : { cumulativeUsage: req.cumulativeUsage }),
    }
    if (req.purpose === 'compaction') {
      const seq = req.seq ?? 1
      return [{
        ...base,
        purpose: 'compaction',
        seq,
        turn: req.turn,
        step: 0,
        group: t('group.compaction', { seq }),
      }]
    }
    if (req.turn === null) return []
    return [{
      ...base,
      purpose: 'assistant',
      ...(req.seq === undefined ? {} : { seq: req.seq }),
      turn: req.turn,
      step: req.step,
      group: t('group.step', { step: req.step }),
    }]
  })
}

function collapsibleIds(turns: readonly TrajectoryTurnModel[]) {
  const turnIds: number[] = []
  const assistantIds: string[] = []
  const toolIds: string[] = []
  for (const turn of turns) {
    const visibleCells = turn.groups.flatMap(g => g.cells.filter(c => c.requestOnly !== true))
    if (turn.turn !== null && visibleCells.length > 1) {
      turnIds.push(turn.turn)
    }
    for (const group of turn.groups) {
      let lastAssistantId: string | null = null
      let lastToolId: string | null = null
      let sawToolUnderAssistant = false
      let sawSubtoolUnderTool = false
      for (const cell of group.cells) {
        if (cell.kind === 'message') {
          if (lastAssistantId !== null && sawToolUnderAssistant) {
            assistantIds.push(lastAssistantId)
          }
          lastAssistantId = trajectoryRecordId(cell)
          sawToolUnderAssistant = false
        } else if (cell.kind === 'tool') {
          if (lastToolId !== null && sawSubtoolUnderTool) {
            toolIds.push(lastToolId)
          }
          lastToolId = trajectoryRecordId(cell)
          sawSubtoolUnderTool = false
          sawToolUnderAssistant = true
        } else if (cell.kind === 'subtool') {
          sawSubtoolUnderTool = true
        }
      }
      if (lastAssistantId !== null && sawToolUnderAssistant) {
        assistantIds.push(lastAssistantId)
      }
      if (lastToolId !== null && sawSubtoolUnderTool) {
        toolIds.push(lastToolId)
      }
    }
  }
  return { turnIds, assistantIds, toolIds }
}

const loadImage = async (attachment: ImageAttachmentRef): Promise<string> =>
  attachment.dataUrl ?? ''

function renderImageSlot(
  _slot: 'conversation.trajectory.images',
  props: TrajectoryImageSlotProps,
) {
  return (
    <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
      {props.images.map(({ attachment, label }, idx) => (
        attachment.dataUrl != null ? (
          <img
            key={attachment.id ?? idx}
            src={attachment.dataUrl}
            alt={label ?? attachment.name ?? 'image'}
            style={{ maxWidth: 96, maxHeight: 72, borderRadius: 6, objectFit: 'cover' }}
          />
        ) : null
      ))}
    </div>
  )
}

export function TrajectoryPane({ sessionId }: { sessionId: string | null }) {
  const rawT = useTranslate(TRAJECTORY_NS, trajectoryDicts)
  const t: TrajectoryTranslate = useCallback(
    (key, params) => rawT(key, params),
    [rawT],
  )

  const drillStack = useTrajectory((state) =>
    sessionId != null ? (state.drillStackBySession[sessionId] ?? []) : [])
  const activeSubagent = drillStack.length > 0 ? drillStack[drillStack.length - 1] : undefined
  const activeAgentId = activeSubagent?.agentId ?? null

  const entryKey = sessionId != null
    ? (activeAgentId != null ? `${sessionId}\u0000${activeAgentId}` : sessionId)
    : ''
  const entry = useTrajectory((state) => (entryKey !== '' ? state.entries[entryKey] : undefined))
  const inspectCallId = useTrajectory((state) =>
    sessionId != null && activeAgentId == null
      ? (state.inspectCallBySession[sessionId] ?? null)
      : null)

  useEffect(() => {
    if (sessionId == null) return
    void useTrajectory.getState().load(sessionId, activeAgentId, inspectCallId)
  }, [sessionId, activeAgentId, inspectCallId])

  const [actualDuration, setActualDuration] = useState(false)
  const [actualTime, setActualTime] = useState(false)
  const [searchQuery, setSearchQuery] = useState('')
  const [timelineRange, setTimelineRange] = useState<TrajectoryTimeRange | null>(null)
  const [selectedIndex, setSelectedIndex] = useState<number | null>(null)
  const [recordSelection, setRecordSelection] = useState<{ readonly index: number } | null>(null)
  const [recordFocus, setRecordFocus] = useState<{ readonly index: number } | null>(null)
  const [collapsedTurns, setCollapsedTurns] = useState<ReadonlySet<number>>(() => new Set())
  const [collapsedAssistants, setCollapsedAssistants] = useState<ReadonlySet<string>>(() => new Set())
  const [collapsedTools, setCollapsedTools] = useState<ReadonlySet<string>>(() => new Set())
  const [subtoolsInitializedForKey, setSubtoolsInitializedForKey] = useState<string>('')
  const [wrapJsonLines, setWrapJsonLines] = useState(false)

  // Reset local range/selection when switching session or drilling into/out of a subagent.
  useEffect(() => {
    setTimelineRange(null)
    setSelectedIndex(null)
    setRecordSelection(null)
    setRecordFocus(null)
    setCollapsedTurns(new Set())
    setCollapsedAssistants(new Set())
  }, [entryKey])

  const timelineMode: TrajectoryTimelineMode = actualDuration
    ? (actualTime ? 'actual' : 'duration')
    : (actualTime ? 'time' : 'sequence')

  const turns = useMemo(
    () => mapTurns(entry?.turns ?? [], t),
    [entry?.turns, t],
  )
  const requestNumbers = useMemo(
    () => mapRequestNumbers(entry?.requestNumbers ?? [], t),
    [entry?.requestNumbers, t],
  )

  const { turnIds, assistantIds, toolIds } = useMemo(() => collapsibleIds(turns), [turns])

  // Upstream folds subtools by default on initial load so Agent rows stay compact until expanded.
  useEffect(() => {
    if (subtoolsInitializedForKey === entryKey || toolIds.length === 0) return
    setSubtoolsInitializedForKey(entryKey)
    setCollapsedTools(new Set(toolIds))
  }, [entryKey, subtoolsInitializedForKey, toolIds])

  const searchIndexRef = useRef<TrajectorySearchIndex | null>(null)
  if (searchIndexRef.current === null) {
    searchIndexRef.current = new TrajectorySearchIndex()
  }
  const searchMatchIndexes = useMemo<ReadonlySet<number> | null>(() => {
    const idx = searchIndexRef.current!
    idx.update([turns])
    const matchedIds = idx.search(searchQuery)
    if (matchedIds === null) return null
    const matched = new Set<number>()
    for (const turn of turns) {
      for (const grp of turn.groups) {
        for (const cell of grp.cells) {
          if (matchedIds.has(trajectoryRecordId(cell))) {
            matched.add(cell.index)
          }
        }
      }
    }
    return matched
  }, [turns, searchQuery])

  const timelineFocusIndexes = useMemo(
    () => (timelineRange === null
      ? null
      : trajectoryTimelineFocusIndexes(turns, timelineRange, timelineMode)),
    [turns, timelineRange, timelineMode],
  )

  const allTurnsCollapsed = turnIds.length > 0 && turnIds.every(id => collapsedTurns.has(id))
  const allAssistantsCollapsed = assistantIds.length > 0
    && assistantIds.every(id => collapsedAssistants.has(id))
  const allSubCallsCollapsed = toolIds.length === 0
    || toolIds.every(id => collapsedTools.has(id))

  const handleLoadOlder = useCallback(async () => {
    if (sessionId == null) return false
    return useTrajectory.getState().loadOlder(sessionId, activeAgentId)
  }, [sessionId, activeAgentId])

  const handleInspectSubagent = useCallback((subagentId: string, label?: string) => {
    if (sessionId == null) return
    useTrajectory.getState().drillIntoSubagent(sessionId, subagentId, label)
  }, [sessionId])

  const handleInspectApplied = useCallback(() => {
    if (sessionId == null) return
    useTrajectory.getState().acknowledgeInspectCall(sessionId)
  }, [sessionId])

  const stringWrapping = useMemo(() => ({
    label: t('record.wrapLines'),
    getDefault: () => wrapJsonLines,
    setDefault: (wrapped: boolean) => { setWrapJsonLines(wrapped) },
  }), [t, wrapJsonLines])

  return (
    <div className={viewsCss.root} data-conversation-composer-overlay="">
      <TrajectoryToolbar
        t={t}
        actualDuration={actualDuration}
        onActualDurationChange={(next) => {
          setTimelineRange(null)
          setActualDuration(next)
        }}
        actualTime={actualTime}
        onActualTimeChange={(next) => {
          setTimelineRange(null)
          setActualTime(next)
        }}
        allTurnsCollapsed={allTurnsCollapsed}
        onToggleAllTurns={() => {
          setCollapsedTurns(allTurnsCollapsed ? new Set() : new Set(turnIds))
        }}
        allAssistantsCollapsed={allAssistantsCollapsed}
        onToggleAllAssistants={() => {
          setCollapsedAssistants(allAssistantsCollapsed ? new Set() : new Set(assistantIds))
        }}
        allSubCallsCollapsed={allSubCallsCollapsed}
        onToggleAllSubCalls={() => {
          setCollapsedTools(allSubCallsCollapsed ? new Set() : new Set(toolIds))
        }}
        searchQuery={searchQuery}
        onSearchQueryChange={setSearchQuery}
      />
      <TrajectoryTimeline
        t={t}
        turns={turns}
        mode={timelineMode}
        range={timelineRange}
        hasEarlierRecords={entry?.hasOlderTurns === true}
        onLoadEarlier={handleLoadOlder}
        selectedIndex={selectedIndex}
        searchMatchIndexes={searchMatchIndexes}
        onRangeChange={setTimelineRange}
        onRecordSelect={(index) => { setRecordSelection({ index }) }}
        onRecordFocus={(index) => { setRecordFocus({ index }) }}
      />
      <div className={viewsCss.ledger}>
        <TrajectoryTable
          t={t}
          stringWrapping={stringWrapping}
          renderSlot={renderImageSlot}
          loadImage={loadImage}
          requestNumbers={requestNumbers}
          turns={turns}
          timelineFocusIndexes={timelineFocusIndexes}
          searchMatchIndexes={searchMatchIndexes}
          onSelectedIndexChange={setSelectedIndex}
          recordSelection={recordSelection}
          recordFocus={recordFocus}
          historyLoading={entry?.loading === true && turns.length === 0}
          olderHistoryLoading={entry?.olderLoading === true}
          historyStartSeq={entry?.oldestTurn}
          hasOlderRecords={entry?.hasOlderTurns === true}
          onLoadOlder={handleLoadOlder}
          onClearSelection={() => { setTimelineRange(null) }}
          collapsedTurns={collapsedTurns}
          onToggleTurn={(turn) => {
            setCollapsedTurns((prev) => {
              const next = new Set(prev)
              if (next.has(turn)) next.delete(turn)
              else next.add(turn)
              return next
            })
          }}
          collapsedAssistants={collapsedAssistants}
          onToggleAssistant={(id) => {
            setCollapsedAssistants((prev) => {
              const next = new Set(prev)
              if (next.has(id)) next.delete(id)
              else next.add(id)
              return next
            })
          }}
          collapsedTools={collapsedTools}
          onToggleTool={(id) => {
            setCollapsedTools((prev) => {
              const next = new Set(prev)
              if (next.has(id)) next.delete(id)
              else next.add(id)
              return next
            })
          }}
          inspectCallId={inspectCallId}
          onInspectApplied={handleInspectApplied}
          onInspectSubagent={handleInspectSubagent}
        />
      </div>
    </div>
  )
}

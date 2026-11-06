/**
 * The subagent row: a `Task`/`Agent` tool call rendered as its own kind of
 * transcript row instead of a generic tool row.
 *
 * Upstream renders subagents as a **catalog of child sessions** — rows in a
 * header dropdown (`packages/client/ui-subagent/src/client/SubagentHeaderLineage.tsx`),
 * each carrying a description, an agent name, a status dot, and token/duration
 * metrics, opening a child *conversation*. This project's subagents are
 * in-session `Task`/`Agent` tool calls, so there is no child session to browse;
 * the row here reuses upstream's row *vocabulary* (the status dot, the
 * description-first label, the trailing token/duration metric column) on the
 * in-session call, and adds what upstream's catalog row has no place for: the
 * sub-agent's own **live progress log**, streamed by the gateway's
 * `tool.progress` frames.
 *
 * What is deliberately not ported: the catalog tree/disclosure machinery
 * (`CatalogRows`' recursion, `toggleBranch`, breadcrumbs), the external-task
 * leaves, and the shared-task footer. Those browse a session tree this product
 * does not have; recorded as deviations in `webui/UPSTREAM.md`.
 */
import { useCallback, useEffect, useMemo, useState } from 'react'
import { DisclosureRow, ReadBlock, StateDot } from '@primitives'
import clsx from 'clsx'
import css from '@chat-styles/ToolRow.module.css'
import subagentCss from '@chat-styles/SubagentRow.module.css'
import type { ToolCallState, ToolProgressEntry } from '../store/conversations'
import { isSubagentDelegationTool } from '../store/turnProcess'
import { useAgentTranscript } from '../store/agentTranscript'
import { useAgentRecord } from '../store/contextTimeline'
import { useSubagentFocus } from '../store/subagentFocus'
import { useTranslate } from '../i18n/useTranslate'
import { SUBAGENT_NS, subagentDicts } from '../i18n/dictionaries/subagent'
import { readLabels } from './labels'
import { MessageItem } from './MessageItem'
import { splitLines } from './ToolCallRow'

/**
 * Whether a tool call is a subagent delegation, and so renders as a
 * {@link SubagentRow} rather than a generic tool row. The literals live in
 * one place — the vendored turn-process rule the turn-level fold already
 * counts with — so the row and the fold's `subagentCount` can never disagree
 * about which calls are subagents.
 *
 * @param call - the reduced tool call.
 * @returns whether the call delegates to a subagent.
 */
export function isSubagentCall(call: ToolCallState): boolean {
  return isSubagentDelegationTool(call.name)
}

/** One metric cell's parts, or null when the fact is absent on this row. */
interface DurationParts {
  seconds: number
  minutes: number
  hours: number
  days: number
  totalMinutes: number
  totalHours: number
}

/** Split a millisecond duration into the calendar parts the copy needs. */
function splitDuration(ms: number): DurationParts {
  const totalSeconds = Math.floor(Math.max(0, ms) / 1_000)
  const totalMinutes = Math.floor(totalSeconds / 60)
  const totalHours = Math.floor(totalMinutes / 60)
  return {
    seconds: totalSeconds % 60,
    minutes: totalMinutes % 60,
    hours: totalHours % 24,
    days: Math.floor(totalHours / 24),
    totalMinutes,
    totalHours,
  }
}

type Translate = ReturnType<typeof useTranslate>

/**
 * Format a duration with decreasing precision at larger scales — upstream's
 * `formatDuration` (`SubagentHeaderLineage.tsx`) verbatim, minus the
 * month/year branches a subagent call can never reach.
 */
export function formatSubagentDuration(ms: number, t: Translate): string {
  const { seconds, minutes, hours, days, totalMinutes, totalHours } = splitDuration(ms)
  if (days > 0) {
    return hours === 0
      ? t('duration.days', { days })
      : t('duration.daysHours', { days, hours })
  }
  if (totalHours > 0) {
    return t('duration.hours', {
      hours: totalHours,
      minutes: String(minutes).padStart(2, '0'),
      seconds: String(seconds).padStart(2, '0'),
    })
  }
  if (totalMinutes > 0) {
    return t('duration.minutes', {
      minutes: totalMinutes,
      seconds: String(seconds).padStart(2, '0'),
    })
  }
  return t('duration.seconds', { seconds })
}

/** Upstream's `formatTokens`: one decimal under 100, whole above, K/M above 1e3/1e6. */
export function formatSubagentTokens(value: number, t: Translate): string {
  const scaled = (next: number): string => next >= 100
    ? String(Math.round(next))
    : String(Math.round(next * 10) / 10)
  if (value < 1_000) return String(value)
  if (value < 1_000_000) return t('tokens.thousand', { value: scaled(value / 1_000) })
  return t('tokens.million', { value: scaled(value / 1_000_000) })
}

/** The launching call's own `description` argument, else the `subagent_type`. */
function subagentLabel(call: ToolCallState): string {
  const description = call.args?.description
  if (typeof description === 'string' && description.trim() !== '') return description
  const type = call.args?.subagent_type
  if (typeof type === 'string' && type.trim() !== '') return type
  return ''
}

/** The sub-agent's prompt, the expanded row's first block. */
function subagentPrompt(call: ToolCallState): string | null {
  const prompt = call.args?.prompt
  return typeof prompt === 'string' && prompt.trim() !== '' ? prompt : null
}

/**
 * One live progress entry's text: the sub-agent's activity, one line per
 * activity, unlabelled.
 *
 * This is the child's ticker, not its transcript — a frame carries a single
 * activity line (a tool call reads as `Read(path)`, a turn with no tool call
 * reads as its prose), and the row's transcript panel is where the child's
 * output actually lives. So the line renders as itself: no gutter label (the
 * agent id is the row's identity, not this line's — repeating it on every
 * activity is noise) and no line numbers (one activity is not a file).
 */
function ProgressLine({ entry }: { entry: ToolProgressEntry }) {
  const text = entry.content ?? entry.message ?? ''
  if (text === '') return null
  return (
    <div className={subagentCss.progressLine} data-role={entry.content !== undefined ? 'message' : 'status'}>
      {text}
    </div>
  )
}

/**
 * The requested sub-agent's restored conversation, fetched on first open.
 *
 * Rows are reduced and rendered exactly as the chat renders its own — same
 * `toMessageState`, same `MessageItem` — so what the reader sees here is the
 * sub-agent's transcript as the transcript is, not a second rendering of it
 * that could disagree. `inSubagentTranscript` keeps a nested delegation from
 * expanding: it has its own sidechain file that this row's ids cannot address.
 *
 * The gateway's three states are the caller's to distinguish: a transcript the
 * session cannot serve (no sessionId yet, or a sub-agent whose file was never
 * written) returns no messages, which reads the same as "left no record" —
 * both are honest here, and neither is an error.
 */
function TranscriptPanel({ sessionId, agentId, t }: {
  /** Both ids are resolved by the caller's guard; a panel is never mounted without them. */
  sessionId: string
  agentId: string
  t: Translate
}) {
  const entry = useAgentTranscript((state) => state.transcripts[`${sessionId}\u0000${agentId}`])
  const reload = useAgentTranscript((state) => state.reload)

  if (entry === undefined || entry.status === 'loading') {
    return <div className={subagentCss.transcriptNote}>{t('transcript.loading')}</div>
  }
  if (entry.status === 'failed') {
    return (
      <div className={subagentCss.transcriptNote} data-role="error">
        <span>{t('transcript.error')}</span>
        {entry.message !== null && <span className={subagentCss.transcriptDetail}>{entry.message}</span>}
        <button type="button" className={subagentCss.transcriptRetry} onClick={() => { reload(sessionId, agentId) }}>
          {t('transcript.retry')}
        </button>
      </div>
    )
  }
  if (entry.messages.length === 0) {
    return <div className={subagentCss.transcriptNote}>{t('transcript.empty')}</div>
  }
  return (
    <div className={subagentCss.transcript} data-role="transcript">
      {entry.messages.map((row) => (
        <MessageItem
          key={row.id}
          message={row}
          sessionId={sessionId}
          inSubagentTranscript
        />
      ))}
    </div>
  )
}

/**
 * One subagent call row: the disclosure header (status dot + description +
 * trailing metrics) and, expanded, the prompt, the live progress log, and —
 * when the gateway can serve it — the sub-agent's own restored conversation.
 */
export function SubagentRow({ call, sessionId = null, defaultOpen = false }: {
  call: ToolCallState
  /**
   * The session the call was made in. The sub-agent's sidechain transcript is
   * stored per session, so without it the row can still show live progress
   * but cannot open the stored conversation.
   */
  sessionId?: string | null
  defaultOpen?: boolean
}) {
  const t = useTranslate(SUBAGENT_NS, subagentDicts)
  const [expanded, setExpanded] = useState(defaultOpen)
  const [showTranscript, setShowTranscript] = useState(false)
  const loadTranscript = useAgentTranscript((state) => state.load)

  const label = subagentLabel(call)
  const prompt = subagentPrompt(call)
  const progress = call.progress
  // The subagent's own reported output, once the call has settled. The task
  // result is the delegation's answer, not an error string, so it is rendered
  // as text.
  const settledResult = call.resultData !== null && call.resultData !== ''
    ? call.resultData
    : null
  const hasBody = prompt !== null || progress.length > 0 || settledResult !== null
  const open = expanded && hasBody

  // The sub-agent id is the one the gateway keys its sidechain file and its
  // transcript endpoint by, and it is NOT the launching tool-use id — those
  // are separate id spaces, and handing over the latter is what the gateway
  // answers `unknown agent: <tool_use_id>` for.
  //
  // The detail ledger's record is the authoritative source: it carries the id
  // for every agent the session ever launched, running or settled, and it
  // survives a reload. The live `tool.progress` frames carry it too, so they
  // are the second source — an agent that is still running, whose record has
  // not been read yet, still resolves.
  //
  // Null when neither has answered. The row then reports that it cannot
  // address the transcript rather than substituting the tool-use id, which
  // fails identically but says "unknown agent" about an id that was never an
  // agent id in the first place.
  const record = useAgentRecord(sessionId, call.toolUseId)
  const agentId = useMemo<string | null>(() => {
    const fromRecord = record?.agentId
    if (fromRecord !== undefined && fromRecord !== '') return fromRecord
    for (let index = progress.length - 1; index >= 0; index -= 1) {
      const id = progress[index]?.agentId
      if (id !== undefined && id !== '') return id
    }
    return null
  }, [record, progress])

  // The fetch is addressed outside the state updater so the guard narrows:
// both ids are required, and a closed transcript is never refetched when the
// reader reopens it (the store already holds it).
  const toggleTranscript = useCallback(() => {
    if (!showTranscript && sessionId !== null && agentId !== null) {
      loadTranscript(sessionId, agentId)
    }
    setShowTranscript((value) => !value)
  }, [showTranscript, loadTranscript, sessionId, agentId])

  // The catalog's request to open this subagent. A pick can land before this
  // row mounts (the catalog sits in the header, the transcript below it), so
  // the pending request is subscribed to rather than read once.
  //
  // The claim is destructive and guarded by the id, so a request addressed to
  // another agent — or already consumed by another row — leaves this row
  // alone. Opening the transcript is part of honouring the request: a reader
  // who picked a row out of the catalog asked to read that subagent, not
  // merely to see its prompt.
  const focusPending = useSubagentFocus((state) => state.pending)
  const claimFocus = useSubagentFocus((state) => state.claim)
  useEffect(() => {
    if (sessionId === null || agentId === null) return
    if (focusPending === null || focusPending.agentId !== agentId) return
    if (!claimFocus(agentId)) return
    setExpanded(true)
    setShowTranscript(true)
    loadTranscript(sessionId, agentId)
  }, [sessionId, agentId, focusPending, claimFocus, loadTranscript])

  const dotState = call.status === 'pending'
    ? 'ongoing'
    : call.status === 'failed' ? 'error' : 'done'
  const statusText = call.status === 'pending'
    ? t('status.running')
    : call.status === 'failed' ? t('status.failed') : t('status.completed')

  // Metrics keep the upstream column shape (tokens over duration); the live
  // path has no durable per-call usage, so only a settled call's own summary
  // contributes, and the column renders only when it has something to say.
  const metrics = useMemo(() => {
    const toolUses = typeof call.args?.tool_uses === 'number' ? call.args.tool_uses : undefined
    return toolUses === undefined ? null : t('metrics.toolUses', { count: toolUses })
  }, [call.args, t])

  const summary = label !== '' ? label : statusText

  return (
    <div
      className={css.root}
      data-tool={call.name}
      data-state={call.status === 'pending' ? 'running' : 'ok'}
      // The status dot and the sweep are aria-hidden/colour-only, and the
      // description says nothing about the outcome, so the run state is
      // carried for assistive technology here — the same role the tool row's
      // `visuallyHidden` span plays, in the one attribute this row already
      // needs for its composed label (upstream's catalog row does the same).
      aria-label={[summary, statusText, metrics ?? ''].filter((part) => part !== '').join(' · ')}
      role="group"
    >
      <DisclosureRow
        rowClassName={clsx(css.row, subagentCss.row)}
        leadingClassName={css.leading}
        titleClassName={css.title}
        chevronClassName={css.chevron}
        icon={<StateDot state={dotState} />}
        title={call.name === 'Task' ? t('title.task') : t('title.subagent')}
        open={open}
        expandable={hasBody}
        expandOnRowClick
        keepContentWhenOpen
        onToggle={() => { setExpanded((value) => !value) }}
        collapsedContent={(
          <>
            <span className={css.sep} aria-hidden />
            <span className={clsx(css.summary, subagentCss.summary)}>{summary}</span>
            {metrics !== null && (
              <span className={clsx(css.summarySuffix, subagentCss.metrics)}>{metrics}</span>
            )}
          </>
        )}
      >
        <div className={subagentCss.body}>
          {prompt !== null && (
            <div className={subagentCss.prompt}>
              <span className={subagentCss.promptLabel}>{t('prompt.label')}</span>
              <span className={subagentCss.promptText}>{prompt}</span>
            </div>
          )}
          <div className={subagentCss.progress}>
            <span className={subagentCss.progressLabel}>{t('progress.label')}</span>
            {progress.length === 0
              ? settledResult !== null
                // A settled call whose live frames are gone (a reloaded
                // transcript is rebuilt from a snapshot that carries no
                // progress) still has its result. Rendering that under the
                // progress label is honest — it IS what the subagent
                // produced — and it replaces a "waiting" note that would be
                // false about a call that has already finished.
                ? <ReadBlock
                    label={t('result.label')}
                    labels={readLabels}
                    lines={splitLines(settledResult)}
                    totalLines={splitLines(settledResult).length}
                    className={subagentCss.progressBlock}
                  />
                : <div className={subagentCss.progressEmpty}>
                    {call.status === 'pending' ? t('progress.empty') : t('progress.settled')}
                  </div>
              : progress.map((entry, index) => (
                <ProgressLine key={index} entry={entry} />
              ))}
          </div>
          {/* The restored conversation lives behind its own toggle: it is a
              second fetch, and expanding the row to read the prompt should
              not silently pull a whole transcript down with it.
              
              The toggle is absent while no agent id has been resolved: there
              is nothing to address, and a button that can only fail is worse
              than no button. The record read that resolves it is in flight;
              the row re-renders with the control once it lands. */}
          <div className={subagentCss.transcriptSection}>
            {sessionId !== null && agentId !== null && (
              <>
                <button
                  type="button"
                  className={subagentCss.transcriptToggle}
                  aria-expanded={showTranscript}
                  onClick={toggleTranscript}
                >
                  {t('transcript.open')}
                </button>
                {showTranscript && (
                  <TranscriptPanel sessionId={sessionId} agentId={agentId} t={t} />
                )}
              </>
            )}
          </div>
        </div>
      </DisclosureRow>
    </div>
  )
}
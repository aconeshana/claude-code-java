/**
 * Per-session context timeline state over the gateway's dsh-context routes
 * (`/api/session/context/{timeline,detail,content}`), feeding the Context
 * tab, the `/context` modal, and the Chat→Context jump.
 *
 * Generation model (mirrors dsh-context's split generation): the slim HEAD
 * is what this store polls — cheap, and its `detailRev` is the cursor the
 * vendored `DetailStore` (vendor/dsh-context/client/timelineSource.ts)
 * trails with a single-flight, debounced detail read. Refresh triggers are
 * mirror frames (`turn.started` / `tool.completed` / `turn.completed` /
 * `session.idle`) for the addressed session, debounced 300ms, and only
 * while a viewer (tab, modal, jump) is open on that session — closed tabs
 * fetch nothing. A cold session (not attached in this gateway process)
 * answers `timeline: null`; the store marks it `cold` and the UI shows the
 * unavailable note instead of an empty chart.
 *
 * Content (a node's text, the system prompt, the tool schemas) is fetched on
 * demand through the two fetcher factories below and cached per session for
 * the page lifetime (the ledger's content is immutable per seq).
 */
import { useEffect, useMemo, useSyncExternalStore } from 'react'
import { create } from 'zustand'
import { fetchContextContent, fetchContextDetail, fetchContextTimeline } from '../api/client'
import type { MirrorFrame } from '../api/types'
import type { ContentFetcher, ConversationNodeLike, HeaderFetcher } from '@dsh-context/client/narrow'
import { asRecord, timelineOf } from '@dsh-context/client/narrow'
import { detailOf, detailStoreOf, type DetailFetcher } from '@dsh-context/client/timelineSource'
import type { AgentRecord, ContextTimeline, ContextTimelineDetail, HeaderEpochContent } from '@dsh-context/shared/types'

export interface SessionTimelineState {
  /** The narrowed slim head; null before the first answer or while cold. */
  readonly head: ContextTimeline | null
  /** The gateway answered `timeline: null`: the session is not live in this process. */
  readonly cold: boolean
  /** A head read is in flight. */
  readonly loading: boolean
  /** The last head read failed (transport); the previous head stays. */
  readonly failed: boolean
}

export interface ContextTimelineStore {
  readonly sessions: Readonly<Record<string, SessionTimelineState>>
  /** Chat→Context jump relay: the request to pin, consumed once by the tab (dsh viewFocus). */
  readonly jump: ContextJump | null
  /** Register a viewer on `sessionId`; returns the release. Refreshes the head on the first viewer. */
  open(sessionId: string): () => void
  /** Pull the head now (viewer-independent; the jump and the modal call it). */
  refresh(sessionId: string): Promise<void>
  /** Mirror-frame hook: schedules a debounced refresh for viewed sessions. */
  applyFrame(frame: MirrorFrame): void
  requestJump(sessionId: string, target: ContextJumpTarget): void
  takeJump(sessionId: string): ContextJumpTarget | null
}

/**
 * What the chat row knows about the reply it wants pinned. dsh resolves the
 * request seq from its conversation node seat; our chat rows carry the
 * transcript timestamp and the 1-based turn instead, so the Context tab
 * resolves the seq against its own request ledger (`resolveJumpSeq`).
 */
export interface ContextJumpTarget {
  readonly seq?: number | undefined
  /** The reply's durable transcript timestamp, epoch ms. */
  readonly time?: number | undefined
  /** The reply's 1-based turn number. */
  readonly turn?: number | undefined
}

export interface ContextJump extends ContextJumpTarget {
  readonly sessionId: string
}

const REFRESH_EVENTS: ReadonlySet<string> = new Set([
  'turn.started', 'tool.completed', 'turn.completed', 'session.idle', 'turn.cancelled',
])
const DEBOUNCE_MS = 300

const EMPTY: SessionTimelineState = { head: null, cold: false, loading: false, failed: false }

const viewers = new Map<string, number>()
const timers = new Map<string, ReturnType<typeof setTimeout>>()
/** Per-session refresh ticket; a response that is not the newest ticket is dropped. */
const refreshTickets = new Map<string, number>()

export const useContextTimeline = create<ContextTimelineStore>((set, get) => {
  const patch = (sessionId: string, next: Partial<SessionTimelineState>): void => {
    set((state) => ({
      sessions: { ...state.sessions, [sessionId]: { ...(state.sessions[sessionId] ?? EMPTY), ...next } },
    }))
  }
  const schedule = (sessionId: string): void => {
    if (timers.has(sessionId)) return
    timers.set(sessionId, setTimeout(() => {
      timers.delete(sessionId)
      void get().refresh(sessionId)
    }, DEBOUNCE_MS))
  }
  return {
    sessions: {},
    jump: null,

    open(sessionId) {
      const count = viewers.get(sessionId) ?? 0
      viewers.set(sessionId, count + 1)
      if (count === 0) void get().refresh(sessionId)
      let released = false
      return () => {
        if (released) return
        released = true
        const now = viewers.get(sessionId) ?? 0
        if (now <= 1) {
          viewers.delete(sessionId)
          const timer = timers.get(sessionId)
          if (timer !== undefined) {
            clearTimeout(timer)
            timers.delete(sessionId)
          }
        } else {
          viewers.set(sessionId, now - 1)
        }
      }
    },

    async refresh(sessionId) {
      // Last request wins: an `open()` refresh overlapping a debounced mirror
      // refresh (or a slow response outliving a session switch) must not
      // overwrite a newer head with an older one.
      const ticket = (refreshTickets.get(sessionId) ?? 0) + 1
      refreshTickets.set(sessionId, ticket)
      const current = (): boolean => refreshTickets.get(sessionId) === ticket
      patch(sessionId, { loading: true })
      try {
        const raw = await fetchContextTimeline(sessionId)
        if (!current()) return
        if (raw === null) {
          patch(sessionId, { head: null, cold: true, loading: false, failed: false })
          return
        }
        const head = timelineOf(raw)
        // A revision that went backwards is a fresh ledger (the session was
        // closed and re-opened): its seqs restart, so cached node text keyed
        // by seq would lie. Drop it.
        const previous = get().sessions[sessionId]?.head?.detailRev
        const next = head?.detailRev
        if (next !== undefined && previous !== undefined && next < previous) forgetContextContent(sessionId)
        patch(sessionId, { head, cold: false, loading: false, failed: head === null })
      } catch {
        if (current()) patch(sessionId, { loading: false, failed: true })
      }
    },

    applyFrame(frame) {
      if (!REFRESH_EVENTS.has(frame.event)) return
      const sessionId = (frame.data as { session_id?: unknown }).session_id
      if (typeof sessionId !== 'string' || !viewers.has(sessionId)) return
      schedule(sessionId)
    },

    requestJump(sessionId, target) {
      set({ jump: { sessionId, ...target } })
    },

    takeJump(sessionId) {
      const jump = get().jump
      if (jump === null || jump.sessionId !== sessionId) return null
      set({ jump: null })
      const { sessionId: _ignored, ...target } = jump
      return target
    },
  }
})

/** Test isolation: viewer counts and pending timers. */
export function resetContextTimelineViewers(): void {
  viewers.clear()
  for (const timer of timers.values()) clearTimeout(timer)
  timers.clear()
  useContextTimeline.setState({ sessions: {}, jump: null })
}

/** The detail reader the vendored DetailStore trails with (null = cold, throws on transport/malformed). */
export const detailFetcher: DetailFetcher = async (sessionId: string): Promise<ContextTimelineDetail | null> => {
  const raw = await fetchContextDetail(sessionId)
  if (raw === null) return null
  const detail = detailOf(raw)
  if (detail === null) throw new Error('context detail malformed')
  return detail
}

/**
 * What the chat header's subagent catalog reads: this session's subagent
 * records, trailing the same per-session detail ledger the Context tab uses.
 *
 * `open` registers a viewer, which is what makes the store poll the head at
 * all — the catalog is a viewer in its own right, so a session whose Context
 * tab was never opened still gets its agents. The detail read is the vendored
 * `DetailStore`'s single-flight trailing fetch, shared with the tab and the
 * modal, so a session with both open issues one read, not two.
 *
 * `agents` is `undefined` until a payload carrying the extension lands — the
 * catalog renders its loading note for that case and its empty note only once
 * a read has answered.
 */
export function useSessionAgents(sessionId: string | null): {
  agents: readonly AgentRecord[] | undefined
  failed: boolean
  retry: () => void
} {
  const key = sessionId ?? ''
  const openViewer = useContextTimeline((state) => state.open)
  useEffect(() => {
    if (key === '') return
    return openViewer(key)
  }, [key, openViewer])

  const entry = useContextTimeline((state) => state.sessions[key])
  const head = entry?.head ?? null
  const store = useMemo(() => detailStoreOf(key, detailFetcher), [key])
  const snap = useSyncExternalStore(store.subscribe, store.getSnapshot)
  const headRev = head !== null && typeof head.detailRev === 'number' ? head.detailRev : null
  useEffect(() => {
    if (headRev !== null) store.request(headRev)
  }, [store, headRev])

  const detail = snap.detail
  const agents: readonly AgentRecord[] | undefined = key === '' ? undefined : detail?.agents
  return { agents, failed: snap.failed, retry: store.retry }
}

/**
 * One subagent's record from this session's detail ledger, found by the
 * tool-use id of the call that launched it.
 *
 * This is a **reader**, not a viewer: it registers nothing and requests
 * nothing, so a transcript row can resolve its agent id without becoming a
 * second poller beside the header catalog. The catalog is what keeps the
 * ledger alive for a session — it is mounted for every session the reader has
 * open — and a row that renders before the first read answers simply gets
 * `null` and falls back, then re-renders when the ledger lands.
 *
 * The record's `agentId` is the only authoritative spelling of a sub-agent's
 * identity: it is what the sidechain transcript endpoint is keyed by, and it
 * survives a reload, where the live `tool.progress` frames that also carry it
 * do not (a settled transcript's rows are rebuilt from the snapshot, which
 * carries no progress). The launching tool-use id is a *different* id space
 * and is never a valid substitute.
 */
export function useAgentRecord(sessionId: string | null, toolUseId: string): AgentRecord | null {
  const key = sessionId ?? ''
  const store = useMemo(() => detailStoreOf(key, detailFetcher), [key])
  const snap = useSyncExternalStore(store.subscribe, store.getSnapshot)
  const agents = snap.detail?.agents
  if (key === '' || agents === undefined) return null
  return agents.find((record) => record.id === toolUseId) ?? null
}

// ---- on-demand content -----------------------------------------------------

const contentCache = new Map<string, Map<number, Promise<ConversationNodeLike | null>>>()
const headerCache = new Map<string, { content: HeaderEpochContent | null; at: number }>()
/** Header epoch re-read cadence: matches the gateway ledger's own header TTL. */
const HEADER_TTL_MS = 15_000

/**
 * A node's stored text as the `ConversationNodeLike` the vendored browser
 * renders: the ledger answers `{seq, cat, tool?, name?, text}`; a tool row
 * becomes a `tool-result` node (its call name attached), an assistant row
 * an `assistant` node with one text block, anything else a plain content
 * node. Null when the ledger no longer holds the seq.
 */
export function makeContentFetcher(sessionId: string): ContentFetcher {
  return (seq: number): Promise<ConversationNodeLike | null> => {
    // The session's map is looked up per call, not captured: the fetcher is
    // memoized by the view for the session's lifetime, and
    // `forgetContextContent` swaps the map out from under it.
    let bySeq = contentCache.get(sessionId)
    if (bySeq === undefined) {
      bySeq = new Map()
      contentCache.set(sessionId, bySeq)
    }
    // The promise itself is cached: concurrent misses on one row (an
    // expand racing a re-render) share one GET, and a settled promise is
    // the memo. A transport failure evicts so the next expand retries.
    const hit = bySeq.get(seq)
    if (hit !== undefined) return hit
    const owner = bySeq
    const pending = fetchContextContent(sessionId, { seq })
      .then((raw) => nodeOfContent(raw, seq))
      .catch((failure: unknown) => {
        owner.delete(seq)
        throw failure
      })
    owner.set(seq, pending)
    return pending
  }
}

export function nodeOfContent(raw: Record<string, unknown> | null, seq: number): ConversationNodeLike | null {
  const data = asRecord(raw)
  if (data === null) return null
  const text = typeof data.text === 'string' ? data.text : ''
  const cat = typeof data.cat === 'string' ? data.cat : ''
  if (cat === 'tool') {
    return {
      kind: 'tool-result',
      seq,
      call: typeof data.tool === 'string' ? { name: data.tool, argsRaw: '' } : null,
      content: [{ type: 'text', text }],
    }
  }
  if (cat === 'assistant') {
    return { kind: 'assistant', seq, blocks: [{ type: 'text', text }] }
  }
  return { kind: cat === '' ? 'user' : cat, seq, content: [{ type: 'text', text }] }
}

/**
 * The header epoch content: the live system prompt plus every tool
 * definition, two GETs (`kind=system`, `kind=tools`) cached per session —
 * the epoch `seq` is ignored because this gateway serves one live epoch.
 */
export function makeHeaderFetcher(sessionId: string): HeaderFetcher {
  return async (): Promise<HeaderEpochContent | null> => {
    const hit = headerCache.get(sessionId)
    if (hit !== undefined && Date.now() - hit.at < HEADER_TTL_MS) return hit.content
    const [system, tools] = await Promise.all([
      fetchContextContent(sessionId, { kind: 'system' }),
      fetchContextContent(sessionId, { kind: 'tools' }),
    ])
    const content = headerContentOf(system, tools)
    headerCache.set(sessionId, { content, at: Date.now() })
    return content
  }
}

export function headerContentOf(system: Record<string, unknown> | null, tools: Record<string, unknown> | null): HeaderEpochContent | null {
  if (system === null && tools === null) return null
  const out: HeaderEpochContent = { tools: [] }
  const text = system?.text
  if (typeof text === 'string' && text !== '') out.system = text
  const list = tools?.tools
  if (Array.isArray(list)) {
    for (const entry of list) {
      const row = asRecord(entry)
      if (row === null || typeof row.name !== 'string') continue
      out.tools.push({
        name: row.name,
        ...(typeof row.description === 'string' ? { description: row.description } : {}),
        ...(row.schema !== undefined ? { schema: row.schema } : {}),
      })
    }
  }
  return out
}

/** Drop a session's cached content (a compaction rewrote its history). */
export function forgetContextContent(sessionId: string): void {
  contentCache.delete(sessionId)
  headerCache.delete(sessionId)
}

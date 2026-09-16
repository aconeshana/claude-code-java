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
import { create } from 'zustand'
import { fetchContextContent, fetchContextDetail, fetchContextTimeline } from '../api/client'
import type { MirrorFrame } from '../api/types'
import type { ContentFetcher, ConversationNodeLike, HeaderFetcher } from '@dsh-context/client/narrow'
import { asRecord, timelineOf } from '@dsh-context/client/narrow'
import { detailOf, type DetailFetcher } from '@dsh-context/client/timelineSource'
import type { ContextTimeline, ContextTimelineDetail, HeaderEpochContent } from '@dsh-context/shared/types'

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
      patch(sessionId, { loading: true })
      try {
        const raw = await fetchContextTimeline(sessionId)
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
        patch(sessionId, { loading: false, failed: true })
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
  let cache = contentCache.get(sessionId)
  if (cache === undefined) {
    cache = new Map()
    contentCache.set(sessionId, cache)
  }
  const bySeq = cache
  return (seq: number): Promise<ConversationNodeLike | null> => {
    // The promise itself is cached: concurrent misses on one row (an
    // expand racing a re-render) share one GET, and a settled promise is
    // the memo. A transport failure evicts so the next expand retries.
    const hit = bySeq.get(seq)
    if (hit !== undefined) return hit
    const pending = fetchContextContent(sessionId, { seq })
      .then((raw) => nodeOfContent(raw, seq))
      .catch((failure: unknown) => {
        bySeq.delete(seq)
        throw failure
      })
    bySeq.set(seq, pending)
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

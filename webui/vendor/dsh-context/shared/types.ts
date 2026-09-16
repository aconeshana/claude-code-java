/**
  * Shared wire contract — the snapshot model exchanged between the Host and Client halves. Delivered as the `view()` payload of the
  * `contextTimeline`/`contextHeaders` session projections (registered on `ctx.sessionProjections`; the registry pushes finished views as
  * `session/projection` frames — see host/timeline.ts). TYPE-ONLY host-side module: both halves import these as `import type`, so nothing
  * from here ever reaches the runtime bundles.
 */

// Vendored into claude-code-java: the cordis/session-projection augmentation
// (`SessionProjectionMap`/`SessionProjectionStateMap`) and the host fold-state
// imports are cut — the Java gateway serves these shapes over plain REST
// (`/api/session/context/{timeline,detail,content,overview}`).

/**
 * The priced surface buckets. `skill` carries every skill-machinery content
 * the harness injects (issue #66): the `<available_skills>` catalog digest,
 * a user-explicit `/name` invocation's instructions message, and the content
 * a `skill`-tool load returns (modeled as a tool result by the harness).
 */
export type Category = 'user' | 'inject' | 'skill' | 'assistant' | 'tool'

/**
 * One live system-prompt node (Snapshot.systems) — the harness models the
 * system prompt as a surface node, so its TEXT is fetched on demand from the
 * event at `seq`: a V3 `system/message` event, or the V0/V2 `request/header`
 * whose envelope carried `header.system`. `tokens` is the node's heuristic
 * price (0 for a dormant empty node, which the harness reads as "no system
 * prompt"); the effective figure is the LAST node with `tokens > 0`.
 */
export interface SystemPromptNode {
  seq: number
  time: number
  tokens: number
}

/**
 * The stats board's count figures, precomputed host-side over the RETAINED
 * request/event records (the same set the detail payload serves). Carried by
 * the split-generation wire head so the board — and the Agent card's
 * per-session request tally — never need the collections themselves.
 * `steps` doubles as the retained request-record count.
 */
export interface TimelineCounts {
  turns: number
  steps: number
  injects: number
  compactions: number
  prunes: number
}

/**
 * The newest retained request record's billing summary — the headline's
 * derived-occupancy anchor (`prompt + surface movement since`), carried by
 * the split-generation wire head so the headline never needs the request
 * records themselves.
 */
export interface TimelineLast {
  seq: number
  total: number
  prompt?: number | undefined
}

/** One day's ledger entry in the `contextActivity` projection. */
export interface ActivityDay {
  /**
   * Billed tokens folded from provider-reported usage that day (prompt-side
   * input + cache read/write + output). Requests without a usage settlement
   * count only toward `requests` — a fabricated 0 never understates the day.
   */
  tokens: number
  /** Completed model calls (assistant settlements) that day. */
  requests: number
}

/**
 * The per-session daily activity ledger (`contextActivity` wire value):
 * day key (`YYYY-MM-DD`, host-local — see shared/days.ts) → that day's
 * billed volume, retention-capped by the fold. The Context Dashboard merges
 * every listed session's ledger into its activity heatmap.
 */
export interface ContextActivity {
  days: Record<string, ActivityDay>
}

/**
 * The per-user display-preference vocabulary of the `dsh-context` settings
 * namespace — the ONE declaration both halves share: the Host registers the
 * namespace schema against it (host/settings.ts), the Client binds the scope
 * and edits fields by name (client/settings.ts). Type-only, so both bundles
 * erase it.
 */
export type DefaultGranularity = 'step' | 'turn'

export type DefaultTrendMode = 'total' | 'delta'

/** File Activity row order: most operations first, most-recently-touched first, or path ascending. */
export type DefaultFileSort = 'count' | 'latest' | 'path'

/** Tool-definition row order: largest schema first, most call hits first, or name ascending. */
export type DefaultToolSort = 'size' | 'count' | 'name'

/** Where the Context view is offered: the conversation tab, the right Sidebar, or both. */
export type DefaultPlacement = 'all' | 'tab' | 'sidebar'

/** Whether the Context Insights panel's sidebar entry is offered at all. */
export type InsightsEntry = 'show' | 'hide'

export interface PluginSettings {
  defaultPlacement: DefaultPlacement
  defaultGranularity: DefaultGranularity
  defaultTrendMode: DefaultTrendMode
  defaultToolSort: DefaultToolSort
  defaultFileSort: DefaultFileSort
  insightsEntry: InsightsEntry
}

/** The section fields the settings card edits, as the Host schema names them. */
export type SettingsField = keyof PluginSettings

export interface Snapshot {
  ok: boolean
  /**
   * The host's baseline-gate record, present ONLY when the running harness
   * is below the plugin's supported baseline: the host then folds nothing
   * and every figure in this snapshot is zero/empty. The client keeps
   * rendering the (blank) cards and pops the upgrade gate modal naming
   * `current` (the detected harness version) and `minimum` (the baseline).
   */
  unsupported?: {
    current: string
    minimum: string
  }
  model?: string | undefined
  provider?: string | undefined
  contextWindow?: number | undefined
  current: {
    system: number
    tools: number
    user: number
    inject: number
    skill: number
    assistant: number
    tool: number
    total: number
  }
  /**
   * Image blocks live in the CURRENT context (user uploads plus tool-result
   * images, nested blocks included) — the sum over the live surface nodes'
   * `imgs`, so compaction/prune shrink it. Absent from older hosts; clients
   * treat absence as zero.
   */
  images?: number | undefined
  /**
   * Tool calls whose result is live in the CURRENT context (one `tool/result`
   * folds to one `tool` surface node). Calls still in flight and results
   * compacted/pruned out of the surface are not counted. Absent from older
   * hosts; clients treat absence as zero.
   */
  toolCalls?: number | undefined
  /**
   * Whole-session human-input tally: every non-injection `user/message`
   * (the user's own messages) plus every answered `ask_user_question`
   * result (one per answer submission). A running total over the COMPLETE
   * log — turns the retained window no longer holds still count. Absent
   * from older hosts; clients treat absence as zero.
   */
  humanInputs?: number | undefined
  /**
   * The user's newest own message as a one-line bounded preview (first text
   * block, whitespace collapsed, ~80 chars): the session cards' footer line.
   * Additive-optional — absent from rows folded before the field existed
   * (older hosts, idle sessions' cached rows); clients hide the line then.
   */
  lastUser?: string | undefined
  /**
   * Split-generation head fields — present exactly when the host serves the
   * SLIM head (the heavy collections moved to the on-demand detail channel,
   * host/detail.ts) and absent on the inline generation (older or
   * channel-less hosts serve the collections in place). `detailRev` is the
   * detail's revision marker: it bumps whenever the detail collections
   * change, so an open tab refetches on the push alone.
   */
  counts?: TimelineCounts | undefined
  last?: TimelineLast | undefined
  detailRev?: number | undefined
  /**
   * The per-request history / context-event collections. On the split
   * generation these stay ABSENT from the wire value (every session.list row,
   * control baseline, and push frame would carry them whole otherwise); the
   * client fills them from the detail channel ({@link ContextTimelineDetail}).
   */
  requests: RequestRecord[]
  events: ContextEventRecord[]
  /**
   * Cumulative session-cost raw material (per-provider, per-model billed
   * token totals — see SessionCostUsage). Absent until a request with a
   * known model reports usage.
   */
  cost?: SessionCostUsage | undefined
  /**
   * Whole-session timing totals (see TimingTotals). Absent until the first
   * step lifecycle completes in the log (older plugin builds never folded
   * one — clients treat absence as an empty timing card).
   */
  timing?: TimingTotals | undefined
  /**
   * The live system-prompt nodes, oldest first — the browser's per-step source
   * for the System section. Absent when the log carried no system prompt, and
   * on older plugin builds (the client then falls back to the header epoch's
   * own `systemTokens`, the pre-V3 shape).
   */
  systems?: SystemPromptNode[] | undefined
  /**
     * The served live surface: the newest `maxNodes` tail PLUS every live inject node older than the tail (injections land first and are
     * few,
    * so they are pinned). Seq-ordered, oldest first.
   */
  nodes: SurfaceNode[]
  /** Live nodes not served (the overflow beyond `maxNodes`, minus pinned injects — see `nodes`). */
  droppedNodes: number
  /**
   * Recently REMOVED surface nodes (compaction/prune shadows), each stamped
   * with `gone` (the replacing event's seq). Together with `nodes` this lets
   * the Context browser reconstruct the assembled surface of any retained
   * step: alive at request R = seq < R.seq && (gone undefined || gone > R.seq).
   */
  archive: SurfaceNode[]
  /**
   * Coverage floor of the served live `nodes`: the newest seq among the
   * `droppedNodes` live nodes not served. Present only when droppedNodes > 0.
   */
  surfaceFloor?: number | undefined
  /**
   * Coverage floor of `archive`: the newest `gone` among archive entries the
   * retention bounds dropped. Steps with seq < archiveFloor may miss removed
   * nodes (the browser shows the reconstruction as approximate).
   */
  archiveFloor?: number | undefined
  /**
   * The fold-derived file-operation log and its trim floor — present on the
   * INLINE wire value (channel-less hosts) and on the detail payload
   * (ContextTimelineDetail), absent from the slim head (they ride the detail
   * channel there).
   */
  fileOps?: FileOpRecord[] | undefined
  fileOpsFloor?: number | undefined
}

/**
 * The on-demand DETAIL payload of the split `contextTimeline` generation —
 * the heavy collections (per-request records, context events, the served
 * surface window, and the removed-node archive) that the slim wire head no
 * longer carries through every delivery channel. The host serves it off the
 * live fold state at the `/dsh-context` `detail` endpoint (host/detail.ts);
 * `rev` mirrors the head's `detailRev` at build time and acts as the
 * client's latest-wins cursor.
 */
/**
 * claude-code-java extension (not in upstream dsh-context): one subagent
 * call inside THIS session — our `Task`/`Agent` tool runs are not separate
 * sessions, so the Agent network card reads them off the detail payload
 * instead of the sessions face's `parentId` tree.
 */
export interface AgentRecord {
  /** The opening `tool_use` id. */
  id: string
  seq: number
  time?: number | undefined
  agentId?: string | undefined
  /** `subagent_type` of the call. */
  type?: string | undefined
  description?: string | undefined
  prompt?: string | undefined
  status: 'running' | 'done' | 'failed'
  endSeq?: number | undefined
  endTime?: number | undefined
  tokens?: number | undefined
  durationMs?: number | undefined
  toolUses?: number | undefined
  model?: string | undefined
  usage?: TokenUsage | undefined
}

export interface ContextTimelineDetail {
  /** claude-code-java extension: the session's subagent calls (see {@link AgentRecord}). */
  agents?: AgentRecord[] | undefined
  /** claude-code-java extension: the header epochs ride the detail payload (one GET serves both). */
  headers?: ContextHeaders | undefined
  rev: number
  /**
   * The slim wire head at the SAME fold cut as the collections: the
   * composition scalars (`current`), the window/model envelope, and the
   * precomputed counts. Sessions listed cold (never attached since the
   * requesting unit last changed) carry no `contextTimeline` projection row
   * for the browser's list reads, so the Agent network card fetches this
   * head per node to render their composition rings. The host always serves
   * it; optional so a payload missing it still serves the collections (the
   * detail cards) and only the ring composition degrades.
   */
  head?: ContextTimeline | undefined
  requests: RequestRecord[]
  events: ContextEventRecord[]
  nodes: SurfaceNode[]
  droppedNodes: number
  archive: SurfaceNode[]
  surfaceFloor?: number | undefined
  archiveFloor?: number | undefined
  /**
   * The fold-derived file-operation log (shared/fileOps.ts): one record per
   * executed file op, newest-retained, covering the full log (never
   * window-bound like the client-side join derivation it replaces on this
   * generation). Code-Mode nested dispatches book ops located on their
   * parent run_code result (`parent`).
   */
  fileOps?: FileOpRecord[] | undefined
  /** The newest dropped op's seq when the op log trimmed (coverage honesty, same family as archiveFloor). */
  fileOpsFloor?: number | undefined
}

/**
 * One executed file operation (a settled file-tool call with a resolved
 * target), folded host-side from the durable tool lifecycle: the call's
 * name+arguments (`tool/call`), the result's presentation meta and error
 * (`tool/result`), or a nested Code-Mode settle (`tool/code-dispatch`,
 * located on its parent run_code result via `parent` + `program`).
 *
 * `gone` is NOT host-stamped: the client joins it from the detail's archive
 * at render time (the op's result node leaving the live surface marks where
 * its content is still viewable). Line deltas are estimates read off the
 * call ARGUMENTS (an edit's old/new strings, a write's content), never off
 * result payloads.
 */
export interface FileOpRecord {
  seq: number
  /** The op's file; for a pathless search the searched PATTERN (`pattern: true`). */
  path: string
  kind: 'read' | 'write' | 'search'
  tool: string
  time?: number | undefined
  err: boolean
  added: number
  removed: number
  /** What was searched for, when a search named both a path and a pattern. */
  detail?: string | undefined
  /** Meta-attributed search op only: matched lines the result reported for this file. */
  hits?: number | undefined
  /** Read ops only: the exact 1-based window the result meta reported, else the `limit`-argument estimate (`est: true`). */
  read?: { start: number; count: number } | { count: number; est: true } | undefined
  /** Nested Code-Mode op only: the run_code result node the op ran under (the locate target). */
  parent?: number | undefined
  /** Nested Code-Mode op only: the run_code program's model-authored description. */
  program?: string | undefined
  /** The searched-pattern marker: `path` is a pattern, not a file — display must not relativize it. */
  pattern?: true | undefined
  /** Client-joined archive stamp (see the type note); absent on the wire. */
  gone?: number | undefined
}

/**
  * The `contextTimeline` projection's whole value — the same snapshot the Client has always rendered. `ok` is always `true` here (a
  * delivered projection is by definition available); kept for wire compatibility with the snapshot shape.
 */
export type ContextTimeline = Snapshot

/**
 * The official token-meter `contextPressure` projection (registered by
 * `@deepseek-ai/dsh-token-meter` on the same `SessionProjectionMap`): the
 * provider-anchored occupancy of the NEXT request. The Client reads this key
 * directly instead of the Host mirroring it inside `contextTimeline`
 * (token-meter owns estimation and replay — the docs' stated division of
 * labor). Fields are independent last-wins records; absent until a provider
 * reports usage. Absent key/value = the registry (or the meter) is not
 * composed — the Client falls back to its derived anchor.
 */
export interface ContextPressure {
  /** Provider-reported prompt size of the most recent request (input + cache). */
  pressureTokens?: number | undefined
  /** pressureTokens + heuristic surface movement since the sample (clamped ≥ 0). */
  projectedTokens?: number | undefined
  /** Newest recorded route capacity (last-wins). */
  contextWindow?: number | undefined
}

/**
 * The official token-meter `contextBreakdown` projection: the heuristic
 * composition rows the chat ring's click-open panel shows (system prompt,
 * tool schemas, conversation). The Client reads this key directly so the
 * composition card's proportions AND counts stay identical to the panel's
 * by construction; the message bucket is subdivided into the plugin's four
 * surface categories by the fold's per-category ratios. Absent key/value =
 * an older harness without the meter's projection units — callers fall back
 * to the fold's own sums (identical estimator, minus the image correction).
 */
export interface ContextBreakdown {
  systemTokens: number
  toolsTokens: number
  messageTokens: number
}

/**
 * The official token-meter `tokenUsage` projection (registered by
 * `@deepseek-ai/dsh-token-meter` on the same `SessionProjectionMap`): durable
 * cumulative provider-reported usage across the COMPLETE session log. The four
 * buckets are disjoint (reasoning tokens are already inside `outputTokens`).
 * The Client reads this key directly to compute the cache-hit share — the
 * exact same data the chat stats line below the input box shows, same formula
 * — instead of the Host mirroring it inside `contextTimeline`. Absent until a
 * provider reports usage.
 */
export interface TokenUsage {
  /** Billed prompt tokens that missed the provider cache. */
  uncachedInputTokens: number
  /** Billed output tokens (reasoning included). */
  outputTokens: number
  /** Billed prompt tokens served from the provider cache. */
  cacheReadTokens: number
  /** Billed prompt tokens written into the provider cache. */
  cacheWriteTokens: number
}

/**
 * Cumulative billed-token totals for one pricing bucket of the session-cost
 * estimate (host-folded, never trimmed — running totals over the COMPLETE
 * session log, immune to the request/event retention bounds).
 */
export interface CostBucketTotals {
  uncached: number
  cacheRead: number
  cacheWrite: number
  output: number
}

/**
 * One completed tool name's whole-session call tally behind the timing
 * card's top-tools ranking (running totals, never trimmed).
 */
export interface ToolTimingTotals {
  calls: number
  ms: number
}

/**
 * Whole-session timing totals, host-folded from the durable `step/start` /
 * `step/end` / `tool/call` / `tool/result` lifecycle plus the model call's
 * first token (running totals over the COMPLETE session log — the same
 * never-trimmed framing as `cost`). The first token comes from a V0
 * `assistant/chunk` delta or from the call's own embedded stream
 * (`assistant/message.data.stream` / `assistant/attempt.data.stream`, the
 * V2+ settlement) — whichever the log carries, matching the harness's own
 * session-stats fold. Durations are wall-clock milliseconds: `wallMs` sums
 * whole steps, `ttftMs` the step-start → first-token slice (the model wait)
 * and `genMs` the first-token → assistant-message slice (the generation) —
 * both only over calls whose stream carried a token delta, `toolsMs` the sum
 * of per-call tool durations (parallel calls each count, so it can overlap).
 * Absent until the first step lifecycle completes in the log.
 *
 * The generation window itself splits by WHAT was being decoded, off the
 * stream's `block-start` framing (`blockType`): `reasoningMs` (the model's
 * thinking), `textMs` (the answer text), and `toolArgMs` (the tool-call
 * arguments). Each marker owns the interval up to the next one (the last one
 * up to the assistant message), so the three tile the marker span and together
 * account for essentially all of `genMs` — the span opens at the first marker,
 * which can sit marginally before the first token, so it is not an exact
 * partition. They are ADDITIVE-OPTIONAL: cached projection rows written before
 * the split carry `genMs` without them, so the card falls back to the
 * un-split shape instead of the cache row being discarded (the
 * stateVersion-15 rationale in host/timeline.ts).
 */
export interface TimingTotals {
  /** Summed wall time of completed steps (the session's active time). */
  wallMs: number
  /** Summed step-start → first-token time (the model wait, TTFT). */
  ttftMs: number
  /** Summed first-token → assistant-message time (the generation). */
  genMs: number
  /** Reasoning-decode slice of `genMs` (the model's thinking). */
  reasoningMs?: number | undefined
  /** Answer-text decode slice of `genMs`. */
  textMs?: number | undefined
  /** Tool-call-argument decode slice of `genMs`. */
  toolArgMs?: number | undefined
  /** Completed model calls (assistant messages folded). */
  calls: number
  /** Summed per-call durations of completed tool calls. */
  toolsMs: number
  /** Completed tool calls (call/result pairs folded). */
  toolCalls: number
  /** Per-tool-name tallies behind the timing card's ranking (bounded). */
  tools: Record<string, ToolTimingTotals>
}

/**
 * One billed model's cumulative totals split by pricing period. Providers
 * without period-based pricing book everything under `peak` (the list-price
 * period); DeepSeek splits at fold time — peak windows bill at list price,
 * off-peak (all other hours) at half.
 */
export interface CostModelUsage {
  peak?: CostBucketTotals | undefined
  off?: CostBucketTotals | undefined
}

/**
 * The session-cost estimate's raw material: cumulative provider-reported
 * billed-token totals, keyed by the request envelope's DSH provider id (''
 * when a log carries none) and then by its model id — the exact (provider,
 * model) faces the Client's model-price book resolves (the models.dev
 * registry, client/modelPrices.ts). Running totals per key; absent until a
 * request with a known model reports usage.
 */
export interface SessionCostUsage {
  [provider: string]: { [model: string]: CostModelUsage }
}

/** One model-visible message on the surface, with its heuristic token price. */
export interface SurfaceNode {
  seq: number
  time?: number | undefined
  cat: Category
  tokens: number
  /** Image blocks inside this node's message (absent when zero). */
  imgs?: number | undefined
  /**
   * Removal marker, present only on `archive` entries: the seq of the
   * replacement surface event that shadowed this node (compaction/prune).
   * The node is part of the assembled context of every request with
   * seq > this node.seq and seq < gone.
   */
  gone?: number | undefined
  form?: string | undefined
  /**
   * The producer identity the matching inject event names (host pricing.ts
   * `injectionSourceName`: the plugin id, the reconciled instruction files,
   * or the durable kind). Stamped on injection nodes alongside the event, so
   * the browser rows label them the way the events card does; absent when the
   * source carries no readable identity or the node predates the stamp.
   */
  name?: string | undefined
  text?: string | undefined
  tool?: string | undefined
  err?: boolean | undefined
  skill?: string | undefined
  calls?: string[] | undefined
}

/** One answered model call (a step); consecutive records of one turn form it. */
export interface RequestRecord {
  turn?: number | undefined
  step?: number | undefined
  time: number
  seq: number
  system: number
  tools: number
  user: number
  inject: number
  assistant: number
  tool: number
  total: number
  prompt?: number | undefined
  /**
   * Skill-machinery tokens of this request (the `skill` composition
   * category — catalog digests, invocation instructions, `skill`-tool
   * loads). Always written by the current fold; absent on rows folded
   * before the category existed (read as 0).
   */
  skill?: number | undefined
  /**
   * Billed cache-read (served) prompt tokens of this request — the
   * hit-rate numerator against `prompt` (input + cacheRead + cacheWrite).
   * Absent on older hosts / usage-less requests; zero is a real value.
   */
  cacheRead?: number | undefined
  output?: number | undefined
  /**
   * Turn-mode aggregate marker, set by the Client's aggregateByTurn (one bar
   * per turn shows its LAST step's record). The Host never sets it.
   */
  stepCount?: number | undefined
  /**
   * Delta-mode signed net change, set by the Client's deltaOf (only present
   * on the delta-transformed records the TrendChart plots). The Host never
   * sets it.
   */
  net?: number | undefined
}

/** A notable context event (compaction, prune, injection, model switch). */
export interface ContextEventRecord {
  seq: number
  time: number
  kind: 'compaction' | 'prune' | 'inject' | 'model' | 'mode'
  form?: string | undefined
  tokens?: number | undefined
  count?: number | undefined
  sub?: string | undefined
  name?: string | undefined
  /** One-line producer account (notice-form summary), shown after the name. */
  detail?: string | undefined
  from?: string | undefined
  to?: string | undefined
  /** Turn/step of the request logged right BEFORE the event (host-stamped). */
  fromTurn?: number | undefined
  fromStep?: number | undefined
  /** Turn/step of the request this event contributed to (host-stamped). */
  turn?: number | undefined
  step?: number | undefined
}

/**
 * Sentinel `HeaderTool.plugin` value marking a tool whose provider could not
 * be attributed: it was already registered in the harness tool service when
 * this plugin's runtime attribution hook installed (boot-time third-party
 * tools — e.g. local-link plugins that apply before dsh-context). The client
 * renders a localized "unknown plugin" tag with an explanatory tooltip. No
 * real plugin name can collide (it is not a valid package identifier).
 */
export const UNKNOWN_TOOL_SOURCE = '<unknown-plugin>'

/** One tool of a request-header epoch, with its display price. */
export interface HeaderTool {
  name: string
  tokens: number
  /**
   * The registering plugin's label, when attribution is known: the host's
   * best-effort attribution (`mcp:<server>` for MCP tools, or the pinned
   * first-party package map), or a `plugin` field carried by the raw header
   * entry — no supported-baseline harness path writes one, but the read
   * stays defensive for foreign/newer producers. `UNKNOWN_TOOL_SOURCE` marks
   * a tool whose provider predates the attribution hook; absent means
   * nothing is known and the browser shows no tag.
   */
  plugin?: string | undefined
}

/**
 * One request-header epoch's METADATA: the epoch boundaries and token prices
 * in force from this event's seq until the next epoch. The epoch CONTENT
 * (full system prompt text, tool descriptions/schemas) is not projected —
 * every session.list row, control baseline, push frame, and projection-cache
 * checkpoint would otherwise carry it per session × epoch. The client
 * fetches one epoch's content on demand (a seq-anchored history read off
 * `seq`) as a {@link HeaderEpochContent}.
 */
export interface HeaderRecord {
  seq: number
  time: number
  /** The epoch's estimated system-prompt tokens; absent when it logged no system prompt. */
  systemTokens?: number | undefined
  tools: HeaderTool[]
}

/** The `contextHeaders` projection value: the bounded epoch list (newest last). */
export interface ContextHeaders {
  headers: HeaderRecord[]
}

/**
 * The fetched CONTENT of one request-header epoch — the full system prompt
 * text and per-tool descriptions/schemas, mapped client-side off the raw
 * durable event (see historyPage.ts). Tool identity joins the epoch metadata
 * by `name`; absent description/schema means the raw entry carried none.
 */
export interface HeaderEpochContent {
  system?: string | undefined
  tools: Array<{
    name: string
    description?: string | undefined
    /** The raw JSON schema object the model received (plain JSON). */
    schema?: unknown | undefined
  }>
}

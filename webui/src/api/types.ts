/**
 * Wire types for the gateway API (openapi.yaml is the source of truth).
 *
 * Shapes mirror the Java projections:
 * - mirror frames: MirrorHub payloads (every frame's data carries session_id)
 * - snapshot: GatewayMessagesSnapshotHandler's two-level shape
 * - catalog: GatewayServer.sessionsBody's projects[] tree
 */

// ---------------------------------------------------------------------------
// GET /api/sessions — catalog
// ---------------------------------------------------------------------------

export interface CatalogSession {
  readonly id: string
  readonly summary: string | null
  readonly message_count: number
  readonly modified_at: string
  readonly git_branch: string | null
  readonly cwd: string | null
  readonly custom_title: string | null
  readonly first_prompt: string | null
  /** The TUI's current session — NOT a statement that a turn is running. */
  readonly active: boolean
  /** Routable right now via metadata.session_id without an open round-trip. */
  readonly headless_open: boolean
  /**
   * A turn is in flight for this session right now. Omitted when not running
   * (the gateway never stamps `false`). Only sessions the gateway follows —
   * the active session and open headless sessions — can report it, which is
   * the sidebar's population; an on-disk-only history row is always absent.
   */
  readonly running?: boolean
  /**
   * The session is hosted by another running Claude Code process rather than
   * this gateway's own TUI. Omitted for local sessions.
   */
  readonly remote?: boolean
  /** True when this session is currently owned by a live discovered TUI process. */
  readonly tui_attached?: boolean
  /** PID of the online TUI process owning this session, when {@code tui_attached} is true. */
  readonly tui_pid?: number
  /**
   * A live process is hosting this session right now. The permission mode and
   * the model selection live on that process, not in the transcript, so a
   * session without a host returns null for both and cannot be driven.
   */
  readonly interactive: boolean
}

export interface CatalogProject {
  readonly project_path: string
  readonly project_name: string
  readonly session_count: number
  readonly sessions: readonly CatalogSession[]
}

export interface SessionCatalog {
  readonly projects: readonly CatalogProject[]
}

/**
 * The registry's flat fallback shape (GatewayServer.sessionsBody, no catalog
 * port wired): one un-nested session list, keyed by fields SessionHostInfo
 * exposes rather than the catalog's richer projection.
 */
export interface FlatSession {
  readonly id: string
  readonly work_dir: string | null
  readonly summary: string | null
  readonly message_count: number
  readonly modified_at: string | null
  readonly git_branch: string | null
  readonly active: boolean | undefined
  readonly headless_open: boolean | undefined
  /** See {@link CatalogSession.running}; omitted when not running. */
  readonly running?: boolean | undefined
  /** Hosted by another running Claude Code process; the gateway omits the field otherwise. */
  readonly remote?: boolean | undefined
  /** See {@link CatalogSession.interactive}. */
  readonly interactive: boolean | undefined
}

export interface FlatSessionCatalog {
  readonly sessions: readonly FlatSession[]
}

// ---------------------------------------------------------------------------
// GET /api/sessions/{id}/messages — snapshot
// ---------------------------------------------------------------------------

export interface ToolCallTool {
  readonly tool_use_id: string
  readonly name: string
  readonly args: Readonly<Record<string, unknown>> | null
  readonly status: 'pending' | 'executed' | 'failed'
  readonly ready: boolean
  readonly result: ToolResult | null
}

export interface ToolResult {
  readonly type: string
  readonly data: string | null
  readonly errorMessage?: string
  readonly errorCode?: string
  readonly transcript_path?: string
  readonly locations?: readonly string[]
}

// The changed-files wire types (`WorkspaceDiffHunk`, `WorkspaceFileDiff`,
// `ChangesSummary`, `ChangedFile`) live in the vendored contract
// `@vendor/ui-deliverables/changes`, which is where the stores and the viewer
// that consume them read them from. They were duplicated here while the client
// fetched these endpoints itself; the vendored `HostReadStore` does the reading
// now, so a second declaration would only be one more thing to keep in step
// with the server — the drift that blanked the viewer once already.

export type SnapshotContent =
  | { readonly type: 'text'; readonly text: string }
  | { readonly type: 'thinking'; readonly thinking: string }
  | { readonly type: 'tool_call'; readonly tool: ToolCallTool }

/** One assistant step's provider-reported token buckets (snapshot turn_usage / frame turn_usage). */
export interface TurnUsage {
  /** The turn's last-reported model id (frame path); the usage dialog's model-route row. */
  readonly model?: string
  readonly uncached_input_tokens: number
  readonly output_tokens: number
  readonly cache_write_tokens: number
  readonly cache_read_tokens: number
  readonly total_tokens: number
}

export interface SnapshotAssistantMessage {
  readonly id: string
  readonly role: 'assistant'
  readonly complete: boolean
  readonly content: readonly SnapshotContent[]
  /** Durable transcript timestamp, epoch ms — the turn-tail clock label. */
  readonly time?: number
  /** 1-based turn number; absent for rows before the first human prompt. */
  readonly turn?: number
  readonly turn_usage?: TurnUsage
}

export interface SnapshotUserMessage {
  readonly id: string
  readonly role: 'user'
  readonly complete: boolean
  /** Durable transcript timestamp, epoch ms — the user row's leading clock. */
  readonly time?: number
  /** Absent on an image-only row (no accompanying text). */
  readonly text?: string
  readonly images?: readonly MessageImageAttachment[]
}

/** One inline image attachment — the same shape the client submits images in. */
export interface MessageImageAttachment {
  readonly media_type: string
  readonly data: string
}

export type SnapshotMessage = SnapshotAssistantMessage | SnapshotUserMessage

export interface MessagesSnapshot {
  readonly session_id: string
  readonly messages: readonly SnapshotMessage[]
  /** The running turn's start clock, epoch ms; absent while the session is idle. */
  readonly running_since?: number
  /** The block the model is streaming right now; absent between blocks. */
  readonly streaming?: {
    readonly kind: 'text' | 'thinking'
    readonly content: string
  }
}

/**
 * GET /api/sessions/{id}/agents/{agentId}/transcript — one sub-agent's own
 * conversation, restored from its sidechain transcript.
 *
 * The rows are the same `SnapshotMessage` union the parent snapshot serves
 * (the gateway projects both through one row builder), so a client reduces
 * them with its ordinary snapshot reducer. There is no `running_since` or
 * `streaming`: a restored sidechain is a settled record, never a live turn.
 * Rows carry no `turn`/`turn_usage` either — a sub-agent's conversation is
 * not a numbered turn of its parent.
 */
export interface AgentTranscriptSnapshot {
  readonly session_id: string
  /** The sub-agent id this transcript belongs to (the sidechain file's key). */
  readonly agent_id: string
  readonly messages: readonly SnapshotMessage[]
  /** True when the sub-agent currently has an active in-process run. */
  readonly running?: boolean
}

export interface AgentPromptResponse {
  readonly ok: boolean
  readonly status: 'queued' | 'resumed'
  readonly agent_id: string
}

export interface AgentStopResponse {
  readonly stopped: boolean
  readonly agent_id: string
}

// ---------------------------------------------------------------------------
// GET /api/shells — background shell tasks
// ---------------------------------------------------------------------------

export type ShellStatus = 'running' | 'completed' | 'failed' | 'killed' | 'unknown'

export interface ShellEntry {
  readonly id: string
  readonly command?: string
  readonly description?: string
  readonly status: ShellStatus
  readonly start_time_ms: number
  readonly end_time_ms?: number
  readonly exit_code?: number
}

export interface ShellsListing {
  readonly shells: readonly ShellEntry[]
}

export interface ShellOutputDetail extends ShellEntry {
  readonly output: string
  readonly truncated: boolean
}

export interface ShellStopResponse {
  readonly stopped: boolean
  readonly id: string
}

// ---------------------------------------------------------------------------
// GET /api/events — mirror frames (SSE)
// ---------------------------------------------------------------------------

export type MirrorFrame =
  | { readonly event: 'turn.started'; readonly id: number; readonly data: {
      readonly session_id: string
      readonly display_text: string
      readonly permission_mode: string
      readonly origin: string
      /** The user row's leading clock label, epoch ms. */
      readonly time?: number
      readonly images?: readonly MessageImageAttachment[]
    } }
  | { readonly event: 'output.text'; readonly id: number; readonly data: {
      readonly session_id: string
      readonly content: string
      readonly synthetic?: boolean
      /** The owning assistant step's id (absent on synthetic System text). */
      readonly message_id?: string
    } }
  | { readonly event: 'output.thinking'; readonly id: number; readonly data: {
      readonly session_id: string
      readonly content: string
      readonly message_id?: string
    } }
  /**
   * Transient live stream slices: never journaled, no `id:` line on the wire
   * (the subscription forwards them with id 0). `offset` is the draft length
   * before this slice, in UTF-16 code units.
   */
  | { readonly event: 'output.text.delta' | 'output.thinking.delta'; readonly id: number; readonly data: {
      readonly session_id: string
      readonly content: string
      readonly offset: number
    } }
  | { readonly event: 'tool.started'; readonly id: number; readonly data: {
      readonly session_id: string
      readonly name: string
      readonly tool_use_id?: string
      readonly input?: Readonly<Record<string, unknown>> | string
      readonly input_raw?: Readonly<Record<string, unknown>>
      readonly message_id?: string
    } }
  | { readonly event: 'tool.progress'; readonly id: number; readonly data: {
      readonly session_id: string
      readonly tool_use_id: string
      readonly kind: string
      readonly agent_id?: string
      readonly content?: string
      readonly message?: string
    } }
  | { readonly event: 'tool.completed'; readonly id: number; readonly data: {
      readonly session_id: string
      readonly status: 'completed' | 'failed'
      readonly tool_use_id?: string
      readonly name?: string
      readonly result: ToolResult | string
    } }
  | { readonly event: 'permission.asked'; readonly id: number; readonly data: PermissionAsk }
  | { readonly event: 'permission.resolved'; readonly id: number; readonly data: {
      readonly session_id: string
      readonly request_id: string
      readonly origin: string
    } }
  | { readonly event: 'sudo.asked'; readonly id: number; readonly data: SudoAsk }
  | { readonly event: 'sudo.resolved'; readonly id: number; readonly data: {
      readonly session_id: string
      readonly request_id: string
      readonly resolution: 'submitted' | 'cancelled'
      readonly origin: 'tui' | 'web'
    } }
  | { readonly event: 'permission.mode'; readonly id: number; readonly data: {
      readonly session_id: string
      /** Mirrors GET /api/session/permission-mode; null when the session exposes no gate. */
      readonly permission_mode: SessionPermissionMode | null
    } }
  | { readonly event: 'task_board'; readonly id: number; readonly data: SessionTaskBoard & {
      readonly session_id: string
      /** `snapshot` from GET /api/session/tasks, `event` for a pushed frame. */
      readonly source: 'snapshot' | 'event'
    } }
  | { readonly event: 'session.activated'; readonly id: number; readonly data: {
      readonly session_id: string
      readonly id2?: string
      readonly summary: string
      readonly origin: string
    } }
  | { readonly event: 'session.error'; readonly id: number; readonly data: {
      readonly session_id: string
      readonly message: string
    } }
  | { readonly event: 'session.idle' | 'turn.cancelled'; readonly id: number; readonly data: {
      readonly session_id: string
    } }
  /** The set of live sessions changed (a TUI started or exited); re-read the catalog. */
  | { readonly event: 'sessions.changed'; readonly id: number; readonly data: {
      readonly session_id: string
    } }
  /** CC Connector status changed (attached/detached/setup/sidecar exit); re-read /api/cc-connect. */
  | { readonly event: 'cc_connect.changed'; readonly id: number; readonly data: {
      readonly session_id: string
    } }
  | { readonly event: 'turn.completed'; readonly id: number; readonly data: {
      readonly session_id: string
      readonly done: boolean
      readonly elapsed_ms: number
      readonly user_cancel: boolean
      /** Time to first stream output, ms; absent when the turn produced none. */
      readonly ttft_ms?: number
      /** The turn-tail's trailing clock label, epoch ms. */
      readonly time?: number
      /** 1-based turn number; present when the durable fold served the delta. */
      readonly turn?: number
      readonly turn_usage?: TurnUsage
    } }

// ---------------------------------------------------------------------------
// Permission ask (permission.asked frame / approval card)
// ---------------------------------------------------------------------------

export interface PermissionQuestionOption {
  readonly label: string
  readonly description?: string
}

export interface PermissionQuestion {
  readonly question: string
  readonly header?: string
  readonly multi_select?: boolean
  readonly options: readonly PermissionQuestionOption[]
}

/**
 * A sudo invocation blocked on a password.
 *
 * Never carries the password: the prompt is shown from these fields, and what
 * the user types goes back on POST /api/sudo/respond as the request body.
 */
export interface SudoAsk {
  readonly session_id: string
  readonly request_id: string
  /** The verified system sudo that will receive the password. */
  readonly executable?: string
  /** The command line awaiting authentication. */
  readonly command?: string
}

export interface PermissionAsk {
  readonly session_id: string
  readonly request_id: string
  readonly tool: string
  readonly tool_use_id?: string
  readonly input?: Readonly<Record<string, unknown>>
  readonly decision_reason_type?: string
  readonly decision_reason_detail?: string
  readonly destructive_warning?: string
  readonly blocked_path?: string
  readonly custom_message?: string
  readonly tool_description?: string
  readonly questions?: readonly PermissionQuestion[]
}

// ---------------------------------------------------------------------------
// POST /api/sessions/open|close, POST /v1/messages
// ---------------------------------------------------------------------------

/**
 * One content block of a submitted user message. Text and inline-base64
 * images and documents follow the Anthropic Messages block shapes the
 * gateway's MessagesHandler extracts; the prompt text stays a text block so
 * the wire carries one canonical shape regardless of attachment mix.
 */
export type UserContentBlock =
  | { readonly type: 'text'; readonly text: string }
  | { readonly type: 'image'; readonly source: Base64Source }
  | { readonly type: 'document'; readonly title?: string; readonly source: Base64Source }

/** An Anthropic base64 source block: inline data with a media type. */
export interface Base64Source {
  readonly type: 'base64'
  readonly media_type: string
  readonly data: string
}

export interface OpenSessionResponse {
  readonly session_id: string
  readonly project_path: string
  readonly resumed: boolean
  readonly headless: boolean
  readonly owner?: 'tui' | 'headless'
  readonly tui_attached?: boolean
  readonly tui_pid?: number
}

export interface CloseSessionResponse {
  readonly session_id: string
  readonly closed: boolean
}

// ---------------------------------------------------------------------------
// POST /api/sessions/{id}/rename|fork|archive, DELETE /api/sessions/{id}
// ---------------------------------------------------------------------------

export interface RenameSessionResponse {
  readonly session_id: string
  readonly custom_title: string
}

export interface ForkSessionResponse {
  readonly session_id: string
}

export type RewindMode = 'both' | 'conversation_only' | 'code_only'

export interface RewindFileDiff {
  readonly path: string
  readonly insertions: number
  readonly deletions: number
  readonly will_delete: boolean
  readonly unified_diff: string
}

export interface RewindPreviewResponse {
  readonly can_rewind_conversation: boolean
  readonly can_rewind_code: boolean
  readonly code_unavailable_reason: string | null
  readonly turns_to_drop: number
  readonly messages_to_drop: number
  readonly insertions: number
  readonly deletions: number
  readonly files: readonly RewindFileDiff[]
}

export interface RewindExecuteResponse {
  readonly rewound_conversation: boolean
  readonly rewound_code: boolean
  readonly retained_message_count: number
  readonly restored_files: readonly string[]
}

export interface ArchiveSessionResponse {
  readonly session_id: string
  readonly archived: boolean
}

export interface DeleteSessionResponse {
  readonly session_id: string
  readonly deleted: boolean
}

export interface RespondRequest {
  readonly request_id: string
  readonly session_id: string
  readonly allowed: boolean
  readonly updated_input?: Readonly<Record<string, unknown>>
  readonly skip?: boolean
}

/**
 * POST /api/sudo/respond: answer a sudo password ask.
 *
 * `password` is present only for a submit and is never echoed back by any
 * endpoint or mirror frame.
 */
export interface SudoRespondRequest {
  readonly request_id: string
  readonly session_id: string
  readonly behavior: 'submit' | 'cancel'
  readonly password?: string
}

export class ApiError extends Error {
  readonly status: number
  readonly code: string

  constructor(status: number, code: string, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

// ---------------------------------------------------------------------------
// GET/POST /api/settings — settings snapshot + mutation ops
// ---------------------------------------------------------------------------

/** One contributing tier row of the merged settings snapshot. */
export interface SettingsSource {
  readonly source: string
  readonly settings: Readonly<Record<string, unknown>>
}

/**
 * The effective settings snapshot (SettingsSnapshots.withSources shape):
 * the merged tree plus one row per contributing tier. POST mutations answer
 * with the same refreshed shape, so one type serves both.
 */
export interface SettingsSnapshot {
  readonly effective: Readonly<Record<string, unknown>>
  readonly sources: readonly SettingsSource[]
}

/** The editable settings tiers the write ops address. */
export type SettingsTier = 'user' | 'project' | 'local'

/** The editable permission behavior arrays. */
export type PermissionBehaviorKind = 'allow' | 'deny' | 'ask'

// ---------------------------------------------------------------------------
// GET/POST /api/schedule, DELETE /api/schedule/{id} — scheduled tasks
// ---------------------------------------------------------------------------

export type ScheduleNotificationMode = 'off' | 'on_error' | 'always'

export interface ScheduleTaskOptions {
  readonly model?: string | null
  readonly effort?: string | null
  readonly working_directory?: string | null
  readonly new_session_per_run?: boolean
  readonly notification_mode?: ScheduleNotificationMode
  readonly archive_on_success?: boolean
  readonly enabled?: boolean
}

export interface ScheduleTask {
  readonly id: string
  readonly cron: string
  readonly prompt: string
  readonly recurring: boolean
  readonly durable: boolean
  readonly created_at: number
  readonly last_fired_at?: number
  readonly kind?: string
  readonly agent_id?: string
  readonly created_by_session_id?: string
  /** Per-task model override; absent keeps the session model at fire time. */
  readonly model?: string
  /** Per-task reasoning effort override (low | medium | high | max). */
  readonly effort?: string
  /** Target project working directory for execution. */
  readonly working_directory?: string
  /** True (default) opens a fresh session per run; false reuses one thread. */
  readonly new_session_per_run?: boolean
  /** Native macOS notification mode: off | on_error | always. */
  readonly notification_mode?: ScheduleNotificationMode
  /** True auto-closes/archives clean successful runs. */
  readonly archive_on_success?: boolean
  /** False pauses automatic scheduling without deleting the task (default true). */
  readonly enabled?: boolean
}

export interface ScheduleListing {
  readonly tasks: readonly ScheduleTask[]
}

// ---------------------------------------------------------------------------
// GET/POST /api/models, DELETE /api/models/{name} — custom model catalogue
// ---------------------------------------------------------------------------

export type ModelProtocol = 'anthropic' | 'chat' | 'responses' | 'gemini'

export interface CustomModelEntry {
  readonly model_name: string
  readonly protocol: ModelProtocol
  readonly base_url: string
  readonly has_api_key: boolean
  readonly headers: Readonly<Record<string, string>>
  readonly context_window?: number
  /** Null = unconfigured (assume multimodal); false marks a text-only endpoint. */
  readonly multimodal?: boolean | null
  readonly source_plugin?: string
  readonly read_only?: boolean
}

export interface ModelsListing {
  readonly models: readonly CustomModelEntry[]
}

export interface DiscoveredModelEntry {
  readonly id: string
  readonly display_name?: string
  readonly owned_by?: string
  readonly context_window?: number
}

export interface DiscoverModelsResponse {
  readonly endpoint: string
  readonly models: readonly DiscoveredModelEntry[]
}

export interface BatchSaveModelsResponse extends ModelsListing {
  readonly saved: number
}

// ---------------------------------------------------------------------------
// GET /api/commands — composer "+" menu catalogue (slash commands + skills)
// ---------------------------------------------------------------------------

export type CommandKind = 'command' | 'skill'

export interface ComposerCommandEntry {
  readonly name: string
  readonly description?: string
  readonly argument_hint?: string
  readonly kind: CommandKind
}

export interface CommandsListing {
  readonly commands: readonly ComposerCommandEntry[]
}

// ---------------------------------------------------------------------------
// GET /api/file-suggestions — composer @ completion candidates
// ---------------------------------------------------------------------------

/**
 * One `@` menu candidate. Rows carry paths only: selecting one never reads
 * or attaches file contents (the backend resolves the inserted `@path` token
 * at submission time).
 */
export interface FileCandidate {
  /** Workspace-relative path, or absolute for a `~/` / `/` rooted query. */
  readonly path: string
  /** True when the row names a directory, so the client can offer a drill. */
  readonly directory: boolean
}

export interface FileSuggestionsListing {
  readonly files: readonly FileCandidate[]
}

// ---------------------------------------------------------------------------
// GET /api/spinner-verbs — the TUI spinner's verb vocabulary
// ---------------------------------------------------------------------------

export interface SpinnerVerbsListing {
  /** Present-participle verbs, already spliced with the `spinnerVerbs` setting. */
  readonly active: readonly string[]
  /** Tool name to its fixed verb; unlisted tools take a random active verb. */
  readonly tools: Readonly<Record<string, string>>
}

// ---------------------------------------------------------------------------
// GET/POST /api/session/context — active session model seat + context meter
// ---------------------------------------------------------------------------

export interface SessionModelChoice {
  readonly name: string
  readonly label: string
  readonly description?: string
  readonly default: boolean
}

export interface SessionEffortState {
  readonly current: string
  readonly effective: string
  readonly choices: readonly string[]
}

export interface SessionModelSelection {
  readonly current: string
  readonly models: readonly SessionModelChoice[]
  readonly effort?: SessionEffortState
}

export interface SessionContextBreakdown {
  readonly system_tokens: number
  readonly tools_tokens: number
  readonly message_tokens: number
}

export interface SessionContextUsage {
  readonly model: string
  readonly context_window: number
  readonly used_tokens?: number
  readonly used_percentage?: number
  readonly breakdown?: SessionContextBreakdown
}

/**
 * The gateway's durable whole-session metrics fold, raw integers only —
 * display formulas live client-side (vendor/dsh-stats-pills). Null when the
 * session's coverage is incomplete or unavailable; a complete all-zero fold
 * is a legal (fresh) session.
 */
export interface SessionMetrics {
  readonly turns: number
  readonly steps: number
  readonly llm_ms: number
  readonly tool_ms: number
  readonly ttft_ms: number
  readonly ttft_steps: number
  readonly decode_ms: number
  readonly decode_tokens: number
  readonly uncached_input_tokens: number
  readonly output_tokens: number
  readonly cache_write_tokens: number
  readonly cache_read_tokens: number
}

export interface SessionContext {
  readonly selection: SessionModelSelection | null
  readonly context: SessionContextUsage | null
  readonly metrics: SessionMetrics | null
}

export interface SessionContextSelectResponse {
  readonly selection: SessionModelSelection
  readonly context: SessionContextUsage | null
  readonly metrics: SessionMetrics | null
}

// ---------------------------------------------------------------------------
// GET/POST /api/session/permission-mode — active session permission-mode chip
// ---------------------------------------------------------------------------

export type PermissionModeValue = 'default' | 'plan' | 'acceptEdits' | 'bypassPermissions' | 'dontAsk'

export interface PermissionModeChoice {
  readonly value: PermissionModeValue
  readonly title: string
  readonly short_title: string
  readonly symbol: string
  readonly color_key: string
  readonly available: boolean
}

export interface SessionPermissionMode {
  readonly current: PermissionModeValue
  readonly modes: readonly PermissionModeChoice[]
  readonly bypass_permissions_available: boolean
  readonly bypass_permissions_disabled_by_policy: boolean
}

export interface SessionPermissionModeResponse {
  readonly permission_mode: SessionPermissionMode | null
}

// ---------------------------------------------------------------------------
// POST /api/session/cancel — stop the addressed session's running turn
// ---------------------------------------------------------------------------

/**
 * The gateway accepted the stop. `cancelling` is the request's own
 * acknowledgement, not the turn's outcome: cancellation unwinds the turn
 * asynchronously and the session's event stream reports how it ended.
 * `session_id` is absent when the active TUI session was addressed.
 */
export interface SessionCancelResponse {
  readonly session_id?: string
  readonly cancelling: boolean
}

// ---------------------------------------------------------------------------
// Task board — the to-do dock (GET /api/session/tasks + `task_board` frames)
// ---------------------------------------------------------------------------

/** Wire spelling of a board entry's state; matches the runtime port's own enum. */
export type TaskStatus = 'pending' | 'in_progress' | 'completed'

export interface SessionTaskBoardItem {
  readonly id: string
  readonly subject: string
  readonly description: string
  readonly active_form: string
  readonly owner: string
  readonly status: TaskStatus
  /** Ids of tasks this one must precede. */
  readonly blocks: readonly string[]
  /** Ids of tasks that must precede this one. */
  readonly blocked_by: readonly string[]
}

/**
 * One board state. The pushed frame and the cold-load endpoint carry this
 * identical shape — consumers read `tasks` and must not branch on which
 * transport delivered it.
 */
export interface SessionTaskBoard {
  readonly list_id: string
  readonly revision: number
  /** True when the board holds no visible task; consumers render no panel. */
  readonly hidden: boolean
  readonly tasks: readonly SessionTaskBoardItem[]
}

// ---------------------------------------------------------------------------
// LAN Model Gateway — GET/POST /api/lan-gateway, keys, and recent calls
// ---------------------------------------------------------------------------

export type LanEndpointKind = 'lan' | 'mdns' | 'tailscale' | 'public'

export interface LanGatewayEndpoint {
  readonly kind: LanEndpointKind
  readonly host: string
  readonly url: string
}

export interface LanGatewaySharedModel {
  readonly id: string
  readonly protocol: string
  readonly built_in: boolean
  readonly context_window: number | null
}

export interface LanGatewayKey {
  readonly id: string
  readonly name: string
  readonly secret: string
  readonly masked_secret: string
  readonly enabled: boolean
  readonly allowed_models: readonly string[]
  readonly created_at: string
  readonly last_used_at: string | null
  readonly total_requests: number
  readonly input_tokens: number
  readonly output_tokens: number
}

export interface LanGatewayStatus {
  readonly enabled: boolean
  readonly listening_host: string
  readonly port: number
  readonly expose_default_provider: boolean
  readonly tier_stand_in_model: string
  readonly prevent_mac_sleep: boolean
  readonly awake_active: boolean
  readonly allowed_cidrs: readonly string[]
  readonly endpoints: readonly LanGatewayEndpoint[]
  readonly available_models: readonly LanGatewaySharedModel[]
  readonly keys: readonly LanGatewayKey[]
}

export interface LanGatewayCall {
  readonly id: number
  readonly timestamp: string
  readonly key_id: string
  readonly key_name: string
  readonly client_ip: string
  readonly source_protocol: string
  readonly target_protocol: string
  readonly requested_model: string
  readonly resolved_model: string
  readonly streaming: boolean
  readonly status: number
  readonly ttft_ms: number
  readonly duration_ms: number
  readonly input_tokens: number
  readonly output_tokens: number
  readonly error: string | null
}

export interface LanGatewayCallsResponse {
  readonly calls: readonly LanGatewayCall[]
}

// ---------------------------------------------------------------------------
// CC Connector (IM external collaboration hosted by Web daemon)
// ---------------------------------------------------------------------------

export type CcConnectTargetState = 'stopped' | 'starting' | 'connected' | 'error'
export type CcConnectSetupMode = 'create' | 'bind' | 'resume'

export interface CcConnectTarget {
  readonly pid: number
  readonly work_dir: string
  readonly session_id: string
  readonly session_title: string
  readonly attached: boolean
  readonly channel: string
  readonly status: CcConnectTargetState
  readonly started_at: string
  readonly last_error: string
}

export interface CcConnectSnapshot {
  readonly supported: boolean
  readonly configured: boolean
  readonly setup_pending: boolean
  readonly config_path: string
  readonly available_channels: readonly string[]
  readonly setup_in_progress: boolean
  readonly targets: readonly CcConnectTarget[]
}

export interface CcConnectLogsResponse {
  readonly pid: number
  readonly lines: readonly string[]
}

export interface CcConnectSetupResult {
  readonly ok: boolean
  readonly channel: string
  readonly message: string
}

// ---------------------------------------------------------------------------
// Progressive Session Capabilities (/api/session/capabilities)
// ---------------------------------------------------------------------------

export type SessionCapabilityKind = 'tool' | 'skill' | 'mcp_server' | 'mcp_tool'
export type SessionCapabilityScope = 'session' | 'default'
export type ToolDeliveryMode = 'prefix_eager' | 'progressive_deferred' | 'disabled'

export interface BuiltInToolCapability {
  readonly name: string
  readonly description: string
  readonly input_schema: Readonly<Record<string, unknown>>
  readonly tokens: number
  readonly kernel: boolean
  readonly session_enabled: boolean
  readonly default_enabled: boolean
  readonly delivery_mode: ToolDeliveryMode
}

export interface SkillCapability {
  readonly name: string
  readonly source: string
  readonly description: string
  readonly tokens: number
  readonly session_enabled: boolean
  readonly default_enabled: boolean
  readonly protocol_visible: boolean
}

export interface McpToolCapability {
  readonly server_name: string
  readonly name: string
  readonly full_name: string
  readonly description: string
  readonly input_schema: Readonly<Record<string, unknown>>
  readonly tokens: number
  readonly session_enabled: boolean
  readonly default_enabled: boolean
}

export interface McpServerCapability {
  readonly name: string
  readonly scope: string
  readonly transport: string
  readonly status: string
  readonly instructions: string
  readonly tokens: number
  readonly manageable: boolean
  readonly session_enabled: boolean
  readonly default_enabled: boolean
  readonly tools: readonly McpToolCapability[]
}

export interface SessionCapabilities {
  readonly session_id: string
  readonly model: string
  readonly protocol: ModelProtocol
  readonly saved_tokens: number
  readonly tools: readonly BuiltInToolCapability[]
  readonly skills: readonly SkillCapability[]
  readonly mcp_servers: readonly McpServerCapability[]
}

export interface SessionCapabilitiesResponse {
  readonly capabilities: SessionCapabilities | null
}

export interface SessionCapabilityToggleRequest {
  readonly session_id?: string | null
  readonly kind: SessionCapabilityKind
  readonly name: string
  readonly server_name?: string | null
  readonly enabled: boolean
  readonly scope?: SessionCapabilityScope
}

export interface SessionCapabilityToggleResponse {
  readonly changed: boolean
  readonly message: string
  readonly capabilities: SessionCapabilities
}

// ---------------------------------------------------------------------------
// Multi-folder Projects & Per-Project Customizations (/api/projects)
// ---------------------------------------------------------------------------

export interface WebProjectEntry {
  readonly path: string
  readonly name: string
  readonly additional_directories: readonly string[]
  readonly updated_at_ms: number
}

export interface WebProjectsListing {
  readonly projects: readonly WebProjectEntry[]
}

export interface WebProjectMutationResponse {
  readonly project: WebProjectEntry
  readonly projects: readonly WebProjectEntry[]
}

export interface WebProjectDeleteResponse {
  readonly path: string
  readonly deleted_session_ids: readonly string[]
  readonly deleted_count: number
  readonly projects: readonly WebProjectEntry[]
}

export interface ProjectRuleFile {
  readonly path: string
  readonly name: string
  readonly scope: string
  readonly tokens: number
}

export interface ProjectSkillInfo {
  readonly name: string
  readonly description: string
  readonly source: string
  readonly path?: string
}

export interface ProjectMcpServerInfo {
  readonly name: string
  readonly type: string
  readonly scope: string
  readonly disabled: boolean
}

export interface ProjectCustomizations {
  readonly path: string
  readonly folders: {
    readonly primary: string
    readonly additional: readonly string[]
    readonly local_directories: readonly string[]
    readonly project_directories: readonly string[]
    readonly user_directories: readonly string[]
  }
  readonly rules: {
    readonly total_tokens: number
    readonly files: readonly ProjectRuleFile[]
  }
  readonly skills: readonly ProjectSkillInfo[]
  readonly mcp_servers: readonly ProjectMcpServerInfo[]
}

// ---------------------------------------------------------------------------
// Plugins & Marketplaces (/api/plugins/**)
// ---------------------------------------------------------------------------

export type PluginInstallScope = 'user' | 'project' | 'local'

export interface PluginRuntimeSummary {
  readonly command_count: number
  readonly agent_count: number
  readonly skill_count: number
  readonly mcp_count: number
  readonly error_count: number
}

export interface PluginConfigSchemaField {
  readonly type: string
  readonly title: string
  readonly description: string
  readonly required: boolean
  readonly sensitive: boolean
  readonly default?: unknown
}

export interface PluginInstallationEntry {
  readonly scope: string
  readonly project_path: string
  readonly install_path: string
  readonly version: string
}

export interface PluginUiPanel {
  readonly plugin_id: string
  readonly provider_id: string
  readonly title: string
  readonly description: string
  readonly url: string
  readonly status: string
  readonly error?: string
  readonly settings_tab?: boolean
}

export interface InstalledPluginItem {
  readonly plugin_id: string
  readonly name: string
  readonly marketplace: string
  readonly enabled: boolean
  readonly description: string
  readonly version: string
  readonly author: string
  readonly homepage: string
  readonly repository: string
  readonly installations: readonly PluginInstallationEntry[]
  readonly user_config: Readonly<Record<string, PluginConfigSchemaField>>
  readonly options: Readonly<Record<string, unknown>>
  readonly ui_panel?: PluginUiPanel
}

export interface MarketplaceCatalogPlugin {
  readonly name: string
  readonly plugin_id: string
  readonly marketplace: string
  readonly description: string
  readonly version: string
  readonly author: string
  readonly category: string
  readonly homepage: string
  readonly repository: string
  readonly tags: readonly string[]
}

export interface PluginMarketplaceItem {
  readonly name: string
  readonly source: string
  readonly install_location: string
  readonly last_updated: string
  readonly auto_update: boolean
  readonly plugins: readonly MarketplaceCatalogPlugin[]
}

export interface PluginErrorItem {
  readonly source: string
  readonly message: string
  readonly guidance: string
  readonly action: string
  readonly target: string
}

export interface PluginsSnapshot {
  readonly supported: boolean
  readonly summary: PluginRuntimeSummary
  readonly installed: readonly InstalledPluginItem[]
  readonly marketplaces: readonly PluginMarketplaceItem[]
  readonly errors: readonly PluginErrorItem[]
  readonly ui_panels?: readonly PluginUiPanel[]
}







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
  readonly active: boolean
  readonly headless_open: boolean
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
  readonly text: string
}

export type SnapshotMessage = SnapshotAssistantMessage | SnapshotUserMessage

export interface MessagesSnapshot {
  readonly session_id: string
  readonly messages: readonly SnapshotMessage[]
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
    } }
  | { readonly event: 'output.text'; readonly id: number; readonly data: {
      readonly session_id: string
      readonly content: string
      readonly synthetic?: boolean
    } }
  | { readonly event: 'output.thinking'; readonly id: number; readonly data: {
      readonly session_id: string
      readonly content: string
    } }
  | { readonly event: 'tool.started'; readonly id: number; readonly data: {
      readonly session_id: string
      readonly name: string
      readonly tool_use_id: string
      readonly input?: Readonly<Record<string, unknown>>
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
      readonly tool_use_id: string
      readonly result: ToolResult
    } }
  | { readonly event: 'permission.asked'; readonly id: number; readonly data: PermissionAsk }
  | { readonly event: 'permission.resolved'; readonly id: number; readonly data: {
      readonly session_id: string
      readonly request_id: string
      readonly origin: string
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
}

export interface CloseSessionResponse {
  readonly session_id: string
  readonly closed: boolean
}

export interface RespondRequest {
  readonly request_id: string
  readonly session_id: string
  readonly allowed: boolean
  readonly updated_input?: Readonly<Record<string, unknown>>
  readonly skip?: boolean
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
}

export interface ScheduleListing {
  readonly tasks: readonly ScheduleTask[]
}

// ---------------------------------------------------------------------------
// GET/POST /api/models, DELETE /api/models/{name} — custom model catalogue
// ---------------------------------------------------------------------------

export type ModelProtocol = 'anthropic' | 'chat' | 'responses'

export interface CustomModelEntry {
  readonly model_name: string
  readonly protocol: ModelProtocol
  readonly base_url: string
  readonly has_api_key: boolean
  readonly headers: Readonly<Record<string, string>>
  readonly context_window?: number
  /** Null = unconfigured (assume multimodal); false marks a text-only endpoint. */
  readonly multimodal?: boolean | null
}

export interface ModelsListing {
  readonly models: readonly CustomModelEntry[]
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


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
}

export type SnapshotContent =
  | { readonly type: 'text'; readonly text: string }
  | { readonly type: 'thinking'; readonly thinking: string }
  | { readonly type: 'tool_call'; readonly tool: ToolCallTool }

export interface SnapshotAssistantMessage {
  readonly id: string
  readonly role: 'assistant'
  readonly complete: boolean
  readonly content: readonly SnapshotContent[]
}

export interface SnapshotUserMessage {
  readonly id: string
  readonly role: 'user'
  readonly complete: boolean
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

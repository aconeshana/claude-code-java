import { ApiError, type CatalogProject, type CloseSessionResponse, type CommandsListing, type FlatSessionCatalog, type MessagesSnapshot, type ModelProtocol, type ModelsListing, type OpenSessionResponse, type PermissionBehaviorKind, type RespondRequest, type ScheduleListing, type SessionCatalog, type SessionContext, type SessionContextSelectResponse, type SettingsSnapshot, type SettingsTier, type UserContentBlock } from './types'
import { currentToken } from './token'

/**
 * Gateway REST client. Every call carries the launch token as a Bearer
 * header (the SSE subscription is the one place that cannot, and that one
 * goes through EventSource with ?token= instead).
 */

const TOKEN_REQUIRED = new ApiError(0, 'no_token',
  '缺少启动 token：请从 TUI 的 /web 命令输出的 URL 打开本页面')

function authHeaders(): Record<string, string> {
  const token = currentToken()
  if (token == null) throw TOKEN_REQUIRED
  return { Authorization: `Bearer ${token}` }
}

async function requestJson<T>(method: string, path: string, body?: unknown): Promise<T> {
  const response = await fetch(path, {
    method,
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    ...(body == null ? {} : { body: JSON.stringify(body) }),
  })
  if (!response.ok) {
    let code = 'api_error'
    let message = `${method} ${path} → HTTP ${response.status}`
    try {
      const parsed: unknown = await response.json()
      if (parsed != null && typeof parsed === 'object') {
        const error = (parsed as { error?: { type?: string; message?: string } }).error
        if (error != null) {
          if (typeof error.type === 'string') code = error.type
          if (typeof error.message === 'string') message = error.message
        }
      }
    } catch { /* non-JSON error body keeps the default message */ }
    throw new ApiError(response.status, code, message)
  }
  return await response.json() as Promise<T>
}

/**
 * GET /api/sessions serves the two-level projects[] tree when a catalog port
 * is wired, and otherwise falls back to a flat sessions[] listing over the
 * registry (GatewayServer.sessionsBody). The web client only ever works with
 * the tree shape, so the flat fallback is normalized into a single synthetic
 * project grouped by working directory.
 *
 * `perPage` pages each project to its most recent `perPage` sessions
 * (?per_project=; session_count keeps the total) — the sidebar grows the
 * page on demand instead of one unbounded listing.
 */
export async function fetchCatalog(perPage?: number): Promise<SessionCatalog> {
  const query = perPage != null ? `?per_project=${perPage}` : ''
  const raw = await requestJson<SessionCatalog | FlatSessionCatalog>('GET', `/api/sessions${query}`)
  if ('projects' in raw) return raw
  return { projects: groupFlatSessions(raw.sessions) }
}

function groupFlatSessions(sessions: FlatSessionCatalog['sessions']): readonly CatalogProject[] {
  const byWorkDir = new Map<string, CatalogProject>()
  for (const session of sessions) {
    const path = session.work_dir ?? ''
    const existing = byWorkDir.get(path)
    const row = {
      id: session.id,
      summary: session.summary,
      message_count: session.message_count,
      modified_at: session.modified_at ?? '',
      git_branch: session.git_branch,
      cwd: session.work_dir,
      custom_title: null,
      first_prompt: null,
      active: session.active ?? false,
      headless_open: session.headless_open ?? false,
    }
    if (existing == null) {
      byWorkDir.set(path, {
        project_path: path,
        project_name: path === '' ? '会话' : path,
        session_count: 1,
        sessions: [row],
      })
    } else {
      byWorkDir.set(path, {
        ...existing,
        session_count: existing.session_count + 1,
        sessions: [...existing.sessions, row],
      })
    }
  }
  return [...byWorkDir.values()]
}

export function fetchSnapshot(sessionId: string): Promise<MessagesSnapshot> {
  return requestJson('GET', `/api/sessions/${encodeURIComponent(sessionId)}/messages`)
}

export function openHeadlessSession(sessionId: string | null, projectPath: string | null): Promise<OpenSessionResponse> {
  return requestJson('POST', '/api/sessions/open', {
    ...(sessionId == null ? {} : { session_id: sessionId }),
    ...(projectPath == null ? {} : { project_path: projectPath }),
  })
}

export function closeHeadlessSession(sessionId: string): Promise<CloseSessionResponse> {
  return requestJson('POST', '/api/sessions/close', { session_id: sessionId })
}

export function respondPermission(request: RespondRequest): Promise<unknown> {
  return requestJson('POST', '/api/permissions/respond', request)
}

/**
 * GET /api/settings: the effective settings snapshot with per-tier source
 * attribution. Every POST op answers with the same refreshed shape.
 */
export function fetchSettingsSnapshot(): Promise<SettingsSnapshot> {
  return requestJson('GET', '/api/settings')
}

/**
 * Writes or removes (value == null) one top-level user-tier setting.
 */
export function writeUserSettingValue(key: string, value: string | boolean | null): Promise<SettingsSnapshot> {
  return requestJson('POST', '/api/settings', { op: 'userValue', key, value })
}

/**
 * Writes or removes (mode == null) permissions.defaultMode for one tier.
 */
export function writePermissionMode(mode: string | null, tier: SettingsTier): Promise<SettingsSnapshot> {
  return requestJson('POST', '/api/settings', { op: 'permissionMode', mode, tier })
}

/**
 * Replaces one permission behavior's rule array for one tier.
 */
export function replacePermissionRules(behavior: PermissionBehaviorKind, rules: readonly string[], tier: SettingsTier): Promise<SettingsSnapshot> {
  return requestJson('POST', '/api/settings', { op: 'permissionRules', behavior, rules, tier })
}

/**
 * Appends unseen directories to permissions.additionalDirectories for one tier.
 */
export function addAdditionalDirectories(directories: readonly string[], tier: SettingsTier): Promise<SettingsSnapshot> {
  return requestJson('POST', '/api/settings', { op: 'addDirectories', directories, tier })
}

/**
 * Removes directories from permissions.additionalDirectories for one tier.
 */
export function removeAdditionalDirectories(directories: readonly string[], tier: SettingsTier): Promise<SettingsSnapshot> {
  return requestJson('POST', '/api/settings', { op: 'removeDirectories', directories, tier })
}

/**
 * GET /api/schedule: every scheduled task known to the process.
 */
export function fetchScheduleTasks(): Promise<ScheduleListing> {
  return requestJson('GET', '/api/schedule')
}

/**
 * POST /api/schedule: adds one scheduled task. `model` is the optional
 * per-task model override; null keeps the session model at fire time.
 */
export function addScheduleTask(cron: string, prompt: string, recurring: boolean, durable: boolean, model: string | null): Promise<{ id: string }> {
  return requestJson('POST', '/api/schedule', model == null
    ? { cron, prompt, recurring, durable }
    : { cron, prompt, recurring, durable, model })
}

/**
 * DELETE /api/schedule/{id}: removes one scheduled task; 404 when absent.
 */
export function removeScheduleTask(id: string): Promise<{ removed: boolean }> {
  return requestJson('DELETE', `/api/schedule/${encodeURIComponent(id)}`)
}

/**
 * GET /api/commands: the composer "+" menu catalogue — every non-hidden
 * slash command, with skills included (they register as commands).
 */
export function fetchComposerCommands(): Promise<CommandsListing> {
  return requestJson('GET', '/api/commands')
}

/**
 * GET /api/session/context: the addressed session's model selection plus
 * the context usage sample (claude-hud token accounting) for the
 * composer's model seat and context meter. A null session id addresses
 * the active TUI session.
 */
export function fetchSessionContext(sessionId?: string | null): Promise<SessionContext> {
  const query = sessionId == null || sessionId === ''
    ? ''
    : `?session_id=${encodeURIComponent(sessionId)}`
  return requestJson('GET', `/api/session/context${query}`)
}

/**
 * POST /api/session/context: applies one model or effort selection to the
 * addressed session and answers with the refreshed selection. Exactly one
 * of `model` / `effort` must be present.
 */
export function selectSessionContext(input: {
  readonly session_id?: string | null
  readonly model?: string
  readonly effort?: string
}): Promise<SessionContextSelectResponse> {
  const body = input.session_id == null || input.session_id === ''
    ? { model: input.model, effort: input.effort }
    : input
  return requestJson('POST', '/api/session/context', body)
}

export type { ComposerCommandEntry } from './types'

/**
 * GET /api/models: every custom model known to the process.
 */
export function fetchCustomModels(): Promise<ModelsListing> {
  return requestJson('GET', '/api/models')
}

/**
 * POST /api/models: adds or updates one custom model, keyed by model name.
 * `apiKey` carries the three-way credential signal: `undefined` keeps the
 * existing key (edit without touching credentials), `null` clears it, and a
 * string sets a new value. Answers with the refreshed listing.
 */
export function saveCustomModel(input: {
  readonly modelName: string
  readonly protocol: ModelProtocol
  readonly baseUrl: string
  readonly apiKey?: string | null
  readonly headers?: Readonly<Record<string, string>>
  readonly contextWindow?: number | null
  /** `undefined` keeps the existing flag; `null`/`false`/`true` set it. */
  readonly multimodal?: boolean | null
}): Promise<ModelsListing> {
  return requestJson('POST', '/api/models', {
    model_name: input.modelName,
    protocol: input.protocol,
    base_url: input.baseUrl,
    ...(input.apiKey === undefined ? {} : { api_key: input.apiKey }),
    ...(input.headers == null ? {} : { headers: input.headers }),
    ...(input.contextWindow == null ? {} : { context_window: input.contextWindow }),
    ...(input.multimodal === undefined ? {} : { multimodal: input.multimodal }),
  })
}

/**
 * DELETE /api/models/{name}: removes one custom model; 404 when absent.
 */
export function removeCustomModel(modelName: string): Promise<{ removed: boolean }> {
  return requestJson('DELETE', `/api/models/${encodeURIComponent(modelName)}`)
}

/**
 * Submits one turn through the Anthropic Messages protocol face. The SSE
 * response body is drained (rendering flows through /api/events), and only
 * terminal protocol errors surface here.
 *
 * `content` accepts either plain text or a block array: image/document
 * blocks ride the request as inline base64 attachments the session owner
 * turns into pasted chips and persisted files.
 */
export async function submitTurn(
  sessionId: string | null,
  content: string | readonly UserContentBlock[],
): Promise<void> {
  const response = await fetch('/v1/messages', {
    method: 'POST',
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({
      model: 'claude-sonnet-5',
      stream: true,
      // Absent session_id targets the active TUI session; an explicit id
      // (TUI session or open headless) routes to that conversation.
      ...(sessionId == null ? {} : { metadata: { session_id: sessionId } }),
      messages: [{ role: 'user', content }],
    }),
  })
  if (!response.ok || response.body == null) {
    let message = `POST /v1/messages → HTTP ${response.status}`
    try {
      const parsed: unknown = await response.json()
      if (parsed != null && typeof parsed === 'object') {
        const error = (parsed as { error?: { message?: string } }).error
        if (error != null && typeof error.message === 'string') message = error.message
      }
    } catch { /* keep the default message */ }
    throw new ApiError(response.status, 'submit_failed', message)
  }
  // Drain without parsing: the mirror stream owns rendering.
  await response.body.cancel()
}

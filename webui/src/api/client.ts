import { ApiError, type AgentPromptResponse, type AgentStopResponse, type AgentTranscriptSnapshot, type ArchiveSessionResponse, type BatchSaveModelsResponse, type CatalogProject, type CloseSessionResponse, type CommandsListing, type DeleteSessionResponse, type DiscoverModelsResponse, type FileSuggestionsListing, type FlatSessionCatalog, type ForkSessionResponse, type MessagesSnapshot, type ModelProtocol, type ModelsListing, type OpenSessionResponse, type PermissionBehaviorKind, type RenameSessionResponse, type RespondRequest, type RewindExecuteResponse, type RewindMode, type RewindPreviewResponse, type ScheduleListing, type SessionCancelResponse, type SessionCatalog, type SessionContext, type SessionContextSelectResponse, type SessionPermissionModeResponse, type SessionTaskBoard, type SettingsSnapshot, type SettingsTier, type ShellOutputDetail, type ShellsListing, type ShellStopResponse, type SpinnerVerbsListing, type SudoRespondRequest, type UserContentBlock } from './types'
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

/**
 * GET one API path with the launch token attached, returning the raw response.
 *
 * Exported for the changed-files read stores, which sit in a vendored tree and
 * therefore cannot use `requestJson`'s decoded shape but must still authenticate
 * the same way — a bare `fetch` carries no bearer token and the gateway answers
 * 401 on every route.
 *
 * @param path - the same-origin API path to read.
 * @param signal - aborts the in-flight request.
 * @returns the response, whatever its status; the caller decides what it means.
 */
export async function readAuthorized(path: string, signal: AbortSignal): Promise<Response> {
  return fetch(path, { method: 'GET', headers: authHeaders(), signal })
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
      ...(session.running === true ? { running: true } : {}),
      ...(session.remote === true ? { remote: true } : {}),
      // A flat row comes from the registry, which lists only live handles, so
      // an absent flag must not be read as "no host".
      interactive: session.interactive ?? true,
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

/**
 * GET /api/sessions/{id}/agents/{agentId}/transcript: one sub-agent's own
 * conversation, restored from its sidechain transcript.
 *
 * The answer is the same message-row shape as the parent snapshot — the
 * gateway resolves and parses the sidechain with the same implementation the
 * TUI's local-agent viewer uses, then projects it through
 * `GatewayMessagesSnapshotHandler`'s row builder — so a client reduces it with
 * its ordinary snapshot reducer. What it never carries is turn numbering
 * (`turn`/`turn_usage`), because a sub-agent's conversation is its own, not a
 * numbered turn of the parent.
 */
export function fetchAgentTranscript(sessionId: string, agentId: string): Promise<AgentTranscriptSnapshot> {
  return requestJson(
    'GET',
    `/api/sessions/${encodeURIComponent(sessionId)}/agents/${encodeURIComponent(agentId)}/transcript`,
  )
}

/**
 * POST /api/sessions/{id}/agents/{agentId}/prompt: sends a follow-up message
 * to a sub-agent (queues onto a running agent or resumes from its sidechain).
 */
export function promptAgent(sessionId: string, agentId: string, prompt: string): Promise<AgentPromptResponse> {
  return requestJson(
    'POST',
    `/api/sessions/${encodeURIComponent(sessionId)}/agents/${encodeURIComponent(agentId)}/prompt`,
    { prompt },
  )
}

/** POST /api/sessions/{id}/agents/{agentId}/stop: interrupts a running sub-agent. */
export function stopAgent(sessionId: string, agentId: string): Promise<AgentStopResponse> {
  return requestJson(
    'POST',
    `/api/sessions/${encodeURIComponent(sessionId)}/agents/${encodeURIComponent(agentId)}/stop`,
    {},
  )
}

/** GET /api/shells: lists background shell tasks in the host process. */
export function fetchShells(): Promise<ShellsListing> {
  return requestJson('GET', '/api/shells')
}

/** GET /api/shells/{taskId}: detail and captured output for one background shell. */
export function fetchShellOutput(taskId: string): Promise<ShellOutputDetail> {
  return requestJson('GET', `/api/shells/${encodeURIComponent(taskId)}`)
}

/** POST /api/shells/{taskId}/stop: terminates a running background shell. */
export function stopShell(taskId: string): Promise<ShellStopResponse> {
  return requestJson('POST', `/api/shells/${encodeURIComponent(taskId)}/stop`, {})
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

/** POST /api/sessions/{id}/rename: renames one session in place. */
export function renameSession(sessionId: string, title: string): Promise<RenameSessionResponse> {
  return requestJson('POST', `/api/sessions/${encodeURIComponent(sessionId)}/rename`, { title })
}

/** POST /api/sessions/{id}/fork: forks one session at its last completed turn or a specific message. */
export function forkSession(
  sessionId: string,
  title?: string,
  upToMessageId?: string,
): Promise<ForkSessionResponse> {
  return requestJson('POST', `/api/sessions/${encodeURIComponent(sessionId)}/fork`, {
    ...(title == null ? {} : { title }),
    ...(upToMessageId == null ? {} : { up_to_message_id: upToMessageId }),
  })
}

/** GET /api/sessions/{id}/rewind/preview: dry-run conversation & code diff impact preview. */
export function previewRewind(sessionId: string, messageId: string): Promise<RewindPreviewResponse> {
  const query = `?messageId=${encodeURIComponent(messageId)}`
  return requestJson('GET', `/api/sessions/${encodeURIComponent(sessionId)}/rewind/preview${query}`)
}

/** POST /api/sessions/{id}/rewind: rewinds conversation and/or code to the target user message. */
export function executeRewind(
  sessionId: string,
  messageId: string,
  mode: RewindMode,
): Promise<RewindExecuteResponse> {
  return requestJson('POST', `/api/sessions/${encodeURIComponent(sessionId)}/rewind`, {
    message_id: messageId,
    mode,
  })
}

/** POST /api/sessions/{id}/archive: one-way hide from every catalog view. */
export function archiveSession(sessionId: string): Promise<ArchiveSessionResponse> {
  return requestJson('POST', `/api/sessions/${encodeURIComponent(sessionId)}/archive`)
}

/** DELETE /api/sessions/{id}: permanently deletes a session's transcript. */
export function deleteSession(sessionId: string): Promise<DeleteSessionResponse> {
  return requestJson('DELETE', `/api/sessions/${encodeURIComponent(sessionId)}`)
}

export function respondPermission(request: RespondRequest): Promise<unknown> {
  return requestJson('POST', '/api/permissions/respond', request)
}

/** POST /api/sudo/respond: supplies or declines the password for one sudo ask. */
export function respondSudo(request: SudoRespondRequest): Promise<unknown> {
  return requestJson('POST', '/api/sudo/respond', request)
}

/**
 * GET /api/settings: the effective settings snapshot with per-tier source
 * attribution. Every POST op answers with the same refreshed shape.
 */
export function fetchSettingsSnapshot(cwd?: string | null): Promise<SettingsSnapshot> {
  const qs = cwd != null && cwd.trim() !== '' ? `?cwd=${encodeURIComponent(cwd.trim())}` : ''
  return requestJson('GET', `/api/settings${qs}`)
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
export function writePermissionMode(mode: string | null, tier: SettingsTier, cwd?: string | null): Promise<SettingsSnapshot> {
  return requestJson('POST', '/api/settings', {
    op: 'permissionMode',
    mode,
    tier,
    ...(cwd != null && cwd.trim() !== '' ? { cwd: cwd.trim() } : {}),
  })
}

/**
 * Replaces one permission behavior's rule array for one tier.
 */
export function replacePermissionRules(
  behavior: PermissionBehaviorKind,
  rules: readonly string[],
  tier: SettingsTier,
  cwd?: string | null,
): Promise<SettingsSnapshot> {
  return requestJson('POST', '/api/settings', {
    op: 'permissionRules',
    behavior,
    rules,
    tier,
    ...(cwd != null && cwd.trim() !== '' ? { cwd: cwd.trim() } : {}),
  })
}

/**
 * Appends unseen directories to permissions.additionalDirectories for one tier.
 */
export function addAdditionalDirectories(
  directories: readonly string[],
  tier: SettingsTier,
  cwd?: string | null,
): Promise<SettingsSnapshot> {
  return requestJson('POST', '/api/settings', {
    op: 'addDirectories',
    directories,
    tier,
    ...(cwd != null && cwd.trim() !== '' ? { cwd: cwd.trim() } : {}),
  })
}

/**
 * Removes directories from permissions.additionalDirectories for one tier.
 */
export function removeAdditionalDirectories(
  directories: readonly string[],
  tier: SettingsTier,
  cwd?: string | null,
): Promise<SettingsSnapshot> {
  return requestJson('POST', '/api/settings', {
    op: 'removeDirectories',
    directories,
    tier,
    ...(cwd != null && cwd.trim() !== '' ? { cwd: cwd.trim() } : {}),
  })
}

/** GET /api/projects: registered multi-folder projects and custom display names. */
export function fetchWebProjects(): Promise<import('./types').WebProjectsListing> {
  return requestJson('GET', '/api/projects')
}

/** POST /api/projects: creates or updates a multi-folder project record. */
export function createWebProject(input: {
  path: string
  name?: string
  additional_directories?: readonly string[]
  tier?: 'local' | 'project'
}): Promise<import('./types').WebProjectMutationResponse> {
  return requestJson('POST', '/api/projects', {
    path: input.path,
    name: input.name ?? '',
    additional_directories: input.additional_directories ?? [],
    tier: input.tier ?? 'local',
  })
}

/** PATCH /api/projects: updates a project's custom display name. */
export function renameWebProject(path: string, name: string): Promise<import('./types').WebProjectMutationResponse> {
  return requestJson('PATCH', '/api/projects', { path, name })
}

/** DELETE /api/projects: removes a project from the web registry (never deletes disk files). */
export function deleteWebProject(path: string): Promise<import('./types').WebProjectsListing> {
  return requestJson('DELETE', `/api/projects?path=${encodeURIComponent(path)}`)
}

/** GET /api/projects/customizations: project-scoped folders, Rules, Skills, and MCP overview. */
export function fetchProjectCustomizations(path: string): Promise<import('./types').ProjectCustomizations> {
  return requestJson('GET', `/api/projects/customizations?path=${encodeURIComponent(path)}`)
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
export function addScheduleTask(
  cron: string,
  prompt: string,
  recurring: boolean,
  durable: boolean,
  model: string | null,
  options?: import('./types').ScheduleTaskOptions,
): Promise<{ id: string }> {
  return requestJson('POST', '/api/schedule', {
    cron,
    prompt,
    recurring,
    durable,
    ...(model != null && model !== '' ? { model } : {}),
    ...(options?.effort ? { effort: options.effort } : {}),
    ...(options?.working_directory ? { working_directory: options.working_directory } : {}),
    ...(options?.new_session_per_run !== undefined ? { new_session_per_run: options.new_session_per_run } : {}),
    ...(options?.notification_mode ? { notification_mode: options.notification_mode } : {}),
    ...(options?.archive_on_success !== undefined ? { archive_on_success: options.archive_on_success } : {}),
    ...(options?.enabled !== undefined ? { enabled: options.enabled } : {}),
  })
}

/**
 * PUT /api/schedule/{id}: updates an existing scheduled task in place.
 */
export function updateScheduleTask(
  id: string,
  cron: string,
  prompt: string,
  recurring: boolean,
  durable: boolean,
  options?: import('./types').ScheduleTaskOptions,
): Promise<{ updated: boolean; id: string }> {
  return requestJson('PUT', `/api/schedule/${encodeURIComponent(id)}`, {
    cron,
    prompt,
    recurring,
    durable,
    ...(options?.model ? { model: options.model } : {}),
    ...(options?.effort ? { effort: options.effort } : {}),
    ...(options?.working_directory ? { working_directory: options.working_directory } : {}),
    ...(options?.new_session_per_run !== undefined ? { new_session_per_run: options.new_session_per_run } : {}),
    ...(options?.notification_mode ? { notification_mode: options.notification_mode } : {}),
    ...(options?.archive_on_success !== undefined ? { archive_on_success: options.archive_on_success } : {}),
    ...(options?.enabled !== undefined ? { enabled: options.enabled } : {}),
  })
}

/**
 * PATCH /api/schedule/{id}: enables or pauses one scheduled task without deleting it.
 */
export function toggleScheduleTask(
  id: string,
  enabled: boolean,
): Promise<{ updated: boolean; id: string; enabled: boolean }> {
  return requestJson('PATCH', `/api/schedule/${encodeURIComponent(id)}`, { enabled })
}

export interface RuntimeKeepaliveState {
  readonly enabled: boolean
  readonly supported: boolean
  readonly active: boolean
}

export interface RuntimeRestartResult {
  readonly restarted: boolean
  readonly reloaded_tasks: number
  readonly reloaded_models: number
}

export function fetchKeepaliveState(): Promise<RuntimeKeepaliveState> {
  return requestJson('GET', '/api/runtime/keepalive')
}

export function setKeepaliveEnabled(enabled: boolean): Promise<RuntimeKeepaliveState> {
  return requestJson('POST', '/api/runtime/keepalive', { enabled })
}

export function restartGatewayRuntime(): Promise<RuntimeRestartResult> {
  return requestJson('POST', '/api/runtime/restart', {})
}

export interface WorkspaceTreeEntry {
  readonly name: string
  readonly path: string
  readonly directory: boolean
  readonly size: number
}

export interface WorkspaceTreeListing {
  readonly path: string
  readonly entries: readonly WorkspaceTreeEntry[]
}

/**
 * GET /api/workspace-tree: immediate children of a workspace directory for the
 * right sidebar's Files tab.
 */
export function fetchWorkspaceTree(
  sessionId: string | null | undefined,
  dir = '',
): Promise<WorkspaceTreeListing> {
  const params = new URLSearchParams()
  if (sessionId != null && sessionId !== '') params.set('session_id', sessionId)
  if (dir !== '') params.set('path', dir)
  const query = params.toString()
  return requestJson('GET', `/api/workspace-tree${query === '' ? '' : `?${query}`}`)
}

/**
 * POST /api/workspace-open: opens or reveals a workspace-contained file using
 * the local host desktop launcher (`open` / `open -R` on macOS, `xdg-open` on Linux).
 */
export function openWorkspaceFileInHost(
  sessionId: string | null | undefined,
  path: string,
  reveal = false,
): Promise<{ readonly opened: boolean; readonly path: string; readonly reveal: boolean }> {
  return requestJson('POST', '/api/workspace-open', {
    ...(sessionId != null && sessionId !== '' ? { session_id: sessionId } : {}),
    path,
    reveal,
  })
}

/**
 * DELETE /api/schedule/{id}: removes one scheduled task; 404 when absent.
 */
export function removeScheduleTask(id: string): Promise<{ removed: boolean }> {
  return requestJson('DELETE', `/api/schedule/${encodeURIComponent(id)}`)
}

/**
 * POST /api/schedule/{id}/run: triggers one scheduled task immediately in a
 * fresh headless session (bypassPermissions).
 */
export function runScheduleTask(id: string): Promise<{ triggered: boolean; id: string }> {
  return requestJson('POST', `/api/schedule/${encodeURIComponent(id)}/run`, {})
}

/**
 * GET /api/commands: the composer "+" menu catalogue — every non-hidden
 * slash command, with skills included (they register as commands).
 */
export function fetchComposerCommands(): Promise<CommandsListing> {
  return requestJson('GET', '/api/commands')
}

/**
 * GET /api/spinner-verbs: the TUI spinner's verb vocabulary, which the
 * running status words its live row from.
 */
export function fetchSpinnerVerbs(): Promise<SpinnerVerbsListing> {
  return requestJson('GET', '/api/spinner-verbs')
}

/**
 * GET /api/file-suggestions: workspace path candidates for the composer's
 * `@` completion menu, ranked by the same scorer the TUI typeahead uses.
 */
export function fetchFileSuggestions(query: string): Promise<FileSuggestionsListing> {
  const suffix = query === '' ? '' : `?q=${encodeURIComponent(query)}`
  return requestJson('GET', `/api/file-suggestions${suffix}`)
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
 * The dsh-context timeline routes (GatewayContextTimelineHandler). These
 * answer the vendored dsh-context `shared/types.ts` camelCase shapes — an
 * intentional exception to the gateway's snake_case wire so the vendored
 * client narrows them verbatim — hence `unknown` here: the narrowing lives
 * in vendor/dsh-context/client/narrow.ts, not in these types.
 */
function contextQuery(sessionId: string | null | undefined, extra: Record<string, string | number | undefined> = {}): string {
  const params = new URLSearchParams()
  if (sessionId != null && sessionId !== '') params.set('session_id', sessionId)
  for (const [key, value] of Object.entries(extra)) {
    if (value !== undefined) params.set(key, String(value))
  }
  const query = params.toString()
  return query === '' ? '' : `?${query}`
}

/** GET /api/session/context/timeline → `{ timeline: ContextTimeline | null }` (null = cold session). */
export async function fetchContextTimeline(sessionId?: string | null): Promise<unknown> {
  const body = await requestJson<{ timeline: unknown }>('GET', `/api/session/context/timeline${contextQuery(sessionId)}`)
  return body.timeline ?? null
}

/** GET /api/session/context/detail → `{ detail: ContextTimelineDetail | null }`. */
export async function fetchContextDetail(sessionId?: string | null): Promise<unknown> {
  const body = await requestJson<{ detail: unknown }>('GET', `/api/session/context/detail${contextQuery(sessionId)}`)
  return body.detail ?? null
}

/**
 * GET /api/session/context/content: one surface node's stored text
 * (`seq=`), the system prompt (`kind=system`), every tool definition
 * (`kind=tools`), or one tool's definition (`kind=tool&name=`). Resolves null on 404 (the ledger no longer holds it).
 */
export async function fetchContextContent(
  sessionId: string | null | undefined,
  target: { seq: number } | { kind: 'system' } | { kind: 'tools' } | { kind: 'tool'; name: string },
): Promise<Record<string, unknown> | null> {
  try {
    const body = await requestJson<{ content: Record<string, unknown> }>(
      'GET', `/api/session/context/content${contextQuery(sessionId, target)}`)
    return body.content ?? null
  } catch (failure) {
    if (failure instanceof ApiError && failure.status === 404) return null
    throw failure
  }
}

/** GET /api/session/context/overview → every live session's head + activity. */
export function fetchContextOverview(): Promise<unknown> {
  return requestJson('GET', '/api/session/context/overview')
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

/**
 * GET /api/session/permission-mode: the addressed session's current
 * permission mode plus the selectable modes, for the composer's
 * permission-mode chip. A null session id addresses the active TUI session.
 */
export function fetchPermissionMode(sessionId?: string | null): Promise<SessionPermissionModeResponse> {
  const query = sessionId == null || sessionId === ''
    ? ''
    : `?session_id=${encodeURIComponent(sessionId)}`
  return requestJson('GET', `/api/session/permission-mode${query}`)
}

/**
 * POST /api/session/permission-mode: applies a new permission mode to the
 * addressed session and answers with the refreshed state.
 */
export function selectPermissionMode(input: {
  readonly session_id?: string | null
  readonly mode: string
}): Promise<SessionPermissionModeResponse> {
  const body = input.session_id == null || input.session_id === ''
    ? { mode: input.mode }
    : input
  return requestJson('POST', '/api/session/permission-mode', body)
}

/**
 * POST /api/session/cancel: stops the addressed session's running turn — the
 * composer's stop control (the primary button's stop form). The cancellation
 * is the same `user-cancel` interrupt the TUI's Ctrl+C drives, so the
 * transcript records it identically. A blank session id addresses the active
 * TUI session; an id no live session answers to is a 404.
 */
export function cancelTurn(input: {
  readonly session_id?: string | null
}): Promise<SessionCancelResponse> {
  const body = input.session_id == null || input.session_id === ''
    ? {}
    : input
  return requestJson('POST', '/api/session/cancel', body)
}

/**
 * GET /api/session/tasks: one session's current to-do board, for the dock's
 * cold load. The live board also arrives as `task_board` mirror frames; this
 * answers the state a client connecting mid-session has not yet seen. The
 * addressed session follows the permission-mode routing: blank means the
 * active TUI session.
 */
export function fetchSessionTasks(sessionId?: string | null): Promise<SessionTaskBoard> {
  const query = sessionId == null || sessionId === ''
    ? ''
    : `?session_id=${encodeURIComponent(sessionId)}`
  return requestJson('GET', `/api/session/tasks${query}`)
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
 * POST /api/models/discover: queries a provider's `/models` endpoint server-side
 * (OpenAI, Anthropic, or Gemini shape) so the user can pick multiple models to
 * import into the standard flat `model.json` catalogue.
 */
export function discoverProviderModels(input: {
  readonly protocol: ModelProtocol
  readonly baseUrl: string
  readonly apiKey?: string
  readonly headers?: Readonly<Record<string, string>>
}): Promise<DiscoverModelsResponse> {
  return requestJson('POST', '/api/models/discover', {
    protocol: input.protocol,
    base_url: input.baseUrl,
    ...(input.apiKey == null || input.apiKey === '' ? {} : { api_key: input.apiKey }),
    ...(input.headers == null ? {} : { headers: input.headers }),
  })
}

/**
 * POST /api/models/batch: saves multiple discovered models sharing one provider's
 * credentials and endpoint into `~/.claude/model.json` as standard flat entries.
 */
export function batchSaveCustomModels(input: {
  readonly protocol: ModelProtocol
  readonly baseUrl: string
  readonly apiKey?: string | null
  readonly headers?: Readonly<Record<string, string>>
  readonly contextWindow?: number | null
  readonly multimodal?: boolean | null
  readonly models: readonly { readonly model_name: string; readonly context_window?: number }[]
}): Promise<BatchSaveModelsResponse> {
  return requestJson('POST', '/api/models/batch', {
    protocol: input.protocol,
    base_url: input.baseUrl,
    ...(input.apiKey === undefined ? {} : { api_key: input.apiKey }),
    ...(input.headers == null ? {} : { headers: input.headers }),
    ...(input.contextWindow == null ? {} : { context_window: input.contextWindow }),
    ...(input.multimodal === undefined ? {} : { multimodal: input.multimodal }),
    models: input.models,
  })
}

/**
 * The model the user persisted in settings (`effective.model`). Turns are
 * never sent with a model the user did not choose, so a missing value fails
 * the submit instead of falling back to a built-in name.
 */
async function persistedModel(): Promise<string> {
  const snapshot = await fetchSettingsSnapshot()
  const model: unknown = snapshot.effective?.model
  if (typeof model !== 'string' || model.trim() === '') {
    throw new ApiError(409, 'model_missing', 'no persisted model in settings')
  }
  return model
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
  const model = await persistedModel()
  const response = await fetch('/v1/messages', {
    method: 'POST',
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({
      model,
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

// The two changed-files reads (`/api/changes.summary`, `/api/changes.diff`) are
// deliberately not wrapped here. The vendored stores own them —
// `changes-summary.ts` and `changes-diff.ts` build their URLs from `changes.ts`
// and read through the vendored `HostReadStore`, which is what caches a state
// per record and decides what each response means. A wrapper beside them would
// only be a second URL builder to keep in step with the first. They do share
// this module's transport: `HostReadStore` reads through `readAuthorized`, so
// a route that needs the launch token is authorized exactly like the rest.

/** One workspace file as the gateway serves it for the preview tab. */
export interface WorkspaceFileContent {
  readonly path: string
  readonly kind: 'text' | 'binary' | 'oversized'
  /** Present only when `kind` is `text`. */
  readonly text?: string
}

/**
 * GET /api/workspace-file — one workspace file's content for the preview tab.
 *
 * @param sessionId - the session whose workspace the path resolves against.
 * @param path - the file path, relative to that workspace.
 * @returns the file's kind and, for text, its content.
 */
export async function fetchWorkspaceFile(sessionId: string, path: string): Promise<WorkspaceFileContent> {
  const params = new URLSearchParams({ session_id: sessionId, path })
  return requestJson<WorkspaceFileContent>('GET', `/api/workspace-file?${params.toString()}`)
}

// ---------------------------------------------------------------------------
// LAN Model Gateway admin endpoints
// ---------------------------------------------------------------------------

export function fetchLanGatewayStatus(): Promise<import('./types').LanGatewayStatus> {
  return requestJson('GET', '/api/lan-gateway')
}

export function updateLanGatewaySettings(input: {
  readonly enabled?: boolean
  readonly exposeDefaultProvider?: boolean
  readonly tierStandInModel?: string
  readonly preventMacSleep?: boolean
  readonly allowedCidrs?: readonly string[]
}): Promise<import('./types').LanGatewayStatus> {
  return requestJson('POST', '/api/lan-gateway', {
    ...(input.enabled === undefined ? {} : { enabled: input.enabled }),
    ...(input.exposeDefaultProvider === undefined
      ? {} : { expose_default_provider: input.exposeDefaultProvider }),
    ...(input.tierStandInModel === undefined
      ? {} : { tier_stand_in_model: input.tierStandInModel }),
    ...(input.preventMacSleep === undefined
      ? {} : { prevent_mac_sleep: input.preventMacSleep }),
    ...(input.allowedCidrs === undefined
      ? {} : { allowed_cidrs: input.allowedCidrs }),
  })
}

export function createLanGatewayKey(input: {
  readonly name: string
  readonly allowedModels?: readonly string[]
}): Promise<import('./types').LanGatewayStatus> {
  return requestJson('POST', '/api/lan-gateway/keys', {
    name: input.name,
    ...(input.allowedModels == null ? {} : { allowed_models: input.allowedModels }),
  })
}

export function updateLanGatewayKey(
  id: string,
  input: {
    readonly name?: string
    readonly enabled?: boolean
    readonly allowedModels?: readonly string[]
  },
): Promise<import('./types').LanGatewayStatus> {
  return requestJson('POST', `/api/lan-gateway/keys/${encodeURIComponent(id)}`, {
    ...(input.name === undefined ? {} : { name: input.name }),
    ...(input.enabled === undefined ? {} : { enabled: input.enabled }),
    ...(input.allowedModels === undefined ? {} : { allowed_models: input.allowedModels }),
  })
}

export function deleteLanGatewayKey(id: string): Promise<import('./types').LanGatewayStatus> {
  return requestJson('DELETE', `/api/lan-gateway/keys/${encodeURIComponent(id)}`)
}

export function fetchLanGatewayCalls(): Promise<import('./types').LanGatewayCallsResponse> {
  return requestJson('GET', '/api/lan-gateway/calls')
}

export function clearLanGatewayCalls(): Promise<import('./types').LanGatewayCallsResponse> {
  return requestJson('DELETE', '/api/lan-gateway/calls')
}

export function fetchCcConnectStatus(): Promise<import('./types').CcConnectSnapshot> {
  return requestJson('GET', '/api/cc-connect')
}

export function attachCcConnect(input: {
  readonly pid: number
  readonly sessionId?: string
  readonly channel?: string
}): Promise<import('./types').CcConnectSnapshot> {
  return requestJson('POST', '/api/cc-connect/attach', {
    pid: input.pid,
    ...(input.sessionId == null || input.sessionId === '' ? {} : { session_id: input.sessionId }),
    ...(input.channel == null || input.channel === '' ? {} : { channel: input.channel }),
    origin: 'web',
  })
}

export function detachCcConnect(input: {
  readonly pid: number
  readonly sessionId?: string
}): Promise<import('./types').CcConnectSnapshot> {
  return requestJson('POST', '/api/cc-connect/detach', {
    pid: input.pid,
    ...(input.sessionId == null || input.sessionId === '' ? {} : { session_id: input.sessionId }),
  })
}

export function fetchCcConnectLogs(
  pid: number,
  tail = 200,
): Promise<import('./types').CcConnectLogsResponse> {
  return requestJson('GET', `/api/cc-connect/logs?pid=${encodeURIComponent(String(pid))}&tail=${encodeURIComponent(String(tail))}`)
}

export async function runCcConnectSetup(
  input: {
    readonly mode: import('./types').CcConnectSetupMode
    readonly appId?: string
    readonly appSecret?: string
    readonly workDir?: string
  },
  onProgress?: (line: string) => void,
): Promise<import('./types').CcConnectSetupResult> {
  const response = await fetch('/api/cc-connect/setup', {
    method: 'POST',
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({
      mode: input.mode,
      ...(input.appId == null || input.appId === '' ? {} : { app_id: input.appId }),
      ...(input.appSecret == null || input.appSecret === '' ? {} : { app_secret: input.appSecret }),
      ...(input.workDir == null || input.workDir === '' ? {} : { work_dir: input.workDir }),
    }),
  })
  if (!response.ok) {
    let message = `POST /api/cc-connect/setup → HTTP ${response.status}`
    try {
      const parsed = await response.json() as { error?: { message?: string } }
      if (typeof parsed.error?.message === 'string') message = parsed.error.message
    } catch { /* keep default */ }
    throw new ApiError(response.status, 'cc_connect_setup_error', message)
  }
  const reader = response.body?.getReader()
  if (reader == null) {
    throw new ApiError(500, 'cc_connect_setup_error', 'Empty setup response stream')
  }
  const decoder = new TextDecoder()
  let buffer = ''
  let finalResult: import('./types').CcConnectSetupResult | null = null
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    let nl = buffer.indexOf('\n')
    while (nl >= 0) {
      const raw = buffer.slice(0, nl).trim()
      buffer = buffer.slice(nl + 1)
      if (raw !== '') {
        try {
          const frame = JSON.parse(raw) as {
            type?: string
            line?: string
            ok?: boolean
            channel?: string
            message?: string
          }
          if (frame.type === 'progress' && typeof frame.line === 'string') {
            onProgress?.(frame.line)
          } else if (frame.type === 'result') {
            finalResult = {
              ok: Boolean(frame.ok),
              channel: frame.channel ?? '',
              message: frame.message ?? '',
            }
          }
        } catch { /* ignore malformed line */ }
      }
      nl = buffer.indexOf('\n')
    }
  }
  return finalResult ?? { ok: false, channel: '', message: 'Setup stream ended unexpectedly' }
}

export function cancelCcConnectSetup(): Promise<{ readonly cancelled: boolean }> {
  return requestJson('POST', '/api/cc-connect/setup/cancel', {})
}

// ---------------------------------------------------------------------------
// Host directory browser (/api/directories — backs vendored DirectoryBrowser)
// ---------------------------------------------------------------------------

export async function listDirectory(
  path?: string,
  signal?: AbortSignal,
): Promise<import('@vendor/ui-directory-picker-browse/types').DirectoryListing> {
  const query = path != null && path !== '' ? `?path=${encodeURIComponent(path)}` : ''
  const headers: Record<string, string> = {}
  const token = currentToken()
  if (token != null) headers['Authorization'] = `Bearer ${token}`
  const response = await fetch(`/api/directories${query}`, {
    method: 'GET',
    headers,
    signal: signal ?? null,
  })
  const text = await response.text()
  const parsed = text.trim() === '' ? {} : JSON.parse(text) as Record<string, unknown>
  if (!response.ok) {
    const err = (parsed.error ?? {}) as { type?: string; message?: string }
    throw new ApiError(response.status, err.type ?? 'directory_error', err.message ?? response.statusText)
  }
  return parsed as unknown as import('@vendor/ui-directory-picker-browse/types').DirectoryListing
}

export async function createDirectory(path: string, name: string): Promise<string> {
  const result = await requestJson<{ readonly path: string }>('POST', '/api/directories', { path, name })
  return result.path
}



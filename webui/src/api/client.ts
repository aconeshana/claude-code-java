import { ApiError, type CatalogProject, type CloseSessionResponse, type FlatSessionCatalog, type MessagesSnapshot, type OpenSessionResponse, type RespondRequest, type SessionCatalog } from './types'
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
 */
export async function fetchCatalog(): Promise<SessionCatalog> {
  const raw = await requestJson<SessionCatalog | FlatSessionCatalog>('GET', '/api/sessions')
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
 * Submits one turn through the Anthropic Messages protocol face. The SSE
 * response body is drained (rendering flows through /api/events), and only
 * terminal protocol errors surface here.
 */
export async function submitTurn(sessionId: string | null, text: string): Promise<void> {
  const response = await fetch('/v1/messages', {
    method: 'POST',
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({
      model: 'claude-sonnet-5',
      stream: true,
      // Absent session_id targets the active TUI session; an explicit id
      // (TUI session or open headless) routes to that conversation.
      ...(sessionId == null ? {} : { metadata: { session_id: sessionId } }),
      messages: [{ role: 'user', content: text }],
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

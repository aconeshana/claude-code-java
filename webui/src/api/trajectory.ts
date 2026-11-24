import { readAuthorized } from './client'
import { ApiError } from './types'
import type { AssistantRequestConfig, JsonValue } from '@vendor/ui-trajectory/shim-types.ts'
import type { TrajectoryCellProps } from '@vendor/ui-trajectory/trajectory-record.ts'
import type { TrajectoryUsage } from '@vendor/ui-trajectory/TrajectoryTable.tsx'

export interface RawTrajectoryGroup {
  readonly kind: 'message' | 'step' | 'compaction'
  readonly step?: number | undefined
  readonly seq?: number | undefined
  readonly durationMs?: number | undefined
  readonly cells: readonly TrajectoryCellProps[]
}

export interface RawTrajectoryTurn {
  readonly turn: number | null
  readonly groups: readonly RawTrajectoryGroup[]
}

export interface RawTrajectoryRequestNumber {
  readonly purpose?: 'assistant' | 'compaction' | undefined
  readonly seq?: number | undefined
  readonly turn: number | null
  readonly step: number
  readonly number: number
  readonly status?: 'complete' | 'running' | 'error' | undefined
  readonly startedAt?: number | undefined
  readonly completedAt?: number | null | undefined
  readonly error?: string | undefined
  readonly errorCode?: string | undefined
  readonly retry?: number | undefined
  readonly maxRetries?: number | undefined
  readonly retryDelayMs?: number | undefined
  readonly provider?: string | undefined
  readonly model?: string | undefined
  readonly requestConfig?: AssistantRequestConfig | undefined
  readonly usage?: TrajectoryUsage | undefined
  readonly cumulativeUsage?: TrajectoryUsage | undefined
  readonly [key: string]: JsonValue | AssistantRequestConfig | TrajectoryUsage | undefined
}

export interface TrajectoryResponse {
  readonly session_id: string
  readonly agent_id?: string | undefined
  readonly agent_type?: string | undefined
  readonly agent_depth?: number | undefined
  readonly total_turns: number
  readonly oldest_turn: number
  readonly has_older_turns: boolean
  readonly running_since?: number | undefined
  readonly turns: readonly RawTrajectoryTurn[]
  readonly request_numbers: readonly RawTrajectoryRequestNumber[]
}

async function getTrajectoryJson(path: string, signal?: AbortSignal): Promise<TrajectoryResponse> {
  const controller = new AbortController()
  const activeSignal = signal ?? controller.signal
  const res = await readAuthorized(path, activeSignal)
  if (!res.ok) {
    let code = 'api_error'
    let message = `GET ${path} → HTTP ${res.status}`
    try {
      const parsed: unknown = await res.json()
      if (parsed != null && typeof parsed === 'object') {
        const err = (parsed as { error?: { type?: string; message?: string } }).error
        if (err != null) {
          if (typeof err.type === 'string') code = err.type
          if (typeof err.message === 'string') message = err.message
        }
      }
    } catch { /* ignore */ }
    throw new ApiError(res.status, code, message)
  }
  return await res.json() as TrajectoryResponse
}

export function fetchSessionTrajectory(
  sessionId: string,
  options?: {
    readonly beforeTurn?: number | undefined
    readonly limit?: number | undefined
    readonly includeCallId?: string | null | undefined
    readonly signal?: AbortSignal | undefined
  },
): Promise<TrajectoryResponse> {
  const qs = new URLSearchParams()
  if (options?.beforeTurn != null) qs.set('before_turn', String(options.beforeTurn))
  if (options?.limit != null) qs.set('limit', String(options.limit))
  if (options?.includeCallId != null && options.includeCallId !== '') {
    qs.set('include_call_id', options.includeCallId)
  }
  const suffix = qs.toString() === '' ? '' : `?${qs.toString()}`
  return getTrajectoryJson(
    `/api/sessions/${encodeURIComponent(sessionId)}/trajectory${suffix}`,
    options?.signal,
  )
}

export function fetchAgentTrajectory(
  sessionId: string,
  agentId: string,
  options?: {
    readonly beforeTurn?: number | undefined
    readonly limit?: number | undefined
    readonly signal?: AbortSignal | undefined
  },
): Promise<TrajectoryResponse> {
  const qs = new URLSearchParams()
  if (options?.beforeTurn != null) qs.set('before_turn', String(options.beforeTurn))
  if (options?.limit != null) qs.set('limit', String(options.limit))
  const suffix = qs.toString() === '' ? '' : `?${qs.toString()}`
  return getTrajectoryJson(
    `/api/sessions/${encodeURIComponent(sessionId)}/agents/${encodeURIComponent(agentId)}/trajectory${suffix}`,
    options?.signal,
  )
}

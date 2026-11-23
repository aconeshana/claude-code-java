import { create } from 'zustand'
import {
  clearLanGatewayCalls,
  createLanGatewayKey,
  deleteLanGatewayKey,
  fetchLanGatewayCalls,
  fetchLanGatewayStatus,
  updateLanGatewayKey,
  updateLanGatewaySettings,
} from '../api/client'
import type { LanGatewayCall, LanGatewayStatus } from '../api/types'

export type ClientSnippetKind = 'poco' | 'claude-code' | 'codex' | 'openai' | 'gemini'
export type ShellPlatformKind = 'powershell' | 'bash'

/** Pure helper generating copy-pasteable Windows PowerShell / Linux Bash / JSON snippets. */
export function buildClientSnippet(input: {
  readonly baseUrl: string
  readonly apiKey: string
  readonly modelName: string
  readonly client: ClientSnippetKind
  readonly platform: ShellPlatformKind
}): string {
  const cleanBase = input.baseUrl.replace(/\/+$/, '')
  const key = input.apiKey || 'sk-poco-YOUR_KEY'
  const model = input.modelName || 'your-model'

  if (input.client === 'poco') {
    return JSON.stringify({
      version: 1,
      models: [
        {
          modelName: model,
          protocol: 'anthropic',
          baseUrl: cleanBase,
          apiKey: key,
        },
      ],
    }, null, 2)
  }

  if (input.client === 'claude-code') {
    return input.platform === 'powershell'
      ? `$env:ANTHROPIC_BASE_URL="${cleanBase}"\n$env:ANTHROPIC_AUTH_TOKEN="${key}"\nclaude --model ${model}`
      : `export ANTHROPIC_BASE_URL="${cleanBase}"\nexport ANTHROPIC_AUTH_TOKEN="${key}"\nclaude --model ${model}`
  }

  if (input.client === 'codex') {
    return input.platform === 'powershell'
      ? `$env:OPENAI_BASE_URL="${cleanBase}/v1"\n$env:OPENAI_API_KEY="${key}"\ncodex --model ${model}`
      : `export OPENAI_BASE_URL="${cleanBase}/v1"\nexport OPENAI_API_KEY="${key}"\ncodex --model ${model}`
  }

  if (input.client === 'gemini') {
    return input.platform === 'powershell'
      ? `$env:GOOGLE_GEMINI_BASE_URL="${cleanBase}/v1beta"\n$env:GEMINI_API_KEY="${key}"`
      : `export GOOGLE_GEMINI_BASE_URL="${cleanBase}/v1beta"\nexport GEMINI_API_KEY="${key}"`
  }

  return input.platform === 'powershell'
    ? `$env:OPENAI_BASE_URL="${cleanBase}/v1"\n$env:OPENAI_API_KEY="${key}"`
    : `export OPENAI_BASE_URL="${cleanBase}/v1"\nexport OPENAI_API_KEY="${key}"`
}

export interface LanGatewayStore {
  readonly status: LanGatewayStatus | null
  readonly calls: readonly LanGatewayCall[]
  readonly loading: boolean
  readonly error: string | null
  refresh(): Promise<void>
  refreshCalls(): Promise<void>
  updateSettings(input: {
    readonly enabled?: boolean
    readonly exposeDefaultProvider?: boolean
    readonly tierStandInModel?: string
    readonly preventMacSleep?: boolean
    readonly allowedCidrs?: readonly string[]
  }): Promise<void>
  createKey(name: string, allowedModels?: readonly string[]): Promise<void>
  updateKey(id: string, input: {
    readonly name?: string
    readonly enabled?: boolean
    readonly allowedModels?: readonly string[]
  }): Promise<void>
  removeKey(id: string): Promise<void>
  clearCalls(): Promise<void>
}

export const useLanGateway = create<LanGatewayStore>((set) => ({
  status: null,
  calls: [],
  loading: false,
  error: null,

  async refresh() {
    set({ loading: true })
    try {
      const [status, callsResp] = await Promise.all([
        fetchLanGatewayStatus(),
        fetchLanGatewayCalls(),
      ])
      set({ status, calls: callsResp.calls, loading: false, error: null })
    } catch (failure) {
      set({ loading: false, error: messageOf(failure) })
    }
  },

  async refreshCalls() {
    try {
      const callsResp = await fetchLanGatewayCalls()
      set({ calls: callsResp.calls, error: null })
    } catch (failure) {
      set({ error: messageOf(failure) })
    }
  },

  async updateSettings(input) {
    try {
      const status = await updateLanGatewaySettings(input)
      set({ status, error: null })
    } catch (failure) {
      set({ error: messageOf(failure) })
    }
  },

  async createKey(name, allowedModels) {
    try {
      const status = await createLanGatewayKey(
        allowedModels == null ? { name } : { name, allowedModels },
      )
      set({ status, error: null })
    } catch (failure) {
      set({ error: messageOf(failure) })
    }
  },

  async updateKey(id, input) {
    try {
      const status = await updateLanGatewayKey(id, input)
      set({ status, error: null })
    } catch (failure) {
      set({ error: messageOf(failure) })
    }
  },

  async removeKey(id) {
    try {
      const status = await deleteLanGatewayKey(id)
      set({ status, error: null })
    } catch (failure) {
      set({ error: messageOf(failure) })
    }
  },

  async clearCalls() {
    try {
      await clearLanGatewayCalls()
      set({ calls: [], error: null })
    } catch (failure) {
      set({ error: messageOf(failure) })
    }
  },
}))

function messageOf(failure: unknown): string {
  return failure instanceof Error ? failure.message : String(failure)
}

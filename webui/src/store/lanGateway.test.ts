import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { LanGatewayStatus } from '../api/types'
import { buildClientSnippet, useLanGateway } from './lanGateway'

vi.mock('../api/client', () => ({
  fetchLanGatewayStatus: vi.fn(),
  fetchLanGatewayCalls: vi.fn(),
  updateLanGatewaySettings: vi.fn(),
  createLanGatewayKey: vi.fn(),
  updateLanGatewayKey: vi.fn(),
  deleteLanGatewayKey: vi.fn(),
  clearLanGatewayCalls: vi.fn(),
}))

import * as client from '../api/client'

const SAMPLE_STATUS: LanGatewayStatus = {
  enabled: true,
  listening_host: '0.0.0.0',
  port: 19845,
  expose_default_provider: false,
  tier_stand_in_model: 'deepseek-chat',
  prevent_mac_sleep: true,
  awake_active: true,
  allowed_cidrs: [],
  endpoints: [
    { kind: 'lan', host: '192.168.1.50', url: 'http://192.168.1.50:19845' },
    { kind: 'mdns', host: 'macbook.local', url: 'http://macbook.local:19845' },
  ],
  available_models: [
    { id: 'deepseek-chat', protocol: 'chat', built_in: false, context_window: 128000 },
  ],
  keys: [
    {
      id: 'gk_1',
      name: 'Windows PC',
      secret: 'sk-poco-0123456789abcdef0123456789abcdef',
      masked_secret: 'sk-poco-…cdef',
      enabled: true,
      allowed_models: [],
      created_at: '2026-11-23T00:00:00Z',
      last_used_at: null,
      total_requests: 3,
      input_tokens: 120,
      output_tokens: 80,
    },
  ],
}

describe('lanGateway store and snippet generator', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    useLanGateway.setState({ status: null, calls: [], loading: false, error: null })
  })

  it('refreshes status and recent calls together', async () => {
    vi.mocked(client.fetchLanGatewayStatus).mockResolvedValue(SAMPLE_STATUS)
    vi.mocked(client.fetchLanGatewayCalls).mockResolvedValue({
      calls: [
        {
          id: 1,
          timestamp: '2026-11-23T00:01:00Z',
          key_id: 'gk_1',
          key_name: 'Windows PC',
          client_ip: '192.168.1.88',
          source_protocol: 'anthropic',
          target_protocol: 'chat',
          requested_model: 'deepseek-chat',
          resolved_model: 'deepseek-chat',
          streaming: true,
          status: 200,
          ttft_ms: 42,
          duration_ms: 310,
          input_tokens: 50,
          output_tokens: 30,
          error: null,
        },
      ],
    })

    await useLanGateway.getState().refresh()

    const state = useLanGateway.getState()
    expect(state.status?.listening_host).toBe('0.0.0.0')
    expect(state.status?.keys).toHaveLength(1)
    expect(state.calls).toHaveLength(1)
    expect(state.error).toBeNull()
  })

  it('updates settings and manages device keys', async () => {
    vi.mocked(client.updateLanGatewaySettings).mockResolvedValue({
      ...SAMPLE_STATUS,
      expose_default_provider: true,
    })
    await useLanGateway.getState().updateSettings({ exposeDefaultProvider: true })
    expect(useLanGateway.getState().status?.expose_default_provider).toBe(true)

    vi.mocked(client.createLanGatewayKey).mockResolvedValue(SAMPLE_STATUS)
    await useLanGateway.getState().createKey('Ubuntu Server', ['deepseek-chat'])
    expect(client.createLanGatewayKey).toHaveBeenCalledWith({
      name: 'Ubuntu Server',
      allowedModels: ['deepseek-chat'],
    })
  })

  it('builds Windows PowerShell, Linux Bash, and Poco model.json snippets cleanly', () => {
    const psClaude = buildClientSnippet({
      baseUrl: 'http://192.168.1.50:19845/',
      apiKey: 'sk-poco-test1234',
      modelName: 'deepseek-chat',
      client: 'claude-code',
      platform: 'powershell',
    })
    expect(psClaude).toContain('$env:ANTHROPIC_BASE_URL="http://192.168.1.50:19845"')
    expect(psClaude).toContain('$env:ANTHROPIC_AUTH_TOKEN="sk-poco-test1234"')

    const bashCodex = buildClientSnippet({
      baseUrl: 'http://macbook.local:19845',
      apiKey: 'sk-poco-test1234',
      modelName: 'gemini-2.5-flash',
      client: 'codex',
      platform: 'bash',
    })
    expect(bashCodex).toContain('export OPENAI_BASE_URL="http://macbook.local:19845/v1"')
    expect(bashCodex).toContain('codex --model gemini-2.5-flash')

    const pocoJson = JSON.parse(buildClientSnippet({
      baseUrl: 'http://192.168.1.50:19845',
      apiKey: 'sk-poco-test1234',
      modelName: 'deepseek-chat',
      client: 'poco',
      platform: 'powershell',
    })) as { models: { baseUrl: string; apiKey: string }[] }
    expect(pocoJson.models[0].baseUrl).toBe('http://192.168.1.50:19845')
    expect(pocoJson.models[0].apiKey).toBe('sk-poco-test1234')
  })
})

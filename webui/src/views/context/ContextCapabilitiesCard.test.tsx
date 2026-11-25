// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ContextCapabilitiesCard } from './ContextCapabilitiesCard'
import { useContextKit } from './useContextKit'
import { useCapabilities } from '../../store/capabilities'
import { useLocale } from '../../store/locale'
import type { ModelProtocol, SessionCapabilities, SessionCapabilityToggleRequest } from '../../api/types'

declare global {
  // eslint-disable-next-line no-var -- React's act() environment flag
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined
}

let currentSnap: SessionCapabilities
const toggleCalls: SessionCapabilityToggleRequest[] = []

vi.mock('../../api/client', () => ({
  fetchSessionCapabilities: vi.fn(async () => ({ capabilities: currentSnap })),
  toggleSessionCapability: vi.fn(async (req: SessionCapabilityToggleRequest) => {
    toggleCalls.push(req)
    if (req.kind === 'skill' && req.name === 'deploy-skill') {
      currentSnap = {
        ...currentSnap,
        skills: currentSnap.skills.map((s) =>
          s.name === 'deploy-skill'
            ? {
                ...s,
                session_enabled: req.enabled,
                default_enabled: req.scope === 'default' ? req.enabled : s.default_enabled,
              }
            : s),
      }
    }
    return { changed: true, message: 'ok', capabilities: currentSnap }
  }),
}))

function Harness({ sessionId }: { sessionId: string }) {
  const { kit } = useContextKit()
  return <ContextCapabilitiesCard sessionId={sessionId} kit={kit} />
}

let container: HTMLDivElement
let root: Root

async function flushAsync() {
  await act(async () => {
    await Promise.resolve()
    await Promise.resolve()
  })
}

function getByTestId(testId: string): HTMLElement {
  const el = container.querySelector<HTMLElement>(`[data-testid="${testId}"]`)
  if (!el) throw new Error(`Missing element with data-testid="${testId}"`)
  return el
}

function buildSnapshot(sessionId: string, protocol: ModelProtocol, model: string): SessionCapabilities {
  return {
    session_id: sessionId,
    model,
    protocol,
    saved_tokens: 1420,
    tools: [
      {
        name: 'ToolInvoke',
        description: 'Kernel deferred invocation bridge',
        input_schema: {
          type: 'object',
          properties: {
            tool_name: { type: 'string', description: 'Target tool name' },
          },
          required: ['tool_name'],
        },
        tokens: 90,
        kernel: true,
        session_enabled: true,
        default_enabled: true,
        delivery_mode: 'prefix_eager',
      },
      {
        name: 'WebSearch',
        description: 'Searches live web results',
        input_schema: {
          type: 'object',
          properties: {
            query: { type: 'string', description: 'Search query' },
          },
          required: ['query'],
        },
        tokens: 320,
        kernel: false,
        session_enabled: true,
        default_enabled: true,
        delivery_mode: 'progressive_deferred',
      },
    ],
    skills: [
      {
        name: 'deploy-skill',
        source: 'project',
        description: 'Full multi-step deployment workflow description',
        tokens: 45,
        session_enabled: true,
        default_enabled: true,
        protocol_visible: true,
      },
    ],
    mcp_servers: [
      {
        name: 'github',
        scope: 'project',
        transport: 'stdio',
        status: 'connected',
        instructions: 'Use github MCP tools to inspect PRs',
        tokens: 965,
        manageable: true,
        session_enabled: true,
        default_enabled: true,
        tools: [
          {
            server_name: 'github',
            name: 'create_issue',
            full_name: 'mcp__github__create_issue',
            description: 'Creates a GitHub issue in repository',
            input_schema: { type: 'object', properties: { title: { type: 'string' } }, required: ['title'] },
            tokens: 180,
            session_enabled: true,
            default_enabled: true,
          },
        ],
      },
    ],
  }
}

describe('ContextCapabilitiesCard', () => {
  beforeEach(() => {
    globalThis.IS_REACT_ACT_ENVIRONMENT = true
    window.localStorage.clear()
    toggleCalls.length = 0
    act(() => {
      useCapabilities.setState({
        bySession: {},
        loadingBySession: {},
        pendingKeys: {},
        scope: 'session',
        lastError: null,
      })
    })
    container = document.createElement('div')
    document.body.append(container)
    root = createRoot(container)
  })

  afterEach(() => {
    act(() => { root.unmount() })
    container.remove()
  })

  it('recognizes all 4 protocols (anthropic, chat, responses, gemini)', async () => {
    const cases: Array<[ModelProtocol, string, string]> = [
      ['anthropic', 'claude-haiku-4-5', 'Anthropic Messages · claude-haiku-4-5'],
      ['chat', 'glm-5.3', 'OpenAI Chat · glm-5.3'],
      ['responses', 'glm-5.3', 'OpenAI Responses · glm-5.3'],
      ['gemini', 'gemini-2.5-flash', 'Google Gemini · gemini-2.5-flash'],
    ]

    for (const [protocol, model, expectedLabel] of cases) {
      const sid = `sess-${protocol}`
      currentSnap = buildSnapshot(sid, protocol, model)
      await act(async () => {
        root.render(<Harness sessionId={sid} />)
      })
      await flushAsync()
      expect(getByTestId('context-capabilities-card').textContent).toContain(expectedLabel)
    }
  })

  it('expands Tool/Skill/MCP groups, inspects Description, and toggles session vs default switches', async () => {
    currentSnap = buildSnapshot('sess-cap-1', 'responses', 'glm-5.3')

    await act(async () => {
      root.render(<Harness sessionId="sess-cap-1" />)
    })
    await flushAsync()

    expect(getByTestId('context-capabilities-card').textContent).toContain('Tools · MCP · Skill')
    expect(getByTestId('context-capabilities-card').textContent).toContain('OpenAI Responses · glm-5.3')
    act(() => { useLocale.getState().setLocale('zh') })
    expect(getByTestId('context-capabilities-card').textContent).toContain('工具 · MCP · Skill')
    act(() => { useLocale.getState().setLocale('en') })

    // Expand Skill group, select deploy-skill, verify Description on right pane
    act(() => { getByTestId('caps-group-skill').click() })
    const skillRow = getByTestId('caps-skill-row-deploy-skill')
    act(() => { skillRow.querySelector<HTMLButtonElement>('button')?.click() })
    expect(getByTestId('caps-detail-card').textContent).toContain(
      'Full multi-step deployment workflow description',
    )

    // Toggle switch in default session scope -> sends scope: 'session'
    act(() => { skillRow.querySelector<HTMLElement>('[role="switch"]')?.click() })
    await flushAsync()
    expect(toggleCalls).toHaveLength(1)
    expect(toggleCalls[0]).toMatchObject({
      session_id: 'sess-cap-1',
      kind: 'skill',
      name: 'deploy-skill',
      enabled: false,
      scope: 'session',
    })

    // Switch mode to Default (Persisted): default_enabled is still true, so clicking persists false
    act(() => { getByTestId('caps-scope-default').click() })
    act(() => { getByTestId('caps-skill-row-deploy-skill').querySelector<HTMLElement>('[role="switch"]')?.click() })
    await flushAsync()
    expect(toggleCalls).toHaveLength(2)
    expect(toggleCalls[1]).toMatchObject({
      session_id: 'sess-cap-1',
      kind: 'skill',
      name: 'deploy-skill',
      enabled: false,
      scope: 'default',
    })

    // Expand MCP group and inspect nested MCP tool description
    act(() => { getByTestId('caps-group-mcp').click() })
    const mcpToolRow = getByTestId('caps-mcp-tool-row-mcp__github__create_issue')
    act(() => { mcpToolRow.querySelector<HTMLButtonElement>('button')?.click() })
    expect(getByTestId('caps-detail-card').textContent).toContain(
      'Creates a GitHub issue in repository',
    )
  })
})

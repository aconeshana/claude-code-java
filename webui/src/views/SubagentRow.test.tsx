import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ToolCallState, ToolProgressEntry } from '../store/conversations'
import { resetAgentTranscripts } from '../store/agentTranscript'
import { detailFetcher } from '../store/contextTimeline'
import { useLocale } from '../store/locale'
import { useSubagentFocus } from '../store/subagentFocus'
import { detailStoreOf, resetTimelineDetailStores } from '@dsh-context/client/timelineSource'
import { SubagentRow, isSubagentCall } from './SubagentRow'

declare global {
  // eslint-disable-next-line no-var -- React's act() environment flag
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined
}

let container: HTMLDivElement
let root: Root

beforeEach(() => {
  globalThis.IS_REACT_ACT_ENVIRONMENT = true
  // Pin the locale: the assertions below read the Chinese copy, and the
  // store otherwise detects from `navigator.languages`.
  useLocale.setState({ locale: 'zh' })
  sessionStorage.setItem('gateway-token', 'fake-token')
  resetAgentTranscripts()
  resetTimelineDetailStores()
  useSubagentFocus.getState().clear()
  container = document.createElement('div')
  document.body.append(container)
  root = createRoot(container)
})

afterEach(() => {
  act(() => { root.unmount() })
  container.remove()
  vi.unstubAllGlobals()
  sessionStorage.clear()
})

/**
 * Publish one subagent record through the real detail conduit — the same
 * `DetailStore` fetch the header catalog drives — so a row under test resolves
 * its agent id the way it does in the app, not through a back door.
 *
 * `record.id` is the launching tool-use id; `record.agentId` is the id the
 * gateway keys the transcript by. They are deliberately different here: the
 * whole point of the lookup is that the row must translate between them.
 */
async function seedAgentRecord(
  id: string,
  agentId: string,
  sessionId = 's1',
): Promise<void> {
  const payload = {
    detail: {
      rev: 1,
      head: { detailRev: 1 },
      agents: [{ id, seq: 1, agentId, type: 'general-purpose', status: 'done' }],
      nodes: [],
      events: [],
    },
  }
  const seen = vi.fn().mockResolvedValue(
    new Response(JSON.stringify(payload), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }),
  )
  const original = globalThis.fetch
  vi.stubGlobal('fetch', seen)
  const store = detailStoreOf(sessionId, detailFetcher)
  store.request(1)
  // The store debounces its read by 300ms; wait past it rather than racing.
  for (let attempt = 0; attempt < 60; attempt += 1) {
    if (seen.mock.calls.length > 0) break
    await new Promise((resolve) => { setTimeout(resolve, 25) })
  }
  for (let attempt = 0; attempt < 5; attempt += 1) {
    await new Promise((resolve) => { setTimeout(resolve, 0) })
  }
  // Restore the harness's own fetch so the test body installs its stub on a
  // clean base.
  vi.stubGlobal('fetch', original)
}

function call(overrides: Partial<ToolCallState> = {}): ToolCallState {
  return {
    toolUseId: 'tu-task',
    name: 'Task',
    args: { description: '审查路由配置', prompt: '检查所有路由的鉴权中间件' },
    status: 'pending',
    ready: false,
    resultData: null,
    resultType: null,
    resultError: null,
    transcriptPath: null,
    locations: null,
    progress: [],
    ...overrides,
  }
}

function entry(overrides: Partial<ToolProgressEntry> = {}): ToolProgressEntry {
  return { kind: 'agent_progress', agentId: 'a1', ...overrides }
}

async function render(next: ToolCallState, defaultOpen = true, sessionId: string | null = 's1'): Promise<void> {
  await act(async () => {
    root.render(<SubagentRow call={next} sessionId={sessionId} defaultOpen={defaultOpen} />)
  })
}

function row(): HTMLElement {
  const found = container.querySelector<HTMLElement>('[data-tool="Task"]')
  if (found === null) throw new Error('no subagent row rendered')
  return found
}

/** The "view the full conversation" toggle. */
function transcriptToggle(): HTMLButtonElement {
  const found = Array.from(container.querySelectorAll('button'))
    .find((button) => button.textContent?.includes('查看完整对话'))
  if (found === undefined) throw new Error('no transcript toggle rendered')
  return found as HTMLButtonElement
}

/** Click the toggle and let the resulting fetch settle. */
async function openTranscript(): Promise<void> {
  await act(async () => {
    transcriptToggle().click()
    for (let attempt = 0; attempt < 5; attempt += 1) {
      await new Promise((resolve) => { setTimeout(resolve, 0) })
    }
  })
}

describe('isSubagentCall', () => {
  it('claims the delegation tool under both of its registered names', () => {
    expect(isSubagentCall(call({ name: 'Task' }))).toBe(true)
    expect(isSubagentCall(call({ name: 'Agent' }))).toBe(true)
  })

  it('leaves the control-plane siblings as ordinary tool calls', () => {
    // SendMessage/TaskList act on subagents but are not delegations; the
    // turn-level fold's subagentCount splits on the same rule, so the row
    // and the count must agree.
    expect(isSubagentCall(call({ name: 'SendMessage' }))).toBe(false)
    expect(isSubagentCall(call({ name: 'TaskList' }))).toBe(false)
    expect(isSubagentCall(call({ name: 'Bash' }))).toBe(false)
  })
})

describe('SubagentRow', () => {
  // The status dot lives in the disclosure's leading slot, which the shared
  // chrome swaps for a chevron while the row is OPEN — so these read the
  // collapsed row, which is also where the dot is what the reader sees.
  it('leads with the call description and shows the running state', async () => {
    await render(call(), false)
    expect(row().textContent).toContain('审查路由配置')
    expect(row().querySelector('[data-state="ongoing"]')).not.toBeNull()
  })

  it('falls back to the subagent type when the call carries no description', async () => {
    await render(call({ args: { subagent_type: 'code-reviewer', prompt: 'x' } }), false)
    expect(row().textContent).toContain('code-reviewer')
  })

  it('shows an error dot on a failed call', async () => {
    await render(call({ status: 'failed', resultError: 'agent crashed' }), false)
    expect(row().querySelector('[data-state="error"]')).not.toBeNull()
  })

  it('shows a done dot on a settled call', async () => {
    await render(call({ status: 'executed', ready: true, resultData: 'summary' }), false)
    expect(row().querySelector('[data-state="done"]')).not.toBeNull()
  })

  it('carries the run state for assistive technology, which the dot cannot', async () => {
    // The dot is aria-hidden and the description says nothing about the
    // outcome, so the composed aria-label is the only place the state
    // reaches a screen reader.
    await render(call({ status: 'executed', ready: true, resultData: 's' }), false)
    expect(row().getAttribute('aria-label')).toContain('已完成')
    await render(call({ status: 'failed' }), false)
    expect(row().getAttribute('aria-label')).toContain('已失败')
    await render(call(), false)
    expect(row().getAttribute('aria-label')).toContain('正在运行')
  })

  it('renders the prompt and one block per progress message', async () => {
    await render(call({
      progress: [
        entry({ content: '先读路由表' }),
        entry({ message: '3/9' }),
        entry({ content: '检查中间件' }),
      ],
    }))
    expect(container.textContent).toContain('检查所有路由的鉴权中间件')
    expect(container.textContent).toContain('先读路由表')
    expect(container.textContent).toContain('检查中间件')
    // The status line is the producer's own note, not the sub-agent's words.
    expect(container.textContent).toContain('3/9')
  })

  it('shows the settled result when no live progress survived', async () => {
    // A reloaded transcript is rebuilt from a snapshot that carries no
    // progress, so a finished subagent would otherwise read "waiting for the
    // subagent" — a false statement about a call that has already answered.
    // Its result is what the subagent produced and is shown in its place.
    await render(call({
      status: 'executed',
      ready: true,
      resultData: 'reviewed 12 routes, 2 missing auth',
    }))
    expect(container.textContent).toContain('reviewed 12 routes, 2 missing auth')
    expect(container.textContent).not.toContain('等待子智能体输出')
  })

  it('says there is no live progress rather than waiting, on a settled call with no result', async () => {
    await render(call({ status: 'executed', ready: true, resultData: null }))
    expect(container.textContent).toContain('没有可显示的实时进度')
    expect(container.textContent).not.toContain('等待子智能体输出')
  })

  it('still says waiting while the call is genuinely running', async () => {
    await render(call({ status: 'pending' }))
    expect(container.textContent).toContain('等待子智能体输出')
  })

  it('keeps a status line distinguishable from subagent text', async () => {
    await render(call({
      progress: [entry({ content: 'words' }), entry({ message: 'progress note' })],
    }))
    const lines = container.querySelectorAll('[data-role]')
    expect(Array.from(lines).map((line) => line.getAttribute('data-role')))
      .toEqual(['message', 'status'])
  })

  it('renders an activity line as itself, with no label gutter or line numbers', async () => {
    // Each activity is one line saying what the child is doing. Rendering it
    // through a file card put the agent id and a line number in front of every
    // line, so the row read as `a13b3868 复制 1 Copy(...)` — the id is the
    // row's identity and a single activity is not a numbered file.
    await render(call({
      progress: [entry({ agentId: 'a13b38686193649bd', content: 'Bash(find . -type f)' })],
    }))
    const line = container.querySelector('[data-role="message"]')
    expect(line?.textContent).toBe('Bash(find . -type f)')
    expect(container.textContent).not.toContain('a13b38686193649bd')
  })

  it('tells the reader the agent has not spoken yet rather than showing nothing', async () => {
    await render(call())
    expect(container.textContent).toContain('等待子智能体输出')
  })

  it('collapses the body but keeps the description in the summary line', async () => {
    await render(call({ progress: [entry({ content: 'hidden' })] }), false)
    expect(row().textContent).toContain('审查路由配置')
    expect(container.textContent).not.toContain('hidden')
  })
})

describe('SubagentRow restored transcript', () => {
  const TRANSCRIPT = {
    session_id: 's1',
    agent_id: 'a1',
    messages: [
      { id: 'u1', role: 'user', complete: true, text: '检查所有路由的鉴权中间件' },
      { id: 'a2', role: 'assistant', complete: true, content: [{ type: 'text', text: '发现两处缺失' }] },
    ],
  }

  function jsonResponse(body: unknown): Response {
    return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }

  it('does not fetch until the reader asks for the conversation', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(TRANSCRIPT))
    vi.stubGlobal('fetch', fetchMock)
    await render(call({ progress: [entry({ agentId: 'a1' })] }))
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('fetches the agent the progress frames named and renders its rows', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(TRANSCRIPT))
    vi.stubGlobal('fetch', fetchMock)
    await render(call({ progress: [entry({ agentId: 'a1', content: 'working' })] }))
    await openTranscript()

    const [url] = fetchMock.mock.calls[0] as [string]
    expect(url).toContain('/api/sessions/s1/agents/a1/transcript')
    expect(container.querySelector('[data-role="transcript"]')).not.toBeNull()
    expect(container.textContent).toContain('发现两处缺失')
  })

  it('resolves the agent id from the session ledger when no frame carried one', async () => {
    // A reloaded transcript is rebuilt from a snapshot that carries no
    // progress, so the live frames are gone. The detail ledger still knows
    // the agent, and its `agentId` is what the transcript is keyed by — the
    // launching tool-use id is a different id space and the gateway answers
    // `unknown agent` for it.
    await seedAgentRecord('tu-task', 'a7')
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(TRANSCRIPT))
    vi.stubGlobal('fetch', fetchMock)
    await render(call())
    await openTranscript()

    const [url] = fetchMock.mock.calls[0] as [string]
    expect(url).toContain('/api/sessions/s1/agents/a7/transcript')
  })

  it('prefers the ledger record over a frame that disagrees', async () => {
    // The record is the authoritative spelling; a frame is only a live hint.
    await seedAgentRecord('tu-task', 'a7')
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(TRANSCRIPT))
    vi.stubGlobal('fetch', fetchMock)
    await render(call({ progress: [entry({ agentId: 'stale' })] }))
    await openTranscript()

    const [url] = fetchMock.mock.calls[0] as [string]
    expect(url).toContain('/api/sessions/s1/agents/a7/transcript')
  })

  it('offers no transcript toggle while no agent id has resolved', async () => {
    // Nothing has answered with an id, so there is nothing to address. A
    // toggle here could only produce an error, and the id it must not fall
    // back to (the tool-use id) would report "unknown agent" about an id that
    // was never an agent id at all.
    await render(call())
    expect(container.textContent).not.toContain('查看完整对话')
  })

  it('offers no transcript control when the row has no session', async () => {
    // Without a session there is no sidechain to read and no id to read it
    // by, so the row shows no control rather than one that can only fail.
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(TRANSCRIPT))
    vi.stubGlobal('fetch', fetchMock)
    await render(call(), true, null)

    expect(fetchMock).not.toHaveBeenCalled()
    expect(container.textContent).not.toContain('查看完整对话')
  })

  it('offers a retry when the read fails, and reads again on click', async () => {
    await seedAgentRecord('tu-task', 'a1')
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(null, { status: 500 }))
      .mockResolvedValueOnce(jsonResponse(TRANSCRIPT))
    vi.stubGlobal('fetch', fetchMock)
    await render(call())
    await openTranscript()
    expect(container.textContent).toContain('无法加载子智能体对话')

    const retry = Array.from(container.querySelectorAll('button'))
      .find((button) => button.textContent?.includes('重试'))
    expect(retry).toBeDefined()
    await act(async () => {
      retry?.click()
      await new Promise((resolve) => { setTimeout(resolve, 0) })
    })
    expect(container.textContent).toContain('发现两处缺失')
  })
})

describe('SubagentRow catalog focus', () => {
  const TRANSCRIPT = {
    session_id: 's1',
    agent_id: 'a1',
    messages: [
      { id: 'u1', role: 'user', complete: true, text: '检查所有路由的鉴权中间件' },
      { id: 'a2', role: 'assistant', complete: true, content: [{ type: 'text', text: '发现两处缺失' }] },
    ],
  }

  function jsonResponse(body: unknown): Response {
    return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }

  /** Publish a catalog pick, then let the row's effect and its fetch settle. */
  async function focus(agentId: string, sessionId = 's1'): Promise<void> {
    await act(async () => {
      useSubagentFocus.getState().open(sessionId, agentId)
      for (let attempt = 0; attempt < 5; attempt += 1) {
        await new Promise((resolve) => { setTimeout(resolve, 0) })
      }
    })
  }

  it('opens the addressed row and its transcript together', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(TRANSCRIPT))
    vi.stubGlobal('fetch', fetchMock)
    await render(call({ progress: [entry({ agentId: 'a1' })] }), false)
    expect(container.textContent).not.toContain('发现两处缺失')

    await focus('a1')

    // A reader who picked this row out of the catalog asked to read the
    // subagent, not merely to see its prompt — so both open.
    const [url] = fetchMock.mock.calls[0] as [string]
    expect(url).toContain('/api/sessions/s1/agents/a1/transcript')
    expect(container.querySelector('[data-role="transcript"]')).not.toBeNull()
    expect(container.textContent).toContain('发现两处缺失')
  })

  it('leaves a row the request did not name alone', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(TRANSCRIPT))
    vi.stubGlobal('fetch', fetchMock)
    await render(call({ progress: [entry({ agentId: 'a1' })] }), false)

    await focus('a9')

    expect(fetchMock).not.toHaveBeenCalled()
    expect(container.querySelector('[data-role="transcript"]')).toBeNull()
  })

  it('claims the request once, so a re-render cannot reopen a closed row', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(TRANSCRIPT))
    vi.stubGlobal('fetch', fetchMock)
    await render(call({ progress: [entry({ agentId: 'a1' })] }), false)
    await focus('a1')
    expect(useSubagentFocus.getState().pending).toBeNull()

    // The reader collapses it; nothing should spring it back open.
    await act(async () => { transcriptToggle().click() })
    expect(container.querySelector('[data-role="transcript"]')).toBeNull()
  })

  it('answers a catalog pick addressed by the agent id, not the launch id', async () => {
    // The catalog addresses a row by `record.agentId`, which is the id the
    // transcript endpoint is keyed by. A row that answered the launching
    // tool-use id instead would never match a pick.
    await seedAgentRecord('tu-task', 'a7')
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(TRANSCRIPT))
    vi.stubGlobal('fetch', fetchMock)
    await render(call(), false)

    await focus('a7')

    const [url] = fetchMock.mock.calls[0] as [string]
    expect(url).toContain('/api/sessions/s1/agents/a7/transcript')
  })

  it('ignores a catalog pick addressed by the launching tool-use id', async () => {
    // The two id spaces are not interchangeable. A pick carrying the launch
    // id names no agent, and expanding a row for it would open a transcript
    // the gateway cannot serve.
    await seedAgentRecord('tu-task', 'a7')
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(TRANSCRIPT))
    vi.stubGlobal('fetch', fetchMock)
    await render(call(), false)

    await focus('tu-task')

    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('answers a request that arrives before the row is rendered', async () => {
    // The catalog sits in the header, above a transcript that mounts after it;
    // a pick must survive that ordering rather than landing on nothing.
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(TRANSCRIPT))
    vi.stubGlobal('fetch', fetchMock)
    await act(async () => { useSubagentFocus.getState().open('s1', 'a1') })

    await render(call({ progress: [entry({ agentId: 'a1' })] }), true)

    expect(container.querySelector('[data-role="transcript"]')).not.toBeNull()
  })
})

describe('formatSubagentDuration', () => {
  // The thresholds are upstream's `formatDuration`; the copy is the vendored
  // dictionary's. Both must hold together or a metric reads wrong at a
  // boundary (e.g. 59.6s rounding to "60秒" instead of rolling to a minute).
  it('uses the seconds form under a minute', async () => {
    const { formatSubagentDuration } = await import('./SubagentRow')
    const t = (key: string, params?: Record<string, string | number>): string =>
      `${key}:${JSON.stringify(params ?? {})}`
    expect(formatSubagentDuration(59_000, t)).toBe('duration.seconds:{"seconds":59}')
  })

  it('pads seconds once minutes are in play', async () => {
    const { formatSubagentDuration } = await import('./SubagentRow')
    const t = (key: string, params?: Record<string, string | number>): string =>
      `${key}:${JSON.stringify(params ?? {})}`
    expect(formatSubagentDuration(65_000, t)).toBe('duration.minutes:{"minutes":1,"seconds":"05"}')
    expect(formatSubagentDuration(3_725_000, t)).toBe('duration.hours:{"hours":1,"minutes":"02","seconds":"05"}')
  })
})

describe('formatSubagentTokens', () => {
  it('keeps one decimal below 100 and rounds above, per upstream', async () => {
    const { formatSubagentTokens } = await import('./SubagentRow')
    const t = (key: string, params?: Record<string, string | number>): string =>
      `${key}:${JSON.stringify(params ?? {})}`
    expect(formatSubagentTokens(999, t)).toBe('999')
    expect(formatSubagentTokens(1_050, t)).toBe('tokens.thousand:{"value":"1.1"}')
    expect(formatSubagentTokens(123_400, t)).toBe('tokens.thousand:{"value":"123"}')
    expect(formatSubagentTokens(2_500_000, t)).toBe('tokens.million:{"value":"2.5"}')
  })
})
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { MessagesSnapshot, MirrorFrame } from '../api/types'
import { useConversations } from './conversations'

vi.mock('../api/client', () => ({ fetchSnapshot: vi.fn() }))

const SESSION = 'sess-1'

function frame(event: MirrorFrame['event'], id: number, data: Record<string, unknown>): MirrorFrame {
  return { event, id, data: { session_id: SESSION, ...data } } as MirrorFrame
}

const store = () => useConversations.getState()

describe('conversations frame reduction', () => {
  beforeEach(() => {
    useConversations.setState({ conversations: {} })
  })

  it('opens an assistant row on output.text and appends full blocks', () => {
    store().applyFrame(frame('output.text', 1, { content: '你好' }))
    store().applyFrame(frame('output.text', 2, { content: '世界' }))
    const conversation = useConversations.getState().conversations[SESSION]
    const last = conversation.messages[conversation.messages.length - 1]
    expect(last).toMatchObject({ kind: 'assistant', textBlocks: ['你好', '世界'] })
  })

  it('collects thinking blocks separately from text', () => {
    store().applyFrame(frame('output.thinking', 1, { content: '让我想想' }))
    store().applyFrame(frame('output.text', 2, { content: '答案' }))
    const conversation = useConversations.getState().conversations[SESSION]
    const last = conversation.messages[conversation.messages.length - 1]
    expect(last).toMatchObject({ kind: 'assistant', thinkingBlocks: ['让我想想'], textBlocks: ['答案'] })
  })

  it('pairs tool.started with tool.completed by tool_use_id', () => {
    store().applyFrame(frame('tool.started', 1, { name: 'Bash', tool_use_id: 'tu-1', input: { command: 'ls' } }))
    store().applyFrame(frame('tool.completed', 2, {
      status: 'completed',
      tool_use_id: 'tu-1',
      result: { type: 'execute_command_tool_result', data: 'a.txt\nb.txt' },
    }))
    const conversation = useConversations.getState().conversations[SESSION]
    const last = conversation.messages[conversation.messages.length - 1]
    expect(last).toMatchObject({
      kind: 'assistant',
      toolCalls: [{
        toolUseId: 'tu-1',
        name: 'Bash',
        // The mirror's "completed" maps to the snapshot vocabulary's
        // "executed" so live and restored rows share one status enum.
        status: 'executed',
        ready: true,
        resultData: 'a.txt\nb.txt',
        resultType: 'execute_command_tool_result',
      }],
    })
  })

  it('keeps a tool call pending until its result frame', () => {
    store().applyFrame(frame('tool.started', 1, { name: 'Read', tool_use_id: 'tu-9', input: {} }))
    const conversation = useConversations.getState().conversations[SESSION]
    const last = conversation.messages[conversation.messages.length - 1]
    expect(last).toMatchObject({
      kind: 'assistant',
      toolCalls: [{ toolUseId: 'tu-9', status: 'pending', ready: false }],
    })
  })

  it('closes the assistant row on turn.completed and clears the spinner', () => {
    store().applyFrame(frame('turn.started', 1, { display_text: 'go', permission_mode: 'ask', origin: 'chat' }))
    store().applyFrame(frame('output.text', 2, { content: 'done' }))
    store().applyFrame(frame('turn.completed', 3, { done: true, elapsed_ms: 10, user_cancel: false }))
    const conversation = useConversations.getState().conversations[SESSION]
    const last = conversation.messages[conversation.messages.length - 1]
    expect(last).toMatchObject({ kind: 'assistant', open: false })
    expect(conversation.turnRunning).toBe(false)
  })

  it('lands the turn.completed delta facts on the closing assistant row', () => {
    store().applyFrame(frame('turn.started', 1, { display_text: 'go', permission_mode: 'ask', origin: 'chat' }))
    store().applyFrame(frame('output.text', 2, { content: 'done' }))
    store().applyFrame(frame('turn.completed', 3, {
      done: true, elapsed_ms: 7_300, user_cancel: false, turn: 4, ttft_ms: 900,
      turn_usage: {
        uncached_input_tokens: 200, output_tokens: 200,
        cache_write_tokens: 100, cache_read_tokens: 900, total_tokens: 1_400,
      },
    }))
    const conversation = useConversations.getState().conversations[SESSION]
    const last = conversation.messages[conversation.messages.length - 1]
    // The turn tail chrome renders from these: the usage pill's buckets,
    // the time pill's wall clock and TTFT, and the turn number for ordering.
    expect(last).toMatchObject({
      kind: 'assistant',
      open: false,
      turn: 4,
      runMs: 7_300,
      ttftMs: 900,
      turnUsage: {
        uncached_input_tokens: 200, output_tokens: 200,
        cache_write_tokens: 100, cache_read_tokens: 900, total_tokens: 1_400,
      },
    })
  })

  it('a turn.completed frame without the delta leaves the row without tail facts', () => {
    store().applyFrame(frame('turn.started', 1, { display_text: 'go', permission_mode: 'ask', origin: 'chat' }))
    store().applyFrame(frame('output.text', 2, { content: 'done' }))
    // No metrics reader (unwired composition): the frame carries only the
    // completion facts; the wall time still lands (it is always present).
    store().applyFrame(frame('turn.completed', 3, { done: true, elapsed_ms: 5, user_cancel: false }))
    const conversation = useConversations.getState().conversations[SESSION]
    const last = conversation.messages[conversation.messages.length - 1]
    expect(last).toMatchObject({ kind: 'assistant', open: false, runMs: 5 })
    if (last.kind !== 'assistant') throw new Error('expected an assistant row')
    expect(last.turnUsage).toBeUndefined()
    expect(last.turn).toBeUndefined()
    expect(last.ttftMs).toBeUndefined()
  })

  it('appends the submitted message immediately on turn.started', () => {
    store().applyFrame(frame('turn.started', 1, { display_text: '你好', permission_mode: 'ask', origin: 'chat' }))
    const conversation = useConversations.getState().conversations[SESSION]
    expect(conversation.messages).toHaveLength(1)
    expect(conversation.messages[0]).toMatchObject({ kind: 'user', text: '你好' })
  })

  it('threads epoch time onto user rows and the closing assistant tail', () => {
    // The clock-label fact: turn.started stamps the user row, turn.completed
    // stamps the closing assistant row (the turn-tail clock renders from it).
    store().applyFrame(frame('turn.started', 1, {
      display_text: '几点了', permission_mode: 'ask', origin: 'chat', time: 1_700_000_000_000,
    }))
    store().applyFrame(frame('output.text', 2, { content: '刚刚' }))
    store().applyFrame(frame('turn.completed', 3, {
      done: true, elapsed_ms: 5, user_cancel: false, time: 1_700_000_005_000,
    }))
    const conversation = useConversations.getState().conversations[SESSION]
    const user = conversation.messages[0]
    const last = conversation.messages[conversation.messages.length - 1]
    expect(user).toMatchObject({ kind: 'user', time: 1_700_000_000_000 })
    expect(last).toMatchObject({ kind: 'assistant', open: false, time: 1_700_000_005_000 })
  })

  it('keeps a user row without a frame time clockless', () => {
    // An older gateway (or a synthetic frame) carries no time: the row stays
    // clockless and the chrome omits the label, not a placeholder.
    store().applyFrame(frame('turn.started', 1, { display_text: 'go', permission_mode: 'ask', origin: 'chat' }))
    const conversation = useConversations.getState().conversations[SESSION]
    expect(conversation.messages[0]).toMatchObject({ kind: 'user' })
    expect((conversation.messages[0] as { time?: number }).time).toBeUndefined()
  })

  it('tracks the running spinner between turn.started and completion', () => {
    store().applyFrame(frame('turn.started', 1, { display_text: 'go', permission_mode: 'ask', origin: 'chat' }))
    expect(useConversations.getState().conversations[SESSION].turnRunning).toBe(true)
    store().applyFrame(frame('turn.completed', 2, { done: true, elapsed_ms: 1, user_cancel: false }))
    expect(useConversations.getState().conversations[SESSION].turnRunning).toBe(false)
  })

  it('drops journal replay overlaps by frame id', () => {
    store().applyFrame(frame('output.text', 5, { content: '第一帧' }))
    store().applyFrame(frame('output.text', 5, { content: '重复帧' }))
    store().applyFrame(frame('output.text', 3, { content: '旧帧' }))
    const conversation = useConversations.getState().conversations[SESSION]
    const last = conversation.messages[conversation.messages.length - 1]
    expect(last).toMatchObject({ kind: 'assistant', textBlocks: ['第一帧'] })
    expect(conversation.lastFrameId).toBe(5)
  })

  it('records a failed tool result with its error message', () => {
    store().applyFrame(frame('tool.started', 1, { name: 'Bash', tool_use_id: 'tu-2' }))
    store().applyFrame(frame('tool.completed', 2, {
      status: 'failed',
      tool_use_id: 'tu-2',
      result: { type: 'execute_command_tool_result', data: null, errorMessage: 'boom', errorCode: 'tool_error' },
    }))
    const conversation = useConversations.getState().conversations[SESSION]
    const last = conversation.messages[conversation.messages.length - 1]
    expect(last).toMatchObject({
      kind: 'assistant',
      toolCalls: [{ status: 'failed', ready: true, resultError: 'boom' }],
    })
  })

  it('keeps sessions isolated by id', () => {
    store().applyFrame(frame('output.text', 1, { content: 'A' }))
    useConversations.getState().applyFrame({ event: 'output.text', id: 1, data: { session_id: 'sess-2', content: 'B' } })
    const conversations = useConversations.getState().conversations
    expect(conversations['sess-1'].messages).toHaveLength(1)
    expect(conversations['sess-2'].messages).toHaveLength(1)
  })

  it('records a session error and stops the spinner', () => {
    store().applyFrame(frame('turn.started', 1, { display_text: 'go', permission_mode: 'ask', origin: 'chat' }))
    store().applyFrame(frame('session.error', 2, { message: 'turn failed' }))
    const conversation = useConversations.getState().conversations[SESSION]
    expect(conversation.turnRunning).toBe(false)
    expect(conversation.lastError).toBe('turn failed')
  })

  it('opens a fresh assistant row after a completed one', () => {
    store().applyFrame(frame('output.text', 1, { content: '第一轮' }))
    store().applyFrame(frame('turn.completed', 2, { done: true, elapsed_ms: 1, user_cancel: false }))
    store().applyFrame(frame('turn.started', 3, { display_text: 'again', permission_mode: 'ask', origin: 'chat' }))
    store().applyFrame(frame('output.text', 4, { content: '第二轮' }))
    const conversation = useConversations.getState().conversations[SESSION]
    expect(conversation.messages).toHaveLength(3)
    expect(conversation.messages[0]).toMatchObject({ kind: 'assistant', open: false })
    expect(conversation.messages[1]).toMatchObject({ kind: 'user', text: 'again' })
    expect(conversation.messages[2]).toMatchObject({ kind: 'assistant', textBlocks: ['第二轮'], open: true })
  })

  it('threads a tool.completed result\'s locations into the tool call', () => {
    store().applyFrame(frame('tool.started', 1, { name: 'Write', tool_use_id: 'tu-3' }))
    store().applyFrame(frame('tool.completed', 2, {
      status: 'completed',
      tool_use_id: 'tu-3',
      result: {
        type: 'replace_in_file_tool_result',
        data: 'File created successfully',
        locations: ['/work/new-file.txt'],
      },
    }))
    const conversation = useConversations.getState().conversations[SESSION]
    const last = conversation.messages[conversation.messages.length - 1]
    expect(last).toMatchObject({
      kind: 'assistant',
      toolCalls: [{ toolUseId: 'tu-3', locations: ['/work/new-file.txt'] }],
    })
  })

  it('leaves locations null for a tool.completed result without any', () => {
    store().applyFrame(frame('tool.started', 1, { name: 'Bash', tool_use_id: 'tu-4' }))
    store().applyFrame(frame('tool.completed', 2, {
      status: 'completed',
      tool_use_id: 'tu-4',
      result: { type: 'execute_command_tool_result', data: 'ok' },
    }))
    const conversation = useConversations.getState().conversations[SESSION]
    const last = conversation.messages[conversation.messages.length - 1]
    expect(last).toMatchObject({
      kind: 'assistant',
      toolCalls: [{ toolUseId: 'tu-4', locations: null }],
    })
  })

  it('threads a snapshot tool result\'s locations into the tool call', async () => {
    const { fetchSnapshot } = await import('../api/client')
    const snapshot: MessagesSnapshot = {
      session_id: SESSION,
      messages: [{
        id: 'm-1',
        role: 'assistant',
        complete: true,
        content: [{
          type: 'tool_call',
          tool: {
            tool_use_id: 'tu-5',
            name: 'Edit',
            args: null,
            status: 'executed',
            ready: true,
            result: {
              type: 'replace_in_file_tool_result',
              data: 'the file has been updated',
              locations: ['/work/existing-file.txt'],
            },
          },
        }],
      }],
    }
    vi.mocked(fetchSnapshot).mockResolvedValue(snapshot)
    await store().loadSnapshot(SESSION)
    const conversation = useConversations.getState().conversations[SESSION]
    expect(conversation.messages[0]).toMatchObject({
      kind: 'assistant',
      toolCalls: [{ toolUseId: 'tu-5', locations: ['/work/existing-file.txt'] }],
    })
  })

  it('threads a snapshot assistant entry turn and usage facts into the row', async () => {
    const { fetchSnapshot } = await import('../api/client')
    const snapshot: MessagesSnapshot = {
      session_id: SESSION,
      messages: [{
        id: 'm-2',
        role: 'assistant',
        complete: true,
        turn: 2,
        turn_usage: {
          uncached_input_tokens: 1_200, output_tokens: 900,
          cache_write_tokens: 300, cache_read_tokens: 9_800, total_tokens: 12_200,
        },
        content: [{ type: 'text', text: '回答完毕' }],
      }],
    }
    vi.mocked(fetchSnapshot).mockResolvedValue(snapshot)
    await store().loadSnapshot(SESSION)
    const conversation = useConversations.getState().conversations[SESSION]
    // The restored tail chrome renders from these: no live frame will ever
    // revisit a snapshot row, so the snapshot must carry the whole tail.
    expect(conversation.messages[0]).toMatchObject({
      kind: 'assistant',
      turn: 2,
      turnUsage: {
        uncached_input_tokens: 1_200, output_tokens: 900,
        cache_write_tokens: 300, cache_read_tokens: 9_800, total_tokens: 12_200,
      },
    })
  })

  it('threads snapshot epoch time onto user and assistant rows', async () => {
    // Both row kinds stamp `time` — the user row's leading clock and the
    // assistant tail's trailing clock render from the snapshot alone.
    const { fetchSnapshot } = await import('../api/client')
    const snapshot: MessagesSnapshot = {
      session_id: SESSION,
      messages: [
        { id: 'm-1', role: 'user', complete: true, text: '几点问的', time: 1_700_000_000_000 },
        {
          id: 'm-2',
          role: 'assistant',
          complete: true,
          time: 1_700_000_005_000,
          content: [{ type: 'text', text: '刚刚' }],
        },
      ],
    }
    vi.mocked(fetchSnapshot).mockResolvedValue(snapshot)
    await store().loadSnapshot(SESSION)
    const conversation = useConversations.getState().conversations[SESSION]
    expect(conversation.messages[0]).toMatchObject({ kind: 'user', time: 1_700_000_000_000 })
    expect(conversation.messages[1]).toMatchObject({ kind: 'assistant', time: 1_700_000_005_000 })
  })
})

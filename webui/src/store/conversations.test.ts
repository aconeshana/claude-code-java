import { beforeEach, describe, expect, it } from 'vitest'
import type { MirrorFrame } from '../api/types'
import { useConversations } from './conversations'

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

  it('appends the submitted message immediately on turn.started', () => {
    store().applyFrame(frame('turn.started', 1, { display_text: '你好', permission_mode: 'ask', origin: 'chat' }))
    const conversation = useConversations.getState().conversations[SESSION]
    expect(conversation.messages).toHaveLength(1)
    expect(conversation.messages[0]).toMatchObject({ kind: 'user', text: '你好' })
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
})

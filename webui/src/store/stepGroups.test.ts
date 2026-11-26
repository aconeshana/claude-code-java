import { describe, expect, it } from 'vitest'
import { useConversations, type MessageState, type ToolCallState } from './conversations'
import { buildChatFlowEntries } from './stepGroups'
import { processTitle } from '@vendor/dsh-step-process/stepProcess'
import { stepProcessZh, stepProcessEn } from '@vendor/dsh-step-process/locales'

function makeT(dict: Record<string, string>) {
  return (key: string, params?: Readonly<Record<string, string | number>>): string => {
    let text = dict[key] ?? key
    if (params) {
      for (const [k, v] of Object.entries(params)) {
        text = text.replaceAll(`{${k}}`, String(v))
      }
    }
    return text
  }
}

function bashCall(id: string, command: string, description?: string, status: ToolCallState['status'] = 'executed'): ToolCallState {
  return {
    toolUseId: id,
    name: 'Bash',
    args: description !== undefined ? { command, description } : { command },
    status,
    ready: status !== 'pending',
    resultData: status === 'executed' ? 'ok' : null,
    resultType: 'execute_command_tool_result',
    resultError: null,
    transcriptPath: null,
    locations: null,
    progress: [],
  }
}

describe('buildChatFlowEntries', () => {
  it('groups 13 consecutive Bash tool steps into one step-process group with description detail', () => {
    const messages: MessageState[] = [
      { kind: 'user', id: 'u1', text: '排查小程序接口' },
      {
        kind: 'assistant',
        id: 'a0',
        textBlocks: ['先确认一件事：小程序现在到底调哪个接口？'],
        thinkingBlocks: [],
        toolCalls: [],
        open: false,
        turn: 1,
      },
      ...Array.from({ length: 13 }, (_, idx): MessageState => ({
        kind: 'assistant',
        id: `a-bash-${idx}`,
        textBlocks: [],
        thinkingBlocks: [],
        toolCalls: [bashCall(`tu-${idx}`, `ls -la step-${idx}`, `检查第 ${idx + 1} 步配置`, idx === 12 ? 'pending' : 'executed')],
        open: idx === 12,
        turn: 1,
      })),
    ]

    const entries = buildChatFlowEntries(messages, true)
    expect(entries).toHaveLength(3)
    expect(entries[0]).toMatchObject({ kind: 'message', key: 'u1' })
    expect(entries[1]).toMatchObject({ kind: 'message', key: 'a0', part: 'all' })
    expect(entries[2]?.kind).toBe('step-group')

    const group = entries[2]!
    if (group.kind !== 'step-group') throw new Error('expected step-group')
    expect(group.items).toHaveLength(13)
    expect(group.closed).toBe(false)
    expect(group.summary.running).toBe('commands')
    expect(group.summary.runningDetail).toBe('检查第 13 步配置')
    expect(group.summary.totalCalls).toBe(13)
    expect(group.summary.counts).toEqual([{ kind: 'commands', count: 13 }])
  })

  it('keeps intro text visible outside and groups trailing tool calls in the same assistant row', () => {
    const messages: MessageState[] = [
      {
        kind: 'assistant',
        id: 'a1',
        textBlocks: ['先查看目录结构'],
        thinkingBlocks: [],
        toolCalls: [bashCall('tu-1', 'ls -la', '列出工作区文件')],
        open: false,
        turn: 1,
      },
      {
        kind: 'assistant',
        id: 'a2',
        textBlocks: [],
        thinkingBlocks: [],
        toolCalls: [{
          toolUseId: 'tu-2',
          name: 'Read',
          args: { file_path: 'README.md' },
          status: 'executed',
          ready: true,
          resultData: '# readme',
          resultType: 'read_file_tool_result',
          resultError: null,
          transcriptPath: null,
          locations: null,
          progress: [],
        }],
        open: false,
        turn: 1,
      },
    ]

    const entries = buildChatFlowEntries(messages, false)
    expect(entries).toHaveLength(2)
    expect(entries[0]).toMatchObject({ kind: 'message', key: 'a1:response', part: 'response' })
    expect(entries[1]?.kind).toBe('step-group')
    if (entries[1]?.kind !== 'step-group') throw new Error('expected step-group')
    expect(entries[1].closed).toBe(true)
    expect(entries[1].summary.totalCalls).toBe(2)
    expect(processTitle(entries[1].summary, makeT(stepProcessZh))).toBe('执行了命令并已读取文件')
    expect(processTitle(entries[1].summary, makeT(stepProcessEn))).toBe('Ran commands and read files')
  })

  it('composes upstream shared-prefix titles cleanly for Chinese and English', () => {
    const zhT = makeT(stepProcessZh)
    const enT = makeT(stepProcessEn)
    const twoReadSearch = {
      counts: [{ kind: 'read' as const, count: 3 }, { kind: 'search' as const, count: 2 }],
      running: undefined,
      runningDetail: '',
      lastDetail: '',
      totalCalls: 5,
    }
    expect(processTitle(twoReadSearch, zhT)).toBe('已读取文件并搜索代码')
    expect(processTitle(twoReadSearch, enT)).toBe('Read files and searched code')

    const fourKinds = {
      counts: [
        { kind: 'commands' as const, count: 5 },
        { kind: 'read' as const, count: 3 },
        { kind: 'edit' as const, count: 2 },
        { kind: 'search' as const, count: 1 },
      ],
      running: undefined,
      runningDetail: '',
      lastDetail: '',
      totalCalls: 11,
    }
    expect(processTitle(fourKinds, zhT)).toBe('执行了命令，已读取文件，修改了文件等')
    expect(processTitle(fourKinds, enT)).toBe('Ran commands, read files, edited files, etc.')
  })

  it('normalizes remote TUI tool.started stringified input and string tool.completed result', () => {
    useConversations.setState({ conversations: {} })
    const sid = 'remote-tui-session'
    useConversations.getState().applyFrame({
      event: 'turn.started',
      id: 1,
      data: { session_id: sid, display_text: 'check status', permission_mode: 'default', origin: 'tui' },
    })
    useConversations.getState().applyFrame({
      event: 'tool.started',
      id: 2,
      data: {
        session_id: sid,
        name: 'Bash',
        input: JSON.stringify({ command: 'git status', description: '检查仓库状态' }),
      },
    })
    useConversations.getState().applyFrame({
      event: 'tool.completed',
      id: 3,
      data: {
        session_id: sid,
        name: 'Bash',
        status: 'completed',
        result: 'On branch main',
      },
    })

    const conv = useConversations.getState().conversations[sid]
    expect(conv).toBeDefined()
    const assistant = conv?.messages.find((m) => m.kind === 'assistant')
    expect(assistant?.kind).toBe('assistant')
    if (assistant?.kind !== 'assistant') throw new Error('expected assistant')
    expect(assistant.toolCalls).toHaveLength(1)
    expect(assistant.toolCalls[0]?.args).toEqual({ command: 'git status', description: '检查仓库状态' })
    expect(assistant.toolCalls[0]?.status).toBe('executed')
    expect(assistant.toolCalls[0]?.resultData).toBe('On branch main')
    expect(assistant.toolCalls[0]?.resultType).toBe('execute_command_tool_result')
  })

  it('keeps prior Bash commands ABOVE the final conclusion even when remote frames omit message_id', () => {
    useConversations.setState({ conversations: {} })
    const sid = 'remote-order-session'
    let frameId = 1
    useConversations.getState().applyFrame({
      event: 'turn.started',
      id: frameId++,
      data: { session_id: sid, display_text: '排查小程序接口', permission_mode: 'bypassPermissions', origin: 'tui' },
    })
    for (let i = 0; i < 13; i++) {
      useConversations.getState().applyFrame({
        event: 'tool.started',
        id: frameId++,
        data: {
          session_id: sid,
          name: 'Bash',
          input: JSON.stringify({ command: `echo step-${i}`, description: `执行第 ${i + 1} 步检查` }),
        },
      })
      useConversations.getState().applyFrame({
        event: 'tool.completed',
        id: frameId++,
        data: {
          session_id: sid,
          name: 'Bash',
          status: 'completed',
          result: `ok-${i}`,
        },
      })
    }
    useConversations.getState().applyFrame({
      event: 'output.text',
      id: frameId++,
      data: {
        session_id: sid,
        content: '先确认一件事：小程序现在到底调哪个接口？要我走 B 打个补丁吗？',
      },
    })
    useConversations.getState().applyFrame({
      event: 'turn.completed',
      id: frameId++,
      data: { session_id: sid, done: true, elapsed_ms: 5000, user_cancel: false },
    })

    const conv = useConversations.getState().conversations[sid]!
    const entries = buildChatFlowEntries(conv.messages, conv.turnRunning)
    // Order must be: user -> step-group (13 Bash calls) -> final conclusion message!
    expect(entries.map((e) => e.kind)).toEqual(['message', 'step-group', 'message'])
    if (entries[1]?.kind !== 'step-group') throw new Error('expected step-group before conclusion')
    expect(entries[1].summary.totalCalls).toBe(13)
    if (entries[2]?.kind !== 'message' || entries[2].message.kind !== 'assistant') {
      throw new Error('expected final assistant conclusion at the bottom')
    }
    expect(entries[2].message.textBlocks[0]).toContain('先确认一件事')
    expect(entries[2].message.toolCalls).toHaveLength(0)
  })

  it('splits thinking + text + toolCalls in one step into [G1: reasoning] -> response -> [G2: tools]', () => {
    const messages: MessageState[] = [
      {
        kind: 'assistant',
        id: 'a0',
        textBlocks: [],
        thinkingBlocks: ['第一步思考'],
        toolCalls: [bashCall('tu-0', 'pwd', '查看目录')],
        open: false,
        turn: 1,
      },
      {
        kind: 'assistant',
        id: 'a1',
        textBlocks: ['先查到了目录，接下来继续检查配置。'],
        thinkingBlocks: ['第二步思考'],
        toolCalls: [bashCall('tu-1', 'ls -la', '列出文件')],
        open: false,
        turn: 1,
      },
      {
        kind: 'assistant',
        id: 'a2',
        textBlocks: ['全部完成。'],
        thinkingBlocks: [],
        toolCalls: [],
        open: false,
        turn: 1,
      },
    ]

    const entries = buildChatFlowEntries(messages, false)
    // Order: G1 (a0:process + a1:reasoning) -> a1:response -> G2 (a1:tools) -> a2 (all)
    expect(entries.map((e) => e.kind)).toEqual(['step-group', 'message', 'step-group', 'message'])
    const g1 = entries[0]!
    const mid = entries[1]!
    const g2 = entries[2]!
    if (g1.kind !== 'step-group' || g2.kind !== 'step-group' || mid.kind !== 'message') {
      throw new Error('unexpected entry kinds')
    }
    expect(g1.items.map((item) => `${item.message.id}:${item.part}`)).toEqual(['a0:process', 'a1:reasoning'])
    // G1 must count only a0's Bash call, NOT a1's trailing tool call.
    expect(g1.summary.totalCalls).toBe(1)
    expect(mid.key).toBe('a1:response')
    expect(mid.part).toBe('response')
    expect(g2.items.map((item) => `${item.message.id}:${item.part}`)).toEqual(['a1:tools'])
    expect(g2.summary.totalCalls).toBe(1)
    expect(g1.key).not.toBe(g2.key)
  })
})

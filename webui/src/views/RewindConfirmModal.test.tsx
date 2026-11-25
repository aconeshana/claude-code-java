import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const previewRewindMock = vi.fn()
const executeRewindMock = vi.fn()
const submitTurnMock = vi.fn()

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client')
  return {
    ...actual,
    previewRewind: (...args: unknown[]) => previewRewindMock(...args),
    executeRewind: (...args: unknown[]) => executeRewindMock(...args),
    submitTurn: (...args: unknown[]) => submitTurnMock(...args),
  }
})

import { useConversations } from '../store/conversations'
import { useSessions } from '../store/sessions'
import { RewindConfirmModal } from './RewindConfirmModal'

let container: HTMLDivElement
let root: Root

beforeEach(() => {
  ;(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  previewRewindMock.mockReset()
  executeRewindMock.mockReset()
  submitTurnMock.mockReset()
})

afterEach(() => {
  act(() => { root.unmount() })
  container.remove()
})

async function flushPromises(): Promise<void> {
  await act(async () => {
    await new Promise((resolve) => { setTimeout(resolve, 0) })
  })
}

describe('RewindConfirmModal', () => {
  it('shows expandable code diff preview, defaults to Case 1 when code changed, and executes rewind + resend', async () => {
    previewRewindMock.mockResolvedValue({
      can_rewind_conversation: true,
      can_rewind_code: true,
      code_unavailable_reason: null,
      turns_to_drop: 2,
      messages_to_drop: 5,
      insertions: 3,
      deletions: 1,
      files: [
        {
          path: 'src/App.tsx',
          insertions: 3,
          deletions: 1,
          will_delete: false,
          unified_diff: '@@ -1,2 +1,4 @@\n-old\n+new',
        },
      ],
    })
    executeRewindMock.mockResolvedValue({
      rewound_conversation: true,
      rewound_code: true,
      retained_message_count: 2,
      restored_files: ['src/App.tsx'],
    })
    submitTurnMock.mockResolvedValue(undefined)
    const loadSpy = vi.spyOn(useConversations.getState(), 'loadSnapshot').mockResolvedValue()
    const refreshSpy = vi.spyOn(useSessions.getState(), 'refresh').mockResolvedValue()
    const onCompleted = vi.fn()

    act(() => {
      root.render(
        <RewindConfirmModal
          open
          sessionId="sess-1"
          messageId="msg-u2"
          editedText="edited prompt"
          onClose={() => {}}
          onCompleted={onCompleted}
        />,
      )
    })
    await flushPromises()

    expect(previewRewindMock).toHaveBeenCalledWith('sess-1', 'msg-u2')
    expect(document.body.textContent).toContain('2 轮对话')
    expect(document.body.textContent).toContain('src/App.tsx')

    // Expand file diff preview
    const fileBtn = document.querySelector('[data-testid="rewind-diff-file-list"] button') as HTMLButtonElement | null
    expect(fileBtn).not.toBeNull()
    act(() => { fileBtn?.click() })
    expect(document.body.textContent).toContain('@@ -1,2 +1,4 @@')

    // Confirm Case 1 (both)
    const confirmBtn = document.querySelector('[data-testid="rewind-confirm-submit"]') as HTMLButtonElement | null
    expect(confirmBtn).not.toBeNull()
    act(() => { confirmBtn?.click() })
    await flushPromises()

    expect(executeRewindMock).toHaveBeenCalledWith('sess-1', 'msg-u2', 'both')
    expect(loadSpy).toHaveBeenCalledWith('sess-1')
    expect(refreshSpy).toHaveBeenCalled()
    expect(submitTurnMock).toHaveBeenCalledWith('sess-1', 'edited prompt')
    expect(onCompleted).toHaveBeenCalledTimes(1)

    loadSpy.mockRestore()
    refreshSpy.mockRestore()
  })

  it('disables Case 1 when code snapshots are unavailable and executes Case 2 (conversation_only)', async () => {
    previewRewindMock.mockResolvedValue({
      can_rewind_conversation: true,
      can_rewind_code: false,
      code_unavailable_reason: '未检测到该步骤的文件快照（仅支持回撤对话）',
      turns_to_drop: 1,
      messages_to_drop: 2,
      insertions: 0,
      deletions: 0,
      files: [],
    })
    executeRewindMock.mockResolvedValue({
      rewound_conversation: true,
      rewound_code: false,
      retained_message_count: 0,
      restored_files: [],
    })
    submitTurnMock.mockResolvedValue(undefined)
    const loadSpy = vi.spyOn(useConversations.getState(), 'loadSnapshot').mockResolvedValue()
    const refreshSpy = vi.spyOn(useSessions.getState(), 'refresh').mockResolvedValue()

    act(() => {
      root.render(
        <RewindConfirmModal
          open
          sessionId="sess-2"
          messageId="msg-u1"
          editedText="retry prompt"
          onClose={() => {}}
          onCompleted={() => {}}
        />,
      )
    })
    await flushPromises()

    const bothRadio = document.querySelector('[data-testid="rewind-mode-both"] input') as HTMLInputElement | null
    expect(bothRadio?.disabled).toBe(true)

    const confirmBtn = document.querySelector('[data-testid="rewind-confirm-submit"]') as HTMLButtonElement | null
    act(() => { confirmBtn?.click() })
    await flushPromises()

    expect(executeRewindMock).toHaveBeenCalledWith('sess-2', 'msg-u1', 'conversation_only')
    expect(submitTurnMock).toHaveBeenCalledWith('sess-2', 'retry prompt')

    loadSpy.mockRestore()
    refreshSpy.mockRestore()
  })
})

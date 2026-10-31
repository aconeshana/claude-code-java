import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { MirrorFrame } from '../api/types'
import { useConversations } from '../store/conversations'
import { ChatView } from './ChatView'

vi.mock('../api/client', () => ({
  fetchSnapshot: vi.fn(),
  fetchSpinnerVerbs: vi.fn(() => Promise.resolve({ active: ['Pondering'], tools: {} })),
}))

declare global {
  // eslint-disable-next-line no-var -- React's act() environment flag
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined
}

const SESSION = 'sess-stream'

function frame(event: MirrorFrame['event'], id: number, data: Record<string, unknown>): MirrorFrame {
  return { event, id, data: { session_id: SESSION, ...data } } as MirrorFrame
}

let container: HTMLDivElement
let root: Root

beforeEach(() => {
  globalThis.IS_REACT_ACT_ENVIRONMENT = true
  useConversations.setState({ conversations: {} })
  container = document.createElement('div')
  document.body.append(container)
  root = createRoot(container)
})

afterEach(() => {
  act(() => { root.unmount() })
  container.remove()
})

async function apply(...frames: MirrorFrame[]): Promise<void> {
  await act(async () => {
    for (const f of frames) useConversations.getState().applyFrame(f)
    root.render(<ChatView conversation={useConversations.getState().conversations[SESSION]} sessionId={SESSION} />)
    await Promise.resolve()
  })
}

const occurrences = (needle: string): number => (container.textContent ?? '').split(needle).length - 1

describe('ChatView live streaming', () => {
  it('shows the streamed text as it arrives and once after the block settles', async () => {
    await apply(frame('turn.started', 1, { display_text: 'q', permission_mode: 'ask', origin: 'chat' }))
    await apply(frame('output.text.delta', 0, { content: 'Streaming', offset: 0 }))
    expect(occurrences('Streaming')).toBe(1)

    await apply(frame('output.text.delta', 0, { content: ' answer', offset: 9 }))
    expect(occurrences('Streaming answer')).toBe(1)

    await apply(frame('output.text', 2, { content: 'Streaming answer', message_id: 'm1' }))
    expect(occurrences('Streaming answer')).toBe(1)
  })

  it('streams the next block into the open step after its settled thinking', async () => {
    await apply(
      frame('turn.started', 1, { display_text: 'q', permission_mode: 'ask', origin: 'chat' }),
      frame('output.thinking', 2, { content: 'Reasoned', message_id: 'm1' }),
      frame('output.text.delta', 0, { content: 'Partial', offset: 0 }),
    )
    const assistantRows = container.querySelectorAll('[data-chat-flow-kind="assistant"]')
    expect(assistantRows).toHaveLength(1)
    expect(assistantRows[0].textContent).toContain('Partial')
  })
})

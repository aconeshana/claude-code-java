import { afterEach, describe, expect, it, vi } from 'vitest'
import * as client from '../api/client'
import { useComposerCommands } from './commands'

describe('useComposerCommands', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    useComposerCommands.getState().reset()
  })

  it('loads and caches commands from GET /api/commands', async () => {
    vi.spyOn(client, 'fetchComposerCommands').mockResolvedValue({
      commands: [
        { name: 'help', description: 'Show help', kind: 'command' },
        { name: 'review', description: 'Code review', kind: 'skill' },
      ],
    })

    await useComposerCommands.getState().refresh()

    const state = useComposerCommands.getState()
    expect(state.loaded).toBe(true)
    expect(state.pending).toBe(false)
    expect(state.failed).toBe(false)
    expect(state.commands.map((c) => c.name)).toEqual(['help', 'review'])
  })

  it('marks failed when initial load errors', async () => {
    vi.spyOn(client, 'fetchComposerCommands').mockRejectedValue(new Error('network'))

    await useComposerCommands.getState().refresh()

    const state = useComposerCommands.getState()
    expect(state.loaded).toBe(false)
    expect(state.pending).toBe(false)
    expect(state.failed).toBe(true)
    expect(state.commands).toEqual([])
  })

  it('preserves stale commands if a background refresh fails', async () => {
    const spy = vi.spyOn(client, 'fetchComposerCommands')
    spy.mockResolvedValueOnce({
      commands: [{ name: 'compact', description: 'Compact', kind: 'command' }],
    })
    await useComposerCommands.getState().refresh()

    spy.mockRejectedValueOnce(new Error('offline'))
    await useComposerCommands.getState().refresh()

    const state = useComposerCommands.getState()
    expect(state.loaded).toBe(true)
    expect(state.failed).toBe(false)
    expect(state.commands.map((c) => c.name)).toEqual(['compact'])
  })

  it('drops superseded responses out of order', async () => {
    let resolveFirst!: (value: { commands: { name: string; kind: 'command' }[] }) => void
    let resolveSecond!: (value: { commands: { name: string; kind: 'command' }[] }) => void
    const spy = vi.spyOn(client, 'fetchComposerCommands')
    spy.mockImplementationOnce(() => new Promise((r) => { resolveFirst = r }))
    spy.mockImplementationOnce(() => new Promise((r) => { resolveSecond = r }))

    const first = useComposerCommands.getState().refresh()
    const second = useComposerCommands.getState().refresh()

    resolveSecond({ commands: [{ name: 'second', kind: 'command' }] })
    await second
    resolveFirst({ commands: [{ name: 'first', kind: 'command' }] })
    await first

    expect(useComposerCommands.getState().commands.map((c) => c.name)).toEqual(['second'])
  })
})

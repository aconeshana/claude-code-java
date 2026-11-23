import { afterEach, describe, expect, it, vi } from 'vitest'
import * as client from '../api/client'
import { useShells } from './shells'

describe('useShells', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    useShells.getState().reset()
  })

  it('loads background shells from GET /api/shells', async () => {
    vi.spyOn(client, 'fetchShells').mockResolvedValue({
      shells: [
        {
          id: 'shell-1',
          command: 'cargo test',
          description: 'Run tests',
          status: 'running',
          start_time_ms: 1_000,
        },
      ],
    })

    await useShells.getState().refresh()

    const state = useShells.getState()
    expect(state.loaded).toBe(true)
    expect(state.failed).toBe(false)
    expect(state.shells).toHaveLength(1)
    expect(state.shells[0]?.command).toBe('cargo test')
  })

  it('loads shell detail and stops a running shell', async () => {
    vi.spyOn(client, 'fetchShellOutput').mockResolvedValue({
      id: 'shell-1',
      command: 'cargo test',
      status: 'running',
      start_time_ms: 1_000,
      output: 'compiling...',
      truncated: false,
    })
    const detail = await useShells.getState().loadDetail('shell-1')
    expect(detail?.output).toBe('compiling...')
    expect(useShells.getState().details['shell-1']?.output).toBe('compiling...')

    vi.spyOn(client, 'stopShell').mockResolvedValue({ stopped: true, id: 'shell-1' })
    vi.spyOn(client, 'fetchShells').mockResolvedValue({
      shells: [{ id: 'shell-1', command: 'cargo test', status: 'killed', start_time_ms: 1_000 }],
    })
    const stopped = await useShells.getState().stop('shell-1')
    expect(stopped).toBe(true)
    expect(useShells.getState().shells[0]?.status).toBe('killed')
  })
})

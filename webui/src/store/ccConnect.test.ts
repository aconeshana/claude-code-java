import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useCcConnect } from './ccConnect'
import type { CcConnectSnapshot } from '../api/types'

const SAMPLE_SNAPSHOT: CcConnectSnapshot = {
  supported: true,
  configured: true,
  setup_pending: false,
  config_path: '/Users/test/.claude/cc-connect.toml',
  available_channels: ['feishu'],
  setup_in_progress: false,
  targets: [
    {
      pid: 4242,
      work_dir: '/Users/test/project-a',
      session_id: 'sess-1234',
      session_title: 'Test TUI',
      attached: false,
      channel: '',
      status: 'stopped',
      started_at: '',
      last_error: '',
    },
  ],
}

vi.mock('../api/client', () => ({
  fetchCcConnectStatus: vi.fn(),
  attachCcConnect: vi.fn(),
  detachCcConnect: vi.fn(),
  fetchCcConnectLogs: vi.fn(),
  runCcConnectSetup: vi.fn(),
  cancelCcConnectSetup: vi.fn(),
}))

import {
  attachCcConnect,
  detachCcConnect,
  fetchCcConnectLogs,
  fetchCcConnectStatus,
} from '../api/client'

describe('useCcConnect store', () => {
  beforeEach(() => {
    useCcConnect.setState({
      snapshot: null,
      loading: false,
      busyPids: {},
      error: null,
      logsByPid: {},
      setupRunning: false,
      setupLogs: [],
      setupResult: null,
    })
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('refreshes snapshot and attaches/detaches a TUI target', async () => {
    vi.mocked(fetchCcConnectStatus).mockResolvedValue(SAMPLE_SNAPSHOT)
    await useCcConnect.getState().refresh()
    expect(useCcConnect.getState().snapshot?.targets).toHaveLength(1)
    expect(useCcConnect.getState().snapshot?.targets[0].attached).toBe(false)

    const attached: CcConnectSnapshot = {
      ...SAMPLE_SNAPSHOT,
      targets: [{ ...SAMPLE_SNAPSHOT.targets[0], attached: true, channel: 'feishu', status: 'connected' }],
    }
    vi.mocked(attachCcConnect).mockResolvedValue(attached)
    await useCcConnect.getState().attach(4242, 'sess-1234', 'feishu')
    expect(useCcConnect.getState().snapshot?.targets[0].attached).toBe(true)
    expect(useCcConnect.getState().snapshot?.targets[0].channel).toBe('feishu')

    vi.mocked(detachCcConnect).mockResolvedValue(SAMPLE_SNAPSHOT)
    await useCcConnect.getState().detach(4242, 'sess-1234')
    expect(useCcConnect.getState().snapshot?.targets[0].attached).toBe(false)
  })

  it('loads sidecar logs and refreshes on cc_connect.changed SSE frame', async () => {
    vi.mocked(fetchCcConnectLogs).mockResolvedValue({ pid: 4242, lines: ['line 1', 'line 2'] })
    await useCcConnect.getState().loadLogs(4242)
    expect(useCcConnect.getState().logsByPid[4242]).toEqual(['line 1', 'line 2'])

    vi.mocked(fetchCcConnectStatus).mockResolvedValue(SAMPLE_SNAPSHOT)
    useCcConnect.getState().applyFrame({
      event: 'cc_connect.changed',
      id: 0,
      data: { session_id: '' },
    })
    expect(fetchCcConnectStatus).toHaveBeenCalledTimes(1)
  })
})

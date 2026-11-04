import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { WorkspaceFileDiff } from '../api/types'
import { useChangesViewer } from '../store/changesViewer'
import { ChangesModal } from './ChangesModal'

const fetchWorkspaceFileDiff = vi.fn()
const openFileInHost = vi.fn()

vi.mock('../api/client', () => ({
  fetchWorkspaceFileDiff: (...args: unknown[]) => fetchWorkspaceFileDiff(...args),
  openFileInHost: (...args: unknown[]) => openFileInHost(...args),
}))

declare global {
  // eslint-disable-next-line no-var -- React's act() environment flag
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined
}

const SESSION = 'sess-changes'
const PATH = '/work/src/App.tsx'

const textDiff: WorkspaceFileDiff = {
  kind: 'text',
  path: PATH,
  display: PATH,
  hunks: [{ oldStart: 1, oldLines: 2, newStart: 1, newLines: 2, lines: [' same', '-old', '+new'] }],
  coarse: false,
  truncated: false,
}

let container: HTMLDivElement
let root: Root

beforeEach(() => {
  globalThis.IS_REACT_ACT_ENVIRONMENT = true
  fetchWorkspaceFileDiff.mockReset()
  openFileInHost.mockReset()
  useChangesViewer.setState({
    sessionId: null, path: null, status: 'idle', diff: null,
    message: null, hostMessage: null, hostPending: false,
  })
  container = document.createElement('div')
  document.body.append(container)
  root = createRoot(container)
})

afterEach(() => {
  act(() => { root.unmount() })
  container.remove()
})

async function render(): Promise<void> {
  await act(async () => { root.render(<ChangesModal />) })
}

/** Opens a file and settles the fetch, leaving the viewer in its final state. */
async function openWith(diff: WorkspaceFileDiff | Error): Promise<void> {
  if (diff instanceof Error) fetchWorkspaceFileDiff.mockRejectedValueOnce(diff)
  else fetchWorkspaceFileDiff.mockResolvedValueOnce(diff)
  await act(async () => { useChangesViewer.getState().open(SESSION, PATH) })
  await render()
}

function body(): HTMLElement {
  return document.body
}

describe('ChangesModal', () => {
  it('renders nothing while no file is open', async () => {
    await render()
    expect(body().querySelector('[data-testid="changes-modal"]')).toBeNull()
  })

  it('renders the diff for a text file', async () => {
    await openWith(textDiff)
    expect(body().querySelector('[data-testid="changes-modal"]')).not.toBeNull()
    expect(body().textContent).toContain('new')
  })

  it('reports a binary file as a notice rather than an empty diff', async () => {
    await openWith({ kind: 'binary', path: PATH, display: PATH })
    expect(body().querySelector('[data-testid="changes-diff-binary"]')).not.toBeNull()
  })

  it('reports an oversized file as a notice', async () => {
    await openWith({ kind: 'oversized', path: PATH, display: PATH })
    expect(body().querySelector('[data-testid="changes-diff-oversized"]')).not.toBeNull()
  })

  it('reports a failed fetch rather than an empty viewer', async () => {
    await openWith(new Error('not_found'))
    expect(body().querySelector('[data-testid="changes-diff-error"]')).not.toBeNull()
  })

  // The deliberate deviation: the control is present but disabled, and the
  // reason stays on screen. A later alignment pass must not "fix" this into a
  // hidden button — hiding it is what upstream does, and this project chose
  // otherwise.
  it('renders the open-in-host control disabled with a visible reason after a refusal', async () => {
    openFileInHost.mockRejectedValueOnce(new Error('cannot open local files'))
    await openWith(textDiff)

    await act(async () => { useChangesViewer.getState().openInHost(SESSION, PATH) })
    await render()

    expect(hostButton().disabled).toBe(true)
    expect(body().querySelector('[data-testid="changes-open-unavailable"]')).not.toBeNull()
  })

  it('leaves the open-in-host control enabled while the host has not refused', async () => {
    await openWith(textDiff)
    expect(hostButton().disabled).toBe(false)
    expect(body().querySelector('[data-testid="changes-open-unavailable"]')).toBeNull()
  })
})

/** The open-in-host control, located by the marker its own module attaches. */
function hostButton(): HTMLButtonElement {
  const control = body().querySelector<HTMLButtonElement>('button[class*="host"]')
  if (control === null) throw new Error('the open-in-host control is not rendered')
  return control
}
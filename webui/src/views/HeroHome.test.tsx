import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ConversationRoot } from './ConversationRoot'
import { HeroHome } from './HeroHome'
import { ProjectRowItem, SessionNodeItem } from './SessionRows'
import { useSessions } from '../store/sessions'
import { useCcConnect } from '../store/ccConnect'
import type { CatalogProject, CatalogSession } from '../api/types'

declare global {
  // eslint-disable-next-line no-var -- React's act() environment flag
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined
}

const openHeadlessSessionMock = vi.fn()
const submitTurnMock = vi.fn()

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client')
  return {
    ...actual,
    openHeadlessSession: (...args: unknown[]) => openHeadlessSessionMock(...args),
    submitTurn: (...args: unknown[]) => submitTurnMock(...args),
  }
})

vi.mock('./InputBar', () => ({
  InputBar: ({ onSubmit, disabled, placeholder }: {
    onSubmit: (content: string) => void
    disabled?: boolean
    placeholder?: string
  }) => (
    <button
      type="button"
      data-testid="mock-hero-submit"
      disabled={disabled}
      aria-label={placeholder ?? 'submit'}
      onClick={() => { onSubmit('hello from hero') }}
    >
      Send
    </button>
  ),
}))

function makeSession(overrides: Partial<CatalogSession> & { id: string }): CatalogSession {
  return {
    summary: '',
    message_count: 1,
    modified_at: '2026-11-24T00:00:00Z',
    git_branch: 'main',
    cwd: '/work/alpha',
    custom_title: '',
    first_prompt: '',
    active: false,
    remote: false,
    headless_open: false,
    interactive: false,
    ...overrides,
  }
}

const sampleProjects: readonly CatalogProject[] = [
  {
    project_path: '/work/alpha',
    project_name: 'alpha',
    session_count: 1,
    sessions: [
      makeSession({
        id: 'tui-1',
        summary: 'TUI live session',
        message_count: 2,
        remote: true,
        tui_attached: true,
        tui_pid: 9001,
        interactive: true,
      }),
    ],
  },
  {
    project_path: '/work/beta',
    project_name: 'beta',
    session_count: 1,
    sessions: [
      makeSession({
        id: 'disk-beta-1',
        summary: 'Beta session',
        cwd: '/work/beta',
      }),
    ],
  },
]

let container: HTMLDivElement
let root: Root

beforeEach(() => {
  globalThis.IS_REACT_ACT_ENVIRONMENT = true
  container = document.createElement('div')
  document.body.append(container)
  root = createRoot(container)
  openHeadlessSessionMock.mockReset()
  submitTurnMock.mockReset()
  useSessions.setState({
    projects: sampleProjects,
    selectedSessionId: null,
    loading: false,
    error: null,
    followActive: false,
  })
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

describe('HeroHome', () => {
  it('renders brand title, default workspace chip, and starts a headless session on submit', async () => {
    openHeadlessSessionMock.mockResolvedValue({
      session_id: 'hl-new-1',
      project_path: '/work/alpha',
      resumed: false,
      headless: true,
      owner: 'headless',
    })
    submitTurnMock.mockResolvedValue(undefined)
    const refreshSpy = vi.spyOn(useSessions.getState(), 'refresh').mockResolvedValue()
    const selectSpy = vi.spyOn(useSessions.getState(), 'selectByUser').mockResolvedValue()

    act(() => { root.render(<HeroHome />) })

    expect(container.textContent).toContain('Pokemon Code')
    const workspaceBtn = container.querySelector('[data-testid="hero-workspace-button"]') as HTMLButtonElement
    expect(workspaceBtn.textContent).toContain('alpha')

    const submitBtn = container.querySelector('[data-testid="mock-hero-submit"]') as HTMLButtonElement
    act(() => { submitBtn.click() })
    await flushPromises()

    expect(openHeadlessSessionMock).toHaveBeenCalledWith(null, '/work/alpha')
    expect(refreshSpy).toHaveBeenCalled()
    expect(selectSpy).toHaveBeenCalledWith('hl-new-1')
    expect(submitTurnMock).toHaveBeenCalledWith('hl-new-1', 'hello from hero')

    refreshSpy.mockRestore()
    selectSpy.mockRestore()
  })

  it('switches workspace via the vendored Menu before creating the headless session', async () => {
    openHeadlessSessionMock.mockResolvedValue({
      session_id: 'hl-beta-1',
      project_path: '/work/beta',
      resumed: false,
      headless: true,
      owner: 'headless',
    })
    submitTurnMock.mockResolvedValue(undefined)
    const refreshSpy = vi.spyOn(useSessions.getState(), 'refresh').mockResolvedValue()
    const selectSpy = vi.spyOn(useSessions.getState(), 'selectByUser').mockResolvedValue()

    act(() => { root.render(<HeroHome />) })

    const workspaceBtn = container.querySelector('[data-testid="hero-workspace-button"]') as HTMLButtonElement
    act(() => { workspaceBtn.click() })

    const menuItems = Array.from(document.querySelectorAll('[role="menu"] button, [role="menuitemradio"], [role="menuitem"]'))
    const betaItem = menuItems.find((el) => el.textContent?.includes('beta')) as HTMLElement | undefined
    expect(betaItem).toBeDefined()
    act(() => { betaItem?.click() })
    expect(workspaceBtn.textContent).toContain('beta')

    const submitBtn = container.querySelector('[data-testid="mock-hero-submit"]') as HTMLButtonElement
    act(() => { submitBtn.click() })
    await flushPromises()

    expect(openHeadlessSessionMock).toHaveBeenCalledWith(null, '/work/beta')

    refreshSpy.mockRestore()
    selectSpy.mockRestore()
  })

  it('inherits ConversationRoot width-axis container and headerHidden when hosted in hero mode', () => {
    const prevResizeObserver = globalThis.ResizeObserver
    globalThis.ResizeObserver = class MockResizeObserver {
      private readonly cb: ResizeObserverCallback
      constructor(cb: ResizeObserverCallback) { this.cb = cb }
      observe(target: Element) {
        this.cb([{ contentRect: { width: 1200, height: 800 } as DOMRectReadOnly, target } as ResizeObserverEntry], this)
      }
      unobserve() {}
      disconnect() {}
    }
    try {
      act(() => {
        root.render(
          <ConversationRoot
            title="Pokemon Code"
            hero={<HeroHome />}
          />,
        )
      })

      const rootEl = container.querySelector('[data-phase="blank"]') as HTMLElement | null
      expect(rootEl).not.toBeNull()
      expect(rootEl?.style.getPropertyValue('--dsh-conversation-column-width')).toBe('1200px')
      expect(rootEl?.querySelector('[data-testid="hero-home"]')).not.toBeNull()
      const header = rootEl?.firstElementChild
      expect(header?.className).toContain('headerHidden')
    } finally {
      globalThis.ResizeObserver = prevResizeObserver
    }
  })
})

describe('SessionNodeItem TUI badge', () => {
  it('renders TUI pill badge for tui_attached sessions alongside feishu badge', () => {
    useCcConnect.setState({
      snapshot: {
        supported: true,
        configured: true,
        setup_pending: false,
        config_path: '/tmp/cc-connect.toml',
        available_channels: ['feishu'],
        setup_in_progress: false,
        targets: [
          {
            pid: 12345,
            session_id: 'tui-row-1',
            session_title: 'Active TUI session',
            work_dir: '/work/alpha',
            attached: true,
            status: 'connected',
            channel: 'feishu',
            started_at: '2026-11-24T00:00:00Z',
            last_error: '',
          },
        ],
      },
    })
    const tuiSession = makeSession({
      id: 'tui-row-1',
      summary: 'Active TUI session',
      remote: true,
      tui_attached: true,
      tui_pid: 12345,
      interactive: true,
    })

    act(() => {
      root.render(
        <SessionNodeItem
          session={tuiSession}
          selected={false}
          pinned={false}
          now={Date.parse('2026-11-24T00:01:00Z')}
          onOpen={() => {}}
          onTogglePin={() => {}}
          onClose={() => {}}
          onRename={() => {}}
          onFork={() => {}}
          onArchive={() => {}}
          onDelete={() => {}}
          t={(key: string) => key}
        />,
      )
    })

    expect(container.textContent).toContain('TUI')
    expect(container.textContent).toContain('feishu')
  })

  it('omits TUI pill badge for headless / disk-only sessions', () => {
    const headlessSession = makeSession({
      id: 'hl-row-1',
      summary: 'Headless session',
      tui_attached: false,
      headless_open: true,
      interactive: true,
    })

    act(() => {
      root.render(
        <SessionNodeItem
          session={headlessSession}
          selected={false}
          pinned={false}
          now={Date.parse('2026-11-24T00:01:00Z')}
          onOpen={() => {}}
          onTogglePin={() => {}}
          onClose={() => {}}
          onRename={() => {}}
          onFork={() => {}}
          onArchive={() => {}}
          onDelete={() => {}}
          t={(key: string) => key}
        />,
      )
    })

    expect(container.textContent).not.toContain('TUI')
  })
})

describe('ProjectRowItem actions', () => {
  it('renders trailing + and ... menu actions (new session, hide, delete) without toggling the group', () => {
    const onToggle = vi.fn()
    const onNewSession = vi.fn()
    const onHideProject = vi.fn()
    const onDeleteProject = vi.fn()

    act(() => {
      root.render(
        <ProjectRowItem
          label="alpha"
          expanded
          containsCurrent={false}
          onToggle={onToggle}
          onNewSession={onNewSession}
          onHideProject={onHideProject}
          onDeleteProject={onDeleteProject}
        />,
      )
    })

    const newBtn = container.querySelector('button[aria-label="在“alpha”中新建会话"]') as HTMLButtonElement | null
    expect(newBtn).not.toBeNull()
    act(() => { newBtn?.click() })
    expect(onNewSession).toHaveBeenCalledTimes(1)
    expect(onToggle).not.toHaveBeenCalled()

    const menuBtn = container.querySelector('button[aria-label="项目“alpha”的更多操作"]') as HTMLButtonElement | null
    expect(menuBtn).not.toBeNull()
    act(() => { menuBtn?.click() })
    expect(onToggle).not.toHaveBeenCalled()

    let menuItems = Array.from(document.querySelectorAll('[role="menu"] button, [role="menuitem"]'))
    const hideItem = menuItems.find((el) => el.textContent?.includes('从列表隐藏')) as HTMLElement | undefined
    expect(hideItem).toBeDefined()
    act(() => { hideItem?.click() })
    expect(onHideProject).toHaveBeenCalledTimes(1)

    act(() => { menuBtn?.click() })
    menuItems = Array.from(document.querySelectorAll('[role="menu"] button, [role="menuitem"]'))
    const deleteItem = menuItems.find((el) => el.textContent?.includes('删除项目会话')) as HTMLElement | undefined
    expect(deleteItem).toBeDefined()
    act(() => { deleteItem?.click() })
    expect(onDeleteProject).toHaveBeenCalledTimes(1)
  })
})

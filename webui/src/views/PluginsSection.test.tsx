import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { PluginsSnapshot } from '../api/types'

const fetchMock = vi.fn()
const addMktMock = vi.fn()
const installMock = vi.fn()
const toggleMock = vi.fn()
const saveOptionsMock = vi.fn()

const SAMPLE_SNAPSHOT: PluginsSnapshot = {
  supported: true,
  summary: { command_count: 3, agent_count: 1, skill_count: 1, mcp_count: 0, error_count: 0 },
  installed: [
    {
      plugin_id: 'opencli-browser@poco-plugins',
      name: 'opencli-browser',
      marketplace: 'poco-plugins',
      enabled: true,
      description: 'Non-intrusive CDP Browser Use',
      version: '0.1.0',
      author: 'aconeshana',
      homepage: 'https://github.com/aconeshana/poco-plugins',
      repository: 'https://github.com/aconeshana/poco-plugins',
      installations: [{ scope: 'user', project_path: '', install_path: '/tmp/cache', version: '0.1.0' }],
      user_config: {
        default_session: {
          type: 'string',
          title: 'Default Browser Session',
          description: 'Default OpenCLI session name',
          required: false,
          sensitive: false,
          default: 'poco',
        },
      },
      options: { default_session: 'poco' },
    },
  ],
  marketplaces: [
    {
      name: 'poco-plugins',
      source: 'aconeshana/poco-plugins',
      install_location: '/tmp/marketplaces/poco-plugins',
      last_updated: '2026-11-26T00:00:00Z',
      auto_update: false,
      plugins: [
        {
          name: 'opencli-browser',
          plugin_id: 'opencli-browser@poco-plugins',
          marketplace: 'poco-plugins',
          description: 'Non-intrusive CDP Browser Use',
          version: '0.1.0',
          author: 'aconeshana',
          category: 'browser-automation',
          homepage: '',
          repository: '',
          tags: ['browser', 'cdp'],
        },
        {
          name: 'extra-helper',
          plugin_id: 'extra-helper@poco-plugins',
          marketplace: 'poco-plugins',
          description: 'Extra helper plugin',
          version: '0.2.0',
          author: 'aconeshana',
          category: 'productivity',
          homepage: '',
          repository: '',
          tags: [],
        },
      ],
    },
  ],
  errors: [],
}

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client')
  return {
    ...actual,
    fetchPluginsSnapshot: () => fetchMock(),
    addPluginMarketplace: (source: string) => addMktMock(source),
    installMarketplacePlugin: (input: unknown) => installMock(input),
    toggleInstalledPlugin: (input: unknown) => toggleMock(input),
    saveInstalledPluginOptions: (input: unknown) => saveOptionsMock(input),
  }
})

vi.mock('../store/commands', () => ({
  useComposerCommands: { getState: () => ({ refresh: vi.fn() }) },
}))

vi.mock('../store/settings', () => ({
  useSettings: { getState: () => ({ refresh: vi.fn() }) },
}))

import { PluginsSection } from './PluginsSection'

let container: HTMLDivElement
let root: Root

beforeEach(() => {
  ;(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  fetchMock.mockResolvedValue(SAMPLE_SNAPSHOT)
  addMktMock.mockResolvedValue(SAMPLE_SNAPSHOT)
  installMock.mockResolvedValue(SAMPLE_SNAPSHOT)
  toggleMock.mockResolvedValue(SAMPLE_SNAPSHOT)
  saveOptionsMock.mockResolvedValue(SAMPLE_SNAPSHOT)
})

afterEach(() => {
  act(() => { root.unmount() })
  container.remove()
  vi.clearAllMocks()
})

async function flushPromises(): Promise<void> {
  await act(async () => {
    await new Promise((resolve) => { setTimeout(resolve, 0) })
  })
}

function setInputValue(input: HTMLInputElement, value: string): void {
  const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set
  setter?.call(input, value)
  input.dispatchEvent(new Event('input', { bubbles: true }))
  input.dispatchEvent(new Event('change', { bubbles: true }))
}

describe('PluginsSection', () => {
  it('renders runtime summary, installs catalog plugin, edits options, and adds marketplace', async () => {
    await act(async () => {
      root.render(<PluginsSection />)
    })
    await flushPromises()

    expect(container.textContent).toContain('已加载 1 个技能 · 3 个斜杠命令 · 1 个子代理')
    expect(container.textContent).toContain('opencli-browser')
    expect(container.textContent).toContain('已安装')

    // Click Install on extra-helper
    const buttons = Array.from(container.querySelectorAll('button'))
    const installBtn = buttons.find((b) => b.textContent?.trim() === '安装')
    expect(installBtn).toBeDefined()
    await act(async () => {
      installBtn?.click()
    })
    await flushPromises()
    expect(installMock).toHaveBeenCalledWith({
      pluginName: 'extra-helper',
      marketplaceName: 'poco-plugins',
      scope: 'user',
    })

    // Open options editor and save
    const optionsBtn = Array.from(container.querySelectorAll('button')).find((b) => b.textContent?.trim() === '选项')
    expect(optionsBtn).toBeDefined()
    await act(async () => {
      optionsBtn?.click()
    })
    const sessionInput = container.querySelector('input[aria-label="Default Browser Session"]') as HTMLInputElement | null
    expect(sessionInput).not.toBeNull()
    await act(async () => {
      if (sessionInput) setInputValue(sessionInput, 'work-tab')
    })
    const saveBtn = Array.from(container.querySelectorAll('button')).find((b) => b.textContent?.trim() === '保存配置')
    await act(async () => {
      saveBtn?.click()
    })
    await flushPromises()
    expect(saveOptionsMock).toHaveBeenCalledWith({
      pluginId: 'opencli-browser@poco-plugins',
      values: { default_session: 'work-tab' },
    })

    // Add marketplace
    const mktInput = container.querySelector('input[aria-label="plugin marketplace source"]') as HTMLInputElement | null
    expect(mktInput).not.toBeNull()
    await act(async () => {
      if (mktInput) setInputValue(mktInput, 'aconeshana/poco-plugins')
    })
    const addMktBtn = Array.from(container.querySelectorAll('button')).find((b) => b.textContent?.trim() === '添加市场')
    await act(async () => {
      addMktBtn?.click()
    })
    await flushPromises()
    expect(addMktMock).toHaveBeenCalledWith('aconeshana/poco-plugins')
  })
})

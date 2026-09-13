import { beforeEach, describe, expect, it, vi } from 'vitest'

describe('enter behavior store', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.resetModules()
  })

  it('defaults to queue when storage is empty', async () => {
    const { useEnterBehavior } = await import('./enterBehavior')
    expect(useEnterBehavior.getState().behavior).toBe('queue')
  })

  it('initializes from a persisted behavior', async () => {
    localStorage.setItem('webui-enter-behavior', 'steer')
    const { useEnterBehavior } = await import('./enterBehavior')
    expect(useEnterBehavior.getState().behavior).toBe('steer')
  })

  it('falls back to queue for a corrupt persisted value', async () => {
    localStorage.setItem('webui-enter-behavior', 'yolo')
    const { useEnterBehavior } = await import('./enterBehavior')
    expect(useEnterBehavior.getState().behavior).toBe('queue')
  })

  it('setBehavior persists and updates the store', async () => {
    const { useEnterBehavior } = await import('./enterBehavior')
    useEnterBehavior.getState().setBehavior('steer')
    expect(useEnterBehavior.getState().behavior).toBe('steer')
    expect(localStorage.getItem('webui-enter-behavior')).toBe('steer')
  })

  it('picks up a cross-tab write via the storage event', async () => {
    const { useEnterBehavior } = await import('./enterBehavior')
    localStorage.setItem('webui-enter-behavior', 'steer')
    window.dispatchEvent(new StorageEvent('storage', { key: 'webui-enter-behavior', newValue: 'steer' }))
    expect(useEnterBehavior.getState().behavior).toBe('steer')
  })

  it('ignores storage events for unrelated keys', async () => {
    const { useEnterBehavior } = await import('./enterBehavior')
    window.dispatchEvent(new StorageEvent('storage', { key: 'unrelated-key', newValue: 'steer' }))
    expect(useEnterBehavior.getState().behavior).toBe('queue')
  })
})

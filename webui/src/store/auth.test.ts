import { beforeEach, describe, expect, it, vi } from 'vitest'

describe('auth store', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.resetModules()
  })

  it('defaults to null when no token is present', async () => {
    const { useAuth } = await import('./auth')
    expect(useAuth.getState().token).toBeNull()
  })

  it('initializes with the current sessionStorage token', async () => {
    sessionStorage.setItem('gateway-token', 'fake-token')
    const { useAuth } = await import('./auth')
    expect(useAuth.getState().token).toBe('fake-token')
  })

  it('setToken updates the store', async () => {
    const { useAuth } = await import('./auth')
    useAuth.getState().setToken('new-token')
    expect(useAuth.getState().token).toBe('new-token')
  })
})

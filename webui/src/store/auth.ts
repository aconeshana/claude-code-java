/**
 * Reactive mirror of the sessionStorage-backed launch token. `currentToken()`
 * (`../api/token`) is a plain synchronous read used by fetch/EventSource call
 * sites; this store exists so App.tsx can subscribe and re-render when a
 * token arrives asynchronously via cross-tab BroadcastChannel sync instead of
 * only ever seeing the value that was present at first render.
 */
import { create } from 'zustand'
import { currentToken } from '../api/token'

export interface AuthStore {
  readonly token: string | null
  setToken(token: string): void
}

export const useAuth = create<AuthStore>(set => ({
  token: currentToken(),

  setToken(token) {
    set({ token })
  },
}))

import { create } from 'zustand'
import { fetchSessionCapabilities, toggleSessionCapability } from '../api/client'
import type {
  SessionCapabilities,
  SessionCapabilityKind,
  SessionCapabilityScope,
} from '../api/types'

export interface CapabilitiesStore {
  readonly bySession: Readonly<Record<string, SessionCapabilities>>
  readonly loadingBySession: Readonly<Record<string, boolean>>
  readonly pendingKeys: Readonly<Record<string, boolean>>
  readonly scope: SessionCapabilityScope
  readonly lastError: string | null
  setScope(scope: SessionCapabilityScope): void
  refresh(sessionId: string | null): Promise<void>
  toggle(params: {
    sessionId: string | null
    kind: SessionCapabilityKind
    name: string
    serverName?: string | undefined
    enabled: boolean
    scope?: SessionCapabilityScope | undefined
  }): Promise<string | null>
}

let refreshEpoch = 0

function keyOf(sessionId: string | null | undefined): string {
  return sessionId ?? ''
}

export const useCapabilities = create<CapabilitiesStore>((set, get) => ({
  bySession: {},
  loadingBySession: {},
  pendingKeys: {},
  scope: 'session',
  lastError: null,

  setScope(scope) {
    if (get().scope !== scope) set({ scope })
  },

  async refresh(sessionId) {
    const k = keyOf(sessionId)
    const myEpoch = ++refreshEpoch
    set((s) => ({
      loadingBySession: { ...s.loadingBySession, [k]: true },
      lastError: null,
    }))
    try {
      const res = await fetchSessionCapabilities(sessionId)
      if (myEpoch < refreshEpoch && get().bySession[k] !== undefined) {
        // Newer refresh superseded; still clear loading below.
      }
      set((s) => {
        const nextMap = { ...s.bySession }
        if (res.capabilities !== null) {
          nextMap[k] = res.capabilities
        }
        return {
          bySession: nextMap,
          loadingBySession: { ...s.loadingBySession, [k]: false },
        }
      })
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err)
      set((s) => ({
        loadingBySession: { ...s.loadingBySession, [k]: false },
        lastError: msg,
      }))
    }
  },

  async toggle({ sessionId, kind, name, serverName, enabled, scope }) {
    const k = keyOf(sessionId)
    const effectiveScope = scope ?? get().scope
    const pendingId = `${k}:${kind}:${serverName ?? ''}:${name}`
    set((s) => ({
      pendingKeys: { ...s.pendingKeys, [pendingId]: true },
      lastError: null,
    }))
    try {
      const res = await toggleSessionCapability({
        session_id: sessionId,
        kind,
        name,
        ...(serverName !== undefined ? { server_name: serverName } : {}),
        enabled,
        scope: effectiveScope,
      })
      set((s) => {
        const nextPending = { ...s.pendingKeys }
        delete nextPending[pendingId]
        return {
          bySession: { ...s.bySession, [k]: res.capabilities },
          pendingKeys: nextPending,
        }
      })
      return null
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err)
      set((s) => {
        const nextPending = { ...s.pendingKeys }
        delete nextPending[pendingId]
        return {
          pendingKeys: nextPending,
          lastError: msg,
        }
      })
      return msg
    }
  },
}))

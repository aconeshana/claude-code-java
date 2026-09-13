/**
 * Launch token capture.
 *
 * The /web command prints `http://127.0.0.1:<port>/?token=<hex>`; the webui
 * captures the token from that landing URL into sessionStorage so API calls
 * (fetch with a Bearer header) and the SSE subscription (EventSource with
 * ?token=, since it cannot carry headers) both reach the gateway for the
 * rest of the tab's lifetime.
 */

const STORAGE_KEY = 'gateway-token'

/** Captures ?token= from the URL into storage; call once at startup. */
export function captureTokenFromUrl(): void {
  const fromUrl = new URLSearchParams(window.location.search).get('token')
  if (fromUrl != null && fromUrl !== '') {
    try {
      sessionStorage.setItem(STORAGE_KEY, fromUrl)
    } catch { /* storage unavailable: keep going with the URL token only */ }
    // Strip the token from the address bar (it stays in history otherwise).
    const clean = new URL(window.location.href)
    clean.searchParams.delete('token')
    window.history.replaceState(null, '', clean)
  }
}

/** The captured token, or null before the landing URL was opened. */
export function currentToken(): string | null {
  try {
    return sessionStorage.getItem(STORAGE_KEY)
  } catch {
    return null
  }
}

const CHANNEL_NAME = 'webui-token-sync'

interface TokenSyncMessage {
  readonly type: 'request-token' | 'token'
  readonly token?: string
}

/**
 * Lets a tab without a launch token inherit one from an already-open tab via
 * a same-origin BroadcastChannel handshake. The token is written back into
 * this tab's own sessionStorage — it never gets promoted to localStorage, so
 * the "forget on browser close" boundary is unchanged. Call once at startup,
 * after captureTokenFromUrl().
 */
export function initTokenSync(onToken: (token: string) => void): void {
  if (typeof BroadcastChannel === 'undefined') return
  const channel = new BroadcastChannel(CHANNEL_NAME)
  channel.onmessage = (event: MessageEvent<TokenSyncMessage>) => {
    const message = event.data
    if (message.type === 'request-token') {
      const token = currentToken()
      if (token != null) channel.postMessage({ type: 'token', token } satisfies TokenSyncMessage)
    } else if (message.type === 'token' && message.token != null && currentToken() == null) {
      try {
        sessionStorage.setItem(STORAGE_KEY, message.token)
      } catch { /* storage unavailable: the token still works for this render */ }
      onToken(message.token)
    }
  }
  const existing = currentToken()
  channel.postMessage(
    existing != null
      ? ({ type: 'token', token: existing } satisfies TokenSyncMessage)
      : ({ type: 'request-token' } satisfies TokenSyncMessage),
  )
}

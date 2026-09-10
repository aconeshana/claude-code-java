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

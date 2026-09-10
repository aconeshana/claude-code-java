import type { MirrorFrame } from './types'
import { currentToken } from './token'

/**
 * Mirror stream subscription (/api/events).
 *
 * The browser's EventSource owns reconnection and replays the gateway's
 * journal ring automatically: it resends Last-Event-ID on reconnect, the
 * gateway replays every journal frame after that cursor, and the frame-id
 * dedupe in the conversation reducer makes the overlap a no-op.
 */

export interface MirrorConnection {
  close(): void
}

export function subscribeMirror(
  onFrame: (frame: MirrorFrame) => void,
  onStateChange?: (state: 'connecting' | 'open' | 'error') => void,
): MirrorConnection {
  const token = currentToken()
  const url = token == null ? '/api/events' : `/api/events?token=${encodeURIComponent(token)}`
  const source = new EventSource(url)

  source.onopen = () => { onStateChange?.('open') }
  source.onerror = () => { onStateChange?.('error') }

  const forward = (event: string) => (ev: MessageEvent<string>) => {
    if (ev.data == null || ev.data === '') return
    try {
      const data: unknown = JSON.parse(ev.data)
      if (data == null || typeof data !== 'object') return
      onFrame({ event, id: Number(ev.lastEventId), data: data as Record<string, unknown> } as MirrorFrame)
    } catch { /* malformed frame: skip, the ring keeps moving */ }
  }

  for (const name of [
    'turn.started', 'output.text', 'output.thinking', 'tool.started',
    'tool.progress', 'tool.completed', 'permission.asked',
    'permission.resolved', 'session.activated', 'session.error',
    'session.idle', 'turn.cancelled', 'turn.completed',
  ]) {
    source.addEventListener(name, forward(name) as EventListener)
  }

  return { close() { source.close() } }
}

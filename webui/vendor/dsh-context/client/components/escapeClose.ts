/**
 * The shared Escape-to-close contract of the plugin's overlays (the /context
 * modal and the baseline-gate dialog): capture-phase keydown on window, so
 * the composer's own key handling never swallows Escape first; on close, focus
 * returns to the element that held it when the overlay opened (skipped when
 * that element left the document).
 */

import { useEffect, useRef } from 'react'

/**
 * Close on Escape while `active`; restores the pre-open focus on cleanup.
 * `onClose` rides a latest-ref so the subscription lives on the `active`
 * transitions alone: every caller passes a render-fresh closure, and a
 * per-render resubscribe would rerun the cleanup's focus restore — yanking
 * focus out of the overlay's own inputs on every keystroke.
 */
export function useEscapeClose(active: boolean, onClose: () => void): void {
  const close = useRef(onClose)
  close.current = onClose
  useEffect(() => {
    if (!active) return undefined
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null
    // claude-code-java: overlays stack. The /context modal and the Context
    // Dashboard can be open at once, both listening on window capture, and
    // one Escape must close only the newest. Each active hook holds a token
    // on a shared stack; only the top token's listener acts, and it stops
    // the event so no other capture listener sees it.
    const token = Symbol('escape-close')
    openOverlays.push(token)
    const onKey = (ev: KeyboardEvent): void => {
      if (ev.key !== 'Escape') return
      if (openOverlays[openOverlays.length - 1] !== token) return
      ev.preventDefault()
      ev.stopImmediatePropagation()
      close.current()
    }
    window.addEventListener('keydown', onKey, true)
    return () => {
      window.removeEventListener('keydown', onKey, true)
      const index = openOverlays.lastIndexOf(token)
      if (index >= 0) openOverlays.splice(index, 1)
      if (previous !== null && document.contains(previous)) previous.focus()
    }
  }, [active])
}

/** Active overlays, oldest first; the last entry owns Escape. */
const openOverlays: symbol[] = []

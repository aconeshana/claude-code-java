import { useCallback, useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import {
  CENTER_MIN, RIGHTBAR_DEFAULT_RATIO, RIGHTBAR_MAX_RATIO, RIGHTBAR_MIN,
  SIDEBAR_DEFAULT, clampWidth, computeColumns, layoutActions, useLayout,
} from '@vendor/ui-layout'
import css from '@chat-styles/AppFrame.module.css'

/** Default wide-sidebar track width; Sidebar.tsx tracks live store width while expanded. */
export const SIDEBAR_WIDTH = SIDEBAR_DEFAULT

/** What the frame hands its right column occupant (upstream's `RightbarOwnerProps`). */
export interface RightbarOwnerProps {
  /** Resolved normal panel width in px, not the saved preference; zero if it cannot fit. */
  width: number
  /** Current frame width in px. */
  viewportWidth: number
  /**
   * Whether a normal right panel can retain 300px beside a 400px center. The
   * occupant shows only when this holds (or in fullscreen on narrow viewports).
   */
  canShow: boolean
  /** Whether the frame is currently in a pointer drag gesture. */
  dragging?: boolean
}

/**
 * One drag handle: pointer capture, rAF-throttled dx reports against drag-start origin.
 * Adapted from upstream `packages/client/ui-layout/src/client/AppFrame.tsx`.
 */
function DragHandle(props: {
  side: 'sidebar' | 'rightbar'
  left: number
  onStart: () => void
  onDrag: (dx: number) => void
  onEnd: () => void
}) {
  const [dragging, setDragging] = useState(false)
  const origin = useRef(0)
  const latest = useRef(0)
  const frame = useRef<number | null>(null)
  const capture = useRef<{ element: HTMLDivElement; id: number } | null>(null)
  const callbacks = useRef({ onStart: props.onStart, onDrag: props.onDrag, onEnd: props.onEnd })
  callbacks.current = { onStart: props.onStart, onDrag: props.onDrag, onEnd: props.onEnd }

  const endDrag = useCallback(() => {
    const active = capture.current
    if (active === null) return
    capture.current = null
    if (frame.current !== null) {
      cancelAnimationFrame(frame.current)
      frame.current = null
    }
    if (active.element.hasPointerCapture(active.id)) {
      active.element.releasePointerCapture(active.id)
    }
    setDragging(false)
    callbacks.current.onEnd()
  }, [])
  useEffect(() => endDrag, [endDrag])

  const onPointerDown = useCallback((e: React.PointerEvent<HTMLDivElement>) => {
    if (e.button !== 0 || capture.current !== null) return
    e.preventDefault()
    e.currentTarget.setPointerCapture(e.pointerId)
    capture.current = { element: e.currentTarget, id: e.pointerId }
    origin.current = e.clientX
    latest.current = e.clientX
    callbacks.current.onStart()
    setDragging(true)
  }, [])

  const onPointerMove = useCallback((e: React.PointerEvent<HTMLDivElement>) => {
    if (capture.current?.id !== e.pointerId) return
    latest.current = e.clientX
    frame.current ??= requestAnimationFrame(() => {
      frame.current = null
      callbacks.current.onDrag(latest.current - origin.current)
    })
  }, [])

  const onPointerUp = useCallback((e: React.PointerEvent<HTMLDivElement>) => {
    if (capture.current?.id !== e.pointerId) return
    callbacks.current.onDrag(e.clientX - origin.current)
    endDrag()
  }, [endDrag])

  const onPointerCancel = useCallback((e: React.PointerEvent<HTMLDivElement>) => {
    if (capture.current?.id === e.pointerId) endDrag()
  }, [endDrag])

  return (
    <div
      className={css.handle}
      style={{ left: props.left }}
      data-side={props.side}
      data-dragging={dragging || undefined}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
      onPointerCancel={onPointerCancel}
      onLostPointerCapture={onPointerCancel}
    />
  )
}

/**
 * Three-column application frame: sidebar | center | right dock, over the
 * vendored dsh ui-layout frame grid, with pointer-captured column drag handles
 * and fullscreen/overlay support.
 */
export function AppFrame({
  sidebar,
  sidebarWidth,
  collapsed = false,
  right = null,
  overlay = null,
  children,
}: {
  sidebar: ReactNode
  sidebarWidth?: number
  collapsed?: boolean
  right?: ((owner: RightbarOwnerProps) => ReactNode) | null
  overlay?: ReactNode
  children: ReactNode
}) {
  const frameRef = useRef<HTMLDivElement | null>(null)
  const viewportWidth = useLayout((state) => state.viewportWidth)
  const storedSidebar = useLayout((state) => state.sidebar)
  const narrowExpanded = useLayout((state) => state.narrowExpanded)
  const rightbar = useLayout((state) => state.rightbar)
  const rightbarShown = useLayout((state) => state.rightbarShown)
  const rightbarTrack = useLayout((state) => state.rightbarTrack)
  const rightbarFullscreen = useLayout((state) => state.rightbarFullscreen)
  const rightbarInstant = useLayout((state) => state.rightbarInstant)

  const [dragging, setDragging] = useState(false)
  const sidebarBase = useRef(0)
  const rightbarBase = useRef(0)

  // Track the frame's own box via ResizeObserver (with window fallback).
  useLayoutEffect(() => {
    const el = frameRef.current
    if (el === null) return
    let raf: number | null = null
    let disposed = false
    const measure = () => {
      const w = el.getBoundingClientRect().width || (typeof window !== 'undefined' ? window.innerWidth : 0)
      if (w > 0) layoutActions.setViewportWidth(Math.round(w))
    }
    measure()
    if (typeof ResizeObserver === 'undefined') {
      window.addEventListener('resize', measure)
      return () => { window.removeEventListener('resize', measure) }
    }
    const observer = new ResizeObserver(() => {
      if (disposed) return
      raf ??= requestAnimationFrame(() => {
        raf = null
        measure()
      })
    })
    observer.observe(el)
    return () => {
      disposed = true
      observer.disconnect()
      if (raf !== null) cancelAnimationFrame(raf)
    }
  }, [])

  const effectiveSidebarPref = sidebarWidth ?? (storedSidebar === 0 ? SIDEBAR_DEFAULT : storedSidebar)
  const sidebarCollapsed = collapsed && !narrowExpanded
  const sidebarPx = sidebarCollapsed ? 0 : effectiveSidebarPref
  const rightbarPreference = rightbar ?? viewportWidth * RIGHTBAR_DEFAULT_RATIO

  const normal = computeColumns(viewportWidth, sidebarPx, rightbarPreference)
  const cols = computeColumns(
    viewportWidth,
    sidebarPx,
    rightbarTrack ? rightbarPreference : 0,
  )
  const sidebarTrack = cols.sidebar
  const rightbarTrackPx = cols.rightbar

  const colsRef = useRef(cols)
  colsRef.current = cols

  const rightbarMax = rightbarTrackPx === 0
    ? 0
    : clampWidth(rightbarPreference, RIGHTBAR_MIN, viewportWidth * RIGHTBAR_MAX_RATIO)

  const onSidebarDragStart = useCallback(() => {
    sidebarBase.current = colsRef.current.sidebar
    setDragging(true)
  }, [])
  const onSidebarDrag = useCallback((dx: number) => {
    layoutActions.setSidebar(sidebarBase.current + dx)
  }, [])
  const onRightbarDragStart = useCallback(() => {
    rightbarBase.current = colsRef.current.rightbar
    setDragging(true)
  }, [])
  const onRightbarDrag = useCallback((dx: number) => {
    layoutActions.setRightbar(rightbarBase.current - dx)
  }, [])
  const onDragEnd = useCallback(() => {
    setDragging(false)
  }, [])

  return (
    <div
      ref={frameRef}
      className={css.frame}
      style={{
        gridTemplateColumns:
          `${sidebarTrack}px minmax(${rightbarTrackPx === 0 ? 0 : CENTER_MIN}px, 1fr) minmax(0px, ${rightbarMax}px)`,
      }}
      data-dragging={dragging || undefined}
      data-rightbar-collapsed={rightbarTrackPx === 0 || undefined}
      data-rightbar-fullscreen={rightbarFullscreen || undefined}
      data-rightbar-instant={rightbarInstant || undefined}
    >
      <div className={css.sidebarCol}>{sidebar}</div>
      <div className={css.centerCol}>{children}</div>
      <div className={css.rightbarCol} data-rightbar-col>
        {right?.({
          width: normal.rightbar,
          viewportWidth,
          canShow: normal.rightbar > 0,
          dragging,
        })}
      </div>

      {!sidebarCollapsed && !rightbarFullscreen && (
        <DragHandle
          side="sidebar"
          left={sidebarTrack}
          onStart={onSidebarDragStart}
          onDrag={onSidebarDrag}
          onEnd={onDragEnd}
        />
      )}

      {rightbarShown && !rightbarFullscreen && rightbarTrackPx > 0 && (
        <DragHandle
          side="rightbar"
          left={viewportWidth - rightbarTrackPx}
          onStart={onRightbarDragStart}
          onDrag={onRightbarDrag}
          onEnd={onDragEnd}
        />
      )}

      {overlay !== null && (
        <div className={css.overlayLayer}>{overlay}</div>
      )}
    </div>
  )
}

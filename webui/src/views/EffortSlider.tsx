import { useRef, type KeyboardEvent, type PointerEvent } from 'react'
import clsx from 'clsx'
import css from './EffortSlider.module.css'

const LABELS: Readonly<Record<string, string>> = {
  '': '默认',
  auto: '默认',
  none: '关闭',
  low: 'Low',
  medium: 'Medium',
  high: 'High',
  xhigh: 'XHigh',
  max: 'Max',
  ultra: 'Ultra',
}

export function effortDisplayLabel(value: string): string {
  const key = value.trim().toLowerCase()
  return LABELS[key] ?? (value || '默认')
}

export function isDefaultEffort(value: string): boolean {
  const key = value.trim().toLowerCase()
  return key === '' || key === 'auto' || key === 'none'
}

export function isMaxEffort(value: string, choices: readonly string[]): boolean {
  if (choices.length < 2) return false
  const key = value.trim().toLowerCase()
  if (key === 'max' || key === 'ultra' || key === 'xhigh') return true
  const idx = choices.indexOf(value)
  return idx === choices.length - 1 && !isDefaultEffort(value)
}

/**
 * Unified Codex-style reasoning effort slider used in both the chat composer
 * (ModelSelect) and the Scheduled Tasks modal. Normal levels render a flowing
 * tech-blue particle stream; the highest level renders a flowing rainbow stream.
 */
export function EffortSlider({
  choices,
  value,
  onChange,
  disabled = false,
  compact = false,
  showTicks = false,
  trackWidth,
  ariaLabel = '思考强度',
}: {
  choices: readonly string[]
  value: string
  onChange: (next: string) => void
  disabled?: boolean
  compact?: boolean
  showTicks?: boolean
  trackWidth?: string
  ariaLabel?: string
}) {
  const trackRef = useRef<HTMLDivElement | null>(null)
  const count = choices.length
  const rawIdx = choices.indexOf(value)
  const activeIdx = rawIdx >= 0 ? rawIdx : 0
  const activeValue = choices[activeIdx] ?? ''
  const isDefault = isDefaultEffort(activeValue)
  const isMax = isMaxEffort(activeValue, choices)

  // Map index to thumb position along the padded track [14px .. 100% - 14px].
  const ratio = count <= 1 ? 0 : activeIdx / (count - 1)
  const thumbLeft = `calc(14px + (100% - 28px) * ${ratio})`
  const fillWidth = isDefault && activeIdx === 0
    ? '28px'
    : `calc(28px + (100% - 28px) * ${ratio})`

  function pickAtClientX(clientX: number) {
    if (disabled || count <= 1) return
    const rect = trackRef.current?.getBoundingClientRect()
    if (!rect || rect.width <= 0) return
    const clamped = Math.min(Math.max((clientX - rect.left - 14) / Math.max(1, rect.width - 28), 0), 1)
    const nextIdx = Math.round(clamped * (count - 1))
    const nextVal = choices[nextIdx]
    if (nextVal !== undefined && nextVal !== value) {
      onChange(nextVal)
    }
  }

  function onPointerDown(event: PointerEvent<HTMLDivElement>) {
    if (disabled) return
    event.currentTarget.setPointerCapture(event.pointerId)
    pickAtClientX(event.clientX)
  }

  function onPointerMove(event: PointerEvent<HTMLDivElement>) {
    if (disabled || event.buttons !== 1) return
    pickAtClientX(event.clientX)
  }

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (disabled || count <= 1) return
    let nextIdx = activeIdx
    if (event.key === 'ArrowRight' || event.key === 'ArrowUp') {
      nextIdx = Math.min(count - 1, activeIdx + 1)
    } else if (event.key === 'ArrowLeft' || event.key === 'ArrowDown') {
      nextIdx = Math.max(0, activeIdx - 1)
    } else if (event.key === 'Home') {
      nextIdx = 0
    } else if (event.key === 'End') {
      nextIdx = count - 1
    } else {
      return
    }
    event.preventDefault()
    const nextVal = choices[nextIdx]
    if (nextVal !== undefined && nextVal !== value) {
      onChange(nextVal)
    }
  }

  return (
    <div className={css.root} style={trackWidth != null ? { width: trackWidth, margin: '0 auto' } : undefined}>
      <div
        ref={trackRef}
        role="slider"
        tabIndex={disabled ? -1 : 0}
        aria-label={ariaLabel}
        aria-valuemin={0}
        aria-valuemax={Math.max(0, count - 1)}
        aria-valuenow={activeIdx}
        aria-valuetext={effortDisplayLabel(activeValue)}
        aria-disabled={disabled}
        className={clsx(css.track, compact && css.trackCompact, disabled && css.trackDisabled)}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onKeyDown={onKeyDown}
      >
        <div className={css.fillClip}>
          <div
            className={clsx(
              css.fill,
              isDefault ? css.fillDefault : isMax ? css.fillRainbow : css.fillBlue,
            )}
            style={{ width: fillWidth }}
          >
            {!isDefault && (
              <div className={clsx(css.particles, isMax && css.particlesFast)} />
            )}
          </div>
        </div>

        <div className={css.stopsRow} aria-hidden="true">
          {choices.map((choice, idx) => (
            <span
              key={choice || '__default__'}
              className={clsx(css.stopDot, !isDefault && idx <= activeIdx && css.stopDotFilled)}
            />
          ))}
        </div>

        <span
          className={clsx(css.thumb, compact && css.thumbCompact, isMax && css.thumbRainbow)}
          style={{ left: thumbLeft }}
          aria-hidden="true"
        />
      </div>

      {showTicks && count > 1 && (
        <div className={css.ticks}>
          {choices.map((choice, idx) => (
            <span
              key={choice || '__default__'}
              className={clsx(idx === activeIdx && css.tickActive)}
            >
              {effortDisplayLabel(choice)}
            </span>
          ))}
        </div>
      )}
    </div>
  )
}

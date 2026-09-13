/** ModelSelect: the composer's model seat beside the send button.
 * Two-level selection per figma 496:26454's MenuDropdown: the root menu is
 * the Model / Effort row pair (label + current value + a right chevron),
 * each drilling into its own list — the model list over the gateway's
 * /api/session/context catalogue, and the effort levels. The trigger
 * (313:14108's ToggleButton) shows both: model name + effort in the
 * caption tone.
 *
 * Upstream: dsh packages/client/ui-model-selection/src/ModelSelect.tsx.
 * Cuts: the per-session cordis ModelDirectory store (the selection rides
 * plain props fetched by the owner), the provider-grouped catalogue (the
 * gateway serves one flat list — built-in families plus custom models), the
 * shared-catalog load lifecycle (each open refetches; this app's catalogue
 * is a synchronous registry snapshot, no lazy generations to track), and
 * the rejection Toast (an in-menu error strip anchored to the card keeps
 * the failure visible without the primitive's transient machinery).
 * Kept: the trigger chrome, the two-level pane drill with Escape backing
 * out one level, the portaled right-aligned placement, and the selection
 * marker as the trailing check. */

import {
  useEffect, useId, useLayoutEffect, useRef, useState,
  type CSSProperties, type KeyboardEvent, type FocusEvent,
} from 'react'
import { createPortal } from 'react-dom'
import clsx from 'clsx'
import {
  IconCheckOutline16, IconChevronDownOutline14, IconChevronRightOutline14,
  IconDataOutline16,
} from '@primitives'
import css from './ModelSelect.module.css'

/** Which pane the dropdown shows: the two-row root or one drilled-in list. */
type Pane = 'root' | 'model' | 'effort'

/** One selectable model row from the gateway's session-context catalogue. */
export interface ModelRow {
  readonly name: string
  readonly label: string
  readonly description?: string
  readonly default: boolean
}

/** The session's current selection, from the same endpoint. */
export interface ModelSeatSelection {
  readonly current: string
  readonly models: readonly ModelRow[]
  readonly effort?: {
    readonly current: string
    readonly effective: string
    readonly choices: readonly string[]
  }
}

export interface ModelSelectStrings {
  readonly triggerLoading: string
  readonly triggerFallback: string
  readonly menuAria: string
  readonly menuModel: string
  readonly menuEffort: string
  readonly effortDefault: string
  readonly emptyModels: string
  readonly emptyEfforts: string
  readonly loadError: (message: string) => string
  readonly selectError: (message: string) => string
}

/** Unplaced portal card: hidden but laid out at a fixed origin so offsetWidth/offsetHeight are real (Menu primitive's measure pass). */
const MEASURE_STYLE: CSSProperties = { visibility: 'hidden', left: 0, top: 0 }

/**
 * Render the composer model seat.
 * @param props - the catalogue + current selection as plain props (the
 * gateway endpoint answer), the select verb, and the label strings.
 */
export function ModelSelect(
  { selection, available, busy, select, selectEffort, s }: {
    selection: ModelSeatSelection | null
    available: boolean
    busy: boolean
    select: (model: string) => Promise<string | null>
    selectEffort: (effort: string) => Promise<string | null>
    s: ModelSelectStrings
  },
) {
  const [open, setOpen] = useState(false)
  const [pane, setPane] = useState<Pane>('root')
  const [error, setError] = useState<string | null>(null)
  const rootRef = useRef<HTMLDivElement | null>(null)
  const triggerRef = useRef<HTMLButtonElement | null>(null)
  const menuRef = useRef<HTMLDivElement | null>(null)
  const [menuPos, setMenuPos] = useState<CSSProperties | null>(null)
  const itemRefs = useRef<(HTMLButtonElement | null)[]>([])
  const id = useId()

  const choices = selection?.models ?? []
  const currentRow = choices.find(row => row.name === selection?.current)
  const effort = selection?.effort
  // The effective level (the server-resolved default when `current` is
  // `auto`) drives every readout, matching upstream: the trigger shows the
  // resolved level (`Sonnet · high`), never a "Default" stand-in, unless the
  // model has no default and the provider's own choice applies.
  const effectiveEffort = effort?.effective !== '' && effort?.effective !== undefined
    ? effort.effective
    : effort?.current ?? effort?.effective

  useEffect(() => {
    if (!open) return
    const closeOutside = (event: MouseEvent): void => {
      // The portaled card is outside the trigger subtree; check both.
      if (rootRef.current?.contains(event.target as Node) === true) return
      if (menuRef.current?.contains(event.target as Node) === true) return
      setOpen(false)
    }
    document.addEventListener('mousedown', closeOutside)
    return () => { document.removeEventListener('mousedown', closeOutside) }
  }, [open])

  // Portaled placement (the Menu primitive's portal rules: fixed from the
  // anchor rect, measured before paint, clamped inside the viewport): above
  // the trigger, right edges aligned. Depends on pane because pane switches
  // resize the card.
  useLayoutEffect(() => {
    if (!open) { setMenuPos(null); return }
    const place = (): void => {
      const rect = triggerRef.current?.getBoundingClientRect()
      if (rect === undefined) return
      const MARGIN = 12
      const lw = menuRef.current?.offsetWidth ?? 0
      const lh = menuRef.current?.offsetHeight ?? 0
      let x = rect.right - lw
      let y = rect.top - 8 - lh
      if (lw > 0) x = Math.min(Math.max(x, MARGIN), window.innerWidth - lw - MARGIN)
      if (lh > 0) y = Math.min(Math.max(y, MARGIN), window.innerHeight - lh - MARGIN)
      setMenuPos({ left: x, top: y })
    }
    // First run measures the hidden pre-render (same commit as `open`), so
    // the card lands placed before anything paints.
    place()
    window.addEventListener('scroll', place, true)
    window.addEventListener('resize', place)
    return () => {
      window.removeEventListener('scroll', place, true)
      window.removeEventListener('resize', place)
    }
  }, [open, pane, selection])

  if (!available) return null

  const show = (): void => {
    setPane('root')
    setError(null)
    setOpen(true)
  }

  const close = (restoreFocus = false): void => {
    setOpen(false)
    setPane('root')
    setError(null)
    if (restoreFocus) queueMicrotask(() => { triggerRef.current?.focus() })
  }

  const moveFocus = (offset: number): void => {
    const items = itemRefs.current.filter(item => item !== null)
    if (items.length === 0) return
    const active = items.findIndex(item => item === document.activeElement)
    const next = (Math.max(active, 0) + offset + items.length) % items.length
    items[next]?.focus()
  }

  const onRootKeyDown = (event: KeyboardEvent<HTMLDivElement>): void => {
    if (event.key === 'Escape' && open) {
      event.preventDefault()
      // Escape backs out of a drilled pane first, then closes.
      if (pane !== 'root') setPane('root')
      else close(true)
      return
    }
    if (!open) return
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      moveFocus(event.key === 'ArrowDown' ? 1 : -1)
    }
  }

  const onBlur = (event: FocusEvent<HTMLDivElement>): void => {
    if (event.relatedTarget instanceof Node && (
      rootRef.current?.contains(event.relatedTarget) === true
      || menuRef.current?.contains(event.relatedTarget) === true
    )) return
    close()
  }

  const settle = (failure: string | null): void => {
    if (failure === null) {
      if (rootRef.current !== null) close(true)
      return
    }
    setError(failure)
  }

  const choose = (model: string): void => {
    if (selection?.current === model) {
      close(true)
      return
    }
    void select(model).then(settle)
  }

  const chooseEffort = (level: string): void => {
    if (effectiveEffort === level) {
      close(true)
      return
    }
    void selectEffort(level).then(settle)
  }

  const modelLabel = selection == null
    ? s.triggerFallback
    : currentRow?.label ?? selection.current
  // Upstream's readout: the resolved level name when one applies, the
  // provider-default label only when no level resolves (the provider's own
  // default governs — e.g. an unknown custom endpoint).
  const effortLabel = effort === undefined
    ? undefined
    : effectiveEffort === 'auto' || effectiveEffort === undefined || effectiveEffort === ''
      ? s.effortDefault
      : effectiveEffort
  const triggerLabel = effortLabel === undefined ? modelLabel : `${modelLabel} · ${effortLabel}`

  itemRefs.current = []
  let itemIndex = 0
  const itemRef = () => {
    const at = itemIndex++
    return (node: HTMLButtonElement | null) => { itemRefs.current[at] = node }
  }

  return (
    <div ref={rootRef} className={css.root} onKeyDown={onRootKeyDown} onBlur={onBlur}>
      <button
        ref={triggerRef}
        type="button"
        className={css.trigger}
        aria-label={triggerLabel}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? `${id}-menu` : undefined}
        title={triggerLabel}
        onClick={() => {
          if (open) {
            close()
          } else {
            show()
          }
        }}
      >
        <IconDataOutline16 className={css.triggerIcon} size={16} />
        <span className={css.triggerLabel}>{modelLabel}</span>
        {effortLabel !== undefined && <span className={css.triggerEffort}>{effortLabel}</span>}
        <IconChevronDownOutline14 className={clsx(css.chevron, open && css.chevronOpen)} />
      </button>

      {/* Portaled to body (Menu primitive's portal mode) so the sidebar and
          column overflow clips cannot crop the card; synthetic events still
          bubble through this React subtree, keeping onKeyDown/onBlur live. */}
      {open && createPortal(
        <div
          ref={menuRef}
          id={`${id}-menu`}
          className={css.menu}
          style={menuPos ?? MEASURE_STYLE}
          role="menu"
          aria-label={s.menuAria}
        >
          {pane === 'root' && (
            <>
              <button ref={itemRef()} type="button" role="menuitem" className={css.cell} onClick={() => { setPane('model') }}>
                <span className={css.cellLabel}>{s.menuModel}</span>
                <span className={css.cellValue}>{modelLabel}</span>
                <IconChevronRightOutline14 className={css.cellChevron} />
              </button>
              {effort !== undefined && (
                <button ref={itemRef()} type="button" role="menuitem" className={css.cell} onClick={() => { setPane('effort') }}>
                  <span className={css.cellLabel}>{s.menuEffort}</span>
                  <span className={css.cellValue}>{effortLabel}</span>
                  <IconChevronRightOutline14 className={css.cellChevron} />
                </button>
              )}
            </>
          )}

          {pane === 'model' && (
            <>
              {error !== null && (
                <div className={css.error}>{s.selectError(error)}</div>
              )}
              <div className={clsx(css.groups, 'scrollable')}>
                {choices.map((row) => {
                  const selected = selection?.current === row.name
                  return (
                    <button
                      ref={itemRef()}
                      type="button"
                      role="menuitemradio"
                      aria-checked={selected}
                      className={clsx(css.option, selected && css.selected)}
                      key={row.name}
                      title={row.label}
                      disabled={busy}
                      onClick={() => { choose(row.name) }}
                    >
                      <span className={css.optionCopy}>
                        <span className={css.modelName}>{row.label}</span>
                      </span>
                      <span className={css.check}>
                        {selected ? <IconCheckOutline16 /> : null}
                      </span>
                    </button>
                  )
                })}
              </div>
              {choices.length === 0 && (
                <div className={css.empty}>{s.emptyModels}</div>
              )}
            </>
          )}

          {pane === 'effort' && (
            <>
              {error !== null && (
                <div className={css.error}>{s.selectError(error)}</div>
              )}
              {effort === undefined || effort.choices.length === 0
                ? <div className={css.empty}>{s.emptyEfforts}</div>
                : effort.choices.map(level => (
                  <button
                    ref={itemRef()}
                    type="button"
                    role="menuitemradio"
                    aria-checked={effectiveEffort === level}
                    className={clsx(css.option, effectiveEffort === level && css.selected)}
                    key={level}
                    disabled={busy}
                    onClick={() => { chooseEffort(level) }}
                  >
                    <span className={css.optionCopy}>
                      <span className={css.modelName}>
                        {level === 'auto' ? s.effortDefault : level}
                      </span>
                    </span>
                    <span className={css.check}>
                      {effectiveEffort === level ? <IconCheckOutline16 /> : null}
                    </span>
                  </button>
                ))}
            </>
          )}
        </div>,
        document.body,
      )}
    </div>
  )
}

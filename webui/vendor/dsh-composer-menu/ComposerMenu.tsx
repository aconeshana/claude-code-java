/**
 * Composer candidate menu — vendored from dsh's ui-input-trigger
 * `src/client/MenuView.tsx`, rendered into the composer card's overlay
 * anchor (the InputBar card is `position: relative`, matching upstream's
 * `[data-composer-card]` anchor).
 *
 * Kept from upstream: the combobox pattern (focus never leaves the composer —
 * rows are mousedown-handled and the highlight rides
 * `aria-activedescendant`), the shared pointer/keyboard highlight, the
 * outside-close rule (a pointer press outside the menu AND outside the
 * composer card dismisses it — presses on the textarea or button row keep
 * it open), the skeleton fallback while the first fetch is pending, the
 * section-title grouping, and the row reading order: title, then the
 * command-name alias when the title is not the name in another letter case,
 * then the description right-aligned.
 *
 * Cut from upstream: the breadcrumb header and the drill seat (Tab keycap +
 * chevron) — both serve @-reference directory descent this menu has no
 * equivalent for — and the multi-source store plumbing (this app has one
 * command source, so the menu state arrives as one plain prop).
 */
import { Fragment, useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import clsx from 'clsx'
import { ReferenceIcon, useAnchoredMaxHeight } from '@primitives'
import type { MenuCandidate, MenuState } from './menuCore'
import css from './ComposerMenu.module.css'

/** Height cap that fits the two headings and eight built-in command rows. */
const MAX_HEIGHT = 400

/** DOM id of one option row (the aria-activedescendant target). */
function optionId(source: string, index: number): string {
  return `ccj-composer-option-${source}-${index}`
}

export interface ComposerMenuProps {
  /** The reducer state driving the render; `open === false` renders null. */
  readonly state: MenuState
  /** Settling pick of one row (mousedown — the composer keeps focus). */
  readonly onPick: (source: string, index: number) => void
  /** Pointer parking of the shared highlight (mousemove, not mouseenter). */
  readonly onHover: (source: string, index: number) => void
  /** Dismiss request: Escape key or a pointer press outside card and menu. */
  readonly onDismiss: () => void
  /** Group title for one source; the section headings carry themselves. */
  readonly groupTitle: (source: string) => string
  /** The pending-group notice under the skeleton rows. */
  readonly loadingLabel: string
  /** The listbox aria label. */
  readonly listboxLabel: string
}

/**
 * Render the candidate menu overlay.
 * @returns the dropdown while open; null while closed.
 */
export function ComposerMenu({
  state, onPick, onHover, onDismiss, groupTitle, loadingLabel, listboxLabel,
}: ComposerMenuProps) {
  const listRef = useRef<HTMLDivElement>(null)
  const viewportRef = useRef<HTMLDivElement>(null)
  const [hasOverflowBelow, setHasOverflowBelow] = useState(false)
  // The list is bottom-anchored above the composer; clamp the design cap to
  // the space above it, re-measured on every state update (the anchor moves
  // when the composer grows).
  const maxHeight = useAnchoredMaxHeight(listRef, MAX_HEIGHT, state)
  const updateOverflowHint = useCallback(() => {
    const viewport = viewportRef.current
    setHasOverflowBelow(viewport !== null
      && viewport.scrollTop + viewport.clientHeight < viewport.scrollHeight - 1)
  }, [])
  useLayoutEffect(() => {
    updateOverflowHint()
  }, [state, maxHeight, updateOverflowHint])
  const highlight = state.open ? state.highlight : null
  // Focus stays in the composer (combobox pattern), so the browser never
  // scrolls the active option into view on keyboard moves — do it here.
  useEffect(() => {
    if (highlight === null) return
    document.getElementById(optionId(highlight.source, highlight.index))
      ?.scrollIntoView({ block: 'nearest' })
  }, [highlight])
  // Dismiss on pointer outside the menu AND outside the composer card
  // (clicking the textarea or bottom bar must not close the menu).
  useEffect(() => {
    if (!state.open) return
    const onPointerDown = (ev: PointerEvent): void => {
      if (!(ev.target instanceof Node)) return
      if (listRef.current?.contains(ev.target)) return
      const composerCard = listRef.current?.closest('.ccj-composer-card')
      if (composerCard?.contains(ev.target)) return
      onDismiss()
    }
    document.addEventListener('pointerdown', onPointerDown, true)
    return () => { document.removeEventListener('pointerdown', onPointerDown, true) }
  }, [state.open, onDismiss])
  if (!state.open) return null
  return (
    // The listbox role sits on the scrolling viewport, not this shell.
    <div
      ref={listRef}
      className={css.menu}
      style={{ maxHeight }}
      data-overflow-below={hasOverflowBelow || undefined}
    >
      <div
        ref={viewportRef}
        className={css.viewport}
        role="listbox"
        aria-label={listboxLabel}
        aria-activedescendant={highlight !== null
          ? optionId(highlight.source, highlight.index) : undefined}
        onScroll={updateOverflowHint}
      >
        {state.groups.map(group => (group.status === 'ready' && group.items.length === 0
          ? null
          : (
            <Fragment key={group.source}>
              {group.showGroupTitle === false || group.items.some(item => item.section !== undefined)
                ? null
                : <div className={css.groupTitle} role="presentation" data-source={group.source}>{groupTitle(group.source)}</div>}
              {group.status === 'pending' && group.items.length === 0
                ? (
                  <div role="status" aria-label={loadingLabel} data-source={group.source}>
                    <div className={css.skeletonRow}><span className={css.skeletonBar} style={{ width: '32%' }} /></div>
                    <div className={css.skeletonRow}><span className={css.skeletonBar} style={{ width: '48%' }} /></div>
                  </div>
                )
                : group.items.map((item, index) => {
                  const active = highlight !== null && highlight.source === group.source && highlight.index === index
                  return (
                    <Fragment key={optionId(group.source, index)}>
                      {item.section !== undefined && item.section !== group.items[index - 1]?.section
                        ? <div className={css.sectionTitle} role="presentation">{item.section}</div>
                        : null}
                      <button
                        id={optionId(group.source, index)}
                        type="button"
                        role="option"
                        aria-selected={active}
                        className={clsx(css.item, active && css.active)}
                        // mousedown, not click: the composer keeps focus (combobox
                        // pattern) — preventing default stops the focus steal, and the
                        // pick runs before any blur-driven teardown.
                        onMouseDown={(ev) => {
                          ev.preventDefault()
                          onPick(group.source, index)
                        }}
                        // mousemove, not mouseenter: real pointer motion moves the
                        // shared highlight; keyboard scrolling rows under a resting
                        // pointer must not steal it back.
                        onMouseMove={active ? undefined : () => { onHover(group.source, index) }}
                      >
                        {item.icon !== undefined && (
                          <span className={css.itemIcon} aria-hidden>
                            {typeof item.icon === 'string'
                              ? <ReferenceIcon kind={item.icon} size={16} />
                              : <item.icon size={16} />}
                          </span>
                        )}
                        <span className={css.itemName}>{item.label ?? item.name}</span>
                        {item.label !== undefined && item.label.toLowerCase() !== item.name.toLowerCase() && (
                          <span className={css.itemAlias}>{item.name}</span>
                        )}
                        {item.description !== undefined && <span className={css.itemDescription}>{item.description}</span>}
                      </button>
                    </Fragment>
                  )
                })}
            </Fragment>
          )))}
      </div>
    </div>
  )
}

export type { MenuCandidate }

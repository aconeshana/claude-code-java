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
  IconDataOutline16, IconSettingsOutline16,
} from '@primitives'
import css from './ModelSelect.module.css'
import { EffortSlider, effortDisplayLabel, isDefaultEffort, isMaxEffort } from '../../src/views/EffortSlider'

/** Which pane the dropdown shows: the two-row root or one drilled-in list. */
type Pane = 'root' | 'model' | 'effort'

/** One selectable model row from the gateway's session-context catalogue. */
export interface ModelRow {
  readonly name: string
  readonly label: string
  readonly description?: string
  readonly default: boolean
  readonly sourcePlugin?: string | undefined
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
  { selection, available, busy, select, selectEffort, onManagePlugin, s }: {
    selection: ModelSeatSelection | null
    available: boolean
    busy: boolean
    select: (model: string) => Promise<string | null>
    selectEffort: (effort: string) => Promise<string | null>
    onManagePlugin?: ((pluginId: string) => void) | undefined
    s: ModelSelectStrings
  },
) {
  const [open, setOpen] = useState(false)
  const [pane, setPane] = useState<Pane>('root')
  const [activeGroup, setActiveGroup] = useState<string | null>(null)
  const [modelQuery, setModelQuery] = useState('')
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
    : (currentRow?.label || selection.current || s.triggerFallback)
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
          {pane === 'root' && (() => {
            const supportedChoices = (effort?.choices ?? []).filter(
              (level) => level.trim().toLowerCase() !== 'ultracode',
            )
            // Drive the slider thumb from the user's explicit selection (`effort.current`)
            // rather than `effort.effective`, so sliding left to `auto` (默认) stays at
            // `默认` instead of bouncing back to the model's default effective level (e.g. high).
            const explicitCurrent = effort?.current ?? ''
            const currentEffortVal = supportedChoices.includes(explicitCurrent)
              ? explicitCurrent
              : supportedChoices.includes(effectiveEffort ?? '')
                ? (effectiveEffort ?? '')
                : (supportedChoices[0] ?? '')
            // Shorter track when a model supports fewer levels, inside a constant-width popover.
            const trackPct = supportedChoices.length >= 5
              ? '100%'
              : `${Math.max(58, Math.round((supportedChoices.length / 5) * 100))}%`
            const friendlyError = error == null
              ? null
              : /unsupported effort/i.test(error)
                ? '当前模型不支持该思考强度'
                : s.selectError(error)
            return (
              <div className={css.codexRoot}>
                {friendlyError !== null && (
                  <div className={css.error}>{friendlyError}</div>
                )}
                <button ref={itemRef()} type="button" role="menuitem" className={css.codexModelRow} onClick={() => { setPane('model') }}>
                  <span className={css.codexModelName}>{modelLabel}</span>
                  {supportedChoices.length > 0 && (
                    <span
                      className={clsx(
                        css.codexEffortBadge,
                        !isDefaultEffort(currentEffortVal) && (
                          isMaxEffort(currentEffortVal, supportedChoices)
                            ? css.codexEffortRainbow
                            : css.codexEffortBlue
                        ),
                      )}
                    >
                      {effortDisplayLabel(currentEffortVal)}
                    </span>
                  )}
                  <IconChevronRightOutline14 className={css.cellChevron} />
                </button>
                {supportedChoices.length > 1 && (
                  <div className={css.codexSliderWrap}>
                    <EffortSlider
                      choices={supportedChoices}
                      value={currentEffortVal}
                      disabled={busy}
                      compact
                      trackWidth={trackPct}
                      onChange={(level) => {
                        setError(null)
                        void selectEffort(level).then((msg) => {
                          if (msg !== null) setError(msg)
                        })
                      }}
                      ariaLabel={s.menuEffort}
                    />
                  </div>
                )}
              </div>
            )
          })()}

          {pane === 'model' && (() => {
            const brandDotColor = (name: string): string => {
              const lower = name.toLowerCase()
              if (lower.includes('claude') || lower.includes('anthropic')) return '#d97757'
              if (lower.includes('deepseek')) return '#4d6bfe'
              if (lower.includes('glm') || lower.includes('zhipu')) return '#10b981'
              if (lower.includes('qwen')) return '#7c3aed'
              if (lower.includes('kimi') || lower.includes('moonshot')) return '#f59e0b'
              if (lower.includes('doubao') || lower.includes('seed')) return '#0ea5e9'
              if (lower.includes('minimax')) return '#ec4899'
              if (lower.includes('gemini')) return '#3b82f6'
              if (lower.includes('gpt') || lower.includes('o3') || lower.includes('o4')) return '#16a34a'
              return '#8b95a5'
            }

            const q = modelQuery.trim().toLowerCase()
            const filteredChoices = q === ''
              ? choices
              : choices.filter((r) => r.name.toLowerCase().includes(q) || r.label.toLowerCase().includes(q))

            const flat: ModelRow[] = []
            const groupMap = new Map<string, ModelRow[]>()
            for (const row of filteredChoices) {
              const slash = row.name.indexOf('/')
              if (slash > 0) {
                const g = row.name.slice(0, slash)
                const list = groupMap.get(g) ?? []
                list.push(row)
                groupMap.set(g, list)
              } else {
                flat.push(row)
              }
            }
            const groups = [...groupMap.entries()]
            const drilledRows = q === '' && activeGroup != null ? (groupMap.get(activeGroup) ?? []) : null

            const renderModelButton = (row: ModelRow, displayName: string) => {
              const selected = selection?.current === row.name
              const dotColor = brandDotColor(displayName)
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
                  style={{ padding: '7px 10px', borderRadius: 8 }}
                  onClick={() => {
                    setModelQuery('')
                    setActiveGroup(null)
                    choose(row.name)
                  }}
                >
                  <span
                    aria-hidden="true"
                    style={{
                      width: 7,
                      height: 7,
                      borderRadius: '50%',
                      background: dotColor,
                      flexShrink: 0,
                      marginRight: 8,
                    }}
                  />
                  <span className={css.optionCopy}>
                    <span className={css.modelName} style={{ fontSize: 12.5 }}>{displayName}</span>
                  </span>
                  <span className={css.check}>
                    {selected ? <IconCheckOutline16 /> : null}
                  </span>
                </button>
              )
            }

            return (
              <>
                {error !== null && (
                  <div className={css.error}>{s.selectError(error)}</div>
                )}
                {choices.length > 5 && (
                  <div className={css.searchWrap}>
                    <input
                      type="text"
                      value={modelQuery}
                      placeholder="搜索模型…"
                      aria-label="搜索模型"
                      className={css.searchInput}
                      onChange={(e) => { setModelQuery(e.target.value) }}
                      onKeyDown={(e) => { e.stopPropagation() }}
                    />
                  </div>
                )}
                {drilledRows != null ? (
                  <div className={clsx(css.groups, 'scrollable')} style={{ padding: '4px 6px 6px' }}>
                    {(() => {
                      const groupPluginId = drilledRows.find((r) => r.sourcePlugin)?.sourcePlugin
                      return (
                        <div style={{ display: 'flex', alignItems: 'center', gap: 4, marginBottom: 4, minWidth: 0 }}>
                          <button
                            ref={itemRef()}
                            type="button"
                            role="menuitem"
                            className={css.option}
                            onClick={() => { setActiveGroup(null) }}
                            style={{
                              flex: 1,
                              minWidth: 0,
                              width: 'auto',
                              padding: '6px 10px',
                              borderRadius: 8,
                              fontSize: 12,
                              fontWeight: 600,
                              opacity: 0.72,
                            }}
                          >
                            <span className={css.optionCopy}>
                              <span className={css.modelName}>{`‹ ${activeGroup}`}</span>
                            </span>
                          </button>
                          {groupPluginId && onManagePlugin && (
                            <button
                              type="button"
                              title="打开桥接管理"
                              aria-label="打开桥接管理"
                              onClick={(e) => {
                                e.stopPropagation()
                                close()
                                onManagePlugin(groupPluginId)
                              }}
                              style={{
                                display: 'inline-flex',
                                alignItems: 'center',
                                gap: 4,
                                padding: '5px 8px',
                                borderRadius: 7,
                                border: '1px solid var(--dsw-alias-border-def, rgba(128,128,128,0.18))',
                                background: 'var(--dsw-alias-bg-subtle, rgba(128,128,128,0.06))',
                                color: 'inherit',
                                fontSize: 11,
                                fontWeight: 500,
                                opacity: 0.8,
                                cursor: 'pointer',
                                flexShrink: 0,
                              }}
                            >
                              <IconSettingsOutline16 size={12} />
                              <span>管理</span>
                            </button>
                          )}
                        </div>
                      )
                    })()}
                    {drilledRows.map((row) => renderModelButton(row, row.name.slice(activeGroup!.length + 1)))}
                  </div>
                ) : (
                  <div className={clsx(css.groups, 'scrollable')} style={{ padding: '4px 6px 6px' }}>
                    {flat.length > 0 && groups.length > 0 && (
                      <div style={{ fontSize: 10.5, fontWeight: 600, opacity: 0.5, padding: '4px 10px 3px', letterSpacing: '0.04em' }}>
                        MODELS
                      </div>
                    )}
                    {flat.map((row) => renderModelButton(row, row.label))}
                    {groups.length > 0 && (
                      <>
                        {flat.length > 0 && (
                          <div style={{ height: 1, background: 'var(--dsw-alias-border-def, rgba(128,128,128,0.14))', margin: '6px 8px' }} />
                        )}
                        <div style={{ fontSize: 10.5, fontWeight: 600, opacity: 0.5, padding: '3px 10px 3px', letterSpacing: '0.04em' }}>
                          PROVIDERS
                        </div>
                        {q !== ''
                          ? groups.flatMap(([, rows]) => rows.map((row) => renderModelButton(row, row.label)))
                          : groups.map(([groupName, rows]) => {
                            const hasSelected = rows.some((r) => selection?.current === r.name)
                            const groupPluginId = rows.find((r) => r.sourcePlugin)?.sourcePlugin
                            return (
                              <div key={`group:${groupName}`} style={{ display: 'flex', alignItems: 'center', gap: 3, minWidth: 0 }}>
                                <button
                                  ref={itemRef()}
                                  type="button"
                                  role="menuitem"
                                  className={clsx(css.option, hasSelected && css.selected)}
                                  style={{ flex: 1, minWidth: 0, width: 'auto', padding: '7px 10px', borderRadius: 8 }}
                                  onClick={() => { setActiveGroup(groupName) }}
                                >
                                  <span
                                    aria-hidden="true"
                                    style={{
                                      width: 18,
                                      height: 18,
                                      borderRadius: 5,
                                      background: 'var(--dsw-alias-bg-subtle, rgba(128,128,128,0.12))',
                                      display: 'inline-flex',
                                      alignItems: 'center',
                                      justifyContent: 'center',
                                      fontSize: 10,
                                      fontWeight: 700,
                                      flexShrink: 0,
                                      marginRight: 8,
                                    }}
                                  >
                                    {groupName.charAt(0).toUpperCase()}
                                  </span>
                                  <span className={css.optionCopy}>
                                    <span className={css.modelName} style={{ fontSize: 12.5, fontWeight: 500 }}>{groupName}</span>
                                  </span>
                                  <span
                                    style={{
                                      fontSize: 10.5,
                                      padding: '1px 6px',
                                      borderRadius: 999,
                                      background: 'var(--dsw-alias-bg-subtle, rgba(128,128,128,0.12))',
                                      opacity: 0.75,
                                      marginRight: 4,
                                    }}
                                  >
                                    {rows.length}
                                  </span>
                                  <IconChevronRightOutline14 className={css.cellChevron} />
                                </button>
                                {groupPluginId && onManagePlugin && (
                                  <button
                                    type="button"
                                    title="管理此供应商（过滤模型 / 授权）"
                                    aria-label={`管理 ${groupName}`}
                                    onClick={(e) => {
                                      e.stopPropagation()
                                      close()
                                      onManagePlugin(groupPluginId)
                                    }}
                                    style={{
                                      width: 26,
                                      height: 26,
                                      padding: 0,
                                      borderRadius: 7,
                                      border: '1px solid var(--dsw-alias-border-def, rgba(128,128,128,0.16))',
                                      background: 'var(--dsw-alias-bg-subtle, rgba(128,128,128,0.06))',
                                      color: 'inherit',
                                      opacity: 0.72,
                                      display: 'inline-flex',
                                      alignItems: 'center',
                                      justifyContent: 'center',
                                      cursor: 'pointer',
                                      flexShrink: 0,
                                    }}
                                  >
                                    <IconSettingsOutline16 size={13} />
                                  </button>
                                )}
                              </div>
                            )
                          })}
                      </>
                    )}
                  </div>
                )}
                {filteredChoices.length === 0 && (
                  <div className={css.empty}>{s.emptyModels}</div>
                )}
              </>
            )
          })()}

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

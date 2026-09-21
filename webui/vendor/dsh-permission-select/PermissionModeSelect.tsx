/**
 * PermissionModeSelect: the composer's permission-mode chip, the webui
 * counterpart of the TUI's Shift+Tab cycle (PermissionModeCycle.java).
 *
 * Upstream: dsh packages/client/ui-permission-presets/src/client/PermissionSelect.tsx
 * — fetched verbatim via `gh api repos/deepseek-ai/deepseek-harness/contents/...`
 * (not inferred from screenshots or memory; see webui/UPSTREAM.md for the
 * fetch record). That component opens the shared `Menu` primitive
 * (`vendor/ui-primitives/Menu.tsx`) with `side="top"` + `portal` and, notably,
 * passes NO `align` prop — Menu's own default is `align='start'` (left-aligned
 * to the anchor), confirmed by grepping the vendored Menu.tsx. This component
 * must not pass `align="end"`; an earlier revision did, which right-aligned
 * the menu against the real upstream's left-aligned behavior.
 *
 * Position: upstream's InputBar.tsx renders `conversation.input.permission`
 * inside `.tools`'s `.modes` sub-container, immediately after the `+`/attach
 * button and before the trailing `.trailing` group (model select + send) —
 * confirmed by fetching InputBar.tsx. This component is mounted the same way
 * in `webui/src/views/InputBar.tsx`'s `.modes` container, not `.trailing`
 * (an earlier revision mounted it next to ModelSelect in error).
 *
 * Icons: upstream's shield glyphs (design set 1556) compose a shared
 * `SHIELD_OUTLINE_PATH`/`SHIELD_OUTLINE_STROKE` contour (now vendored verbatim
 * in `vendor/ui-primitives/icons/index.tsx`, fetched from
 * `ui-primitives/src/icons/index.tsx`) with a per-preset inner mark for its
 * three Codex-style presets (read-only/workspace-write/danger-full-access).
 * The assistant's `PermissionMode` has five states with no 1:1 correspondence,
 * but three map semantically onto upstream's exact glyphs and are reused
 * verbatim: PLAN (deny writes, allow reads) ~= upstream's "read-only" (shield
 * + check); ACCEPT_EDITS (auto-accept edits within the workspace) ~=
 * upstream's "workspace-write" (the custom edit glyph); BYPASS_PERMISSIONS
 * (skip every check) ~= upstream's "danger-full-access" (shield + exclamation).
 * DEFAULT gets the bare shield contour (no mode mark — there is no upstream
 * "ask every time" preset to borrow from). DONT_ASK gets no icon at all,
 * mirroring upstream's own pattern of leaving its second risky preset
 * (`auto`/AUTO_REVIEW) icon-less — `permissionGlyphs` has no entry for it,
 * and the trigger only renders `.triggerIcon` when a glyph exists. Unlike
 * upstream (shape alone distinguishes its own three presets, no color axis),
 * this component tints each glyph via `colorFor(color_key)` — TUI risk-color
 * parity (`LanternaTheme.colorFor(PermissionMode)`) layered on top of the
 * borrowed shield geometry, not an upstream behavior.
 *
 * Kept: the `Menu`-primitive composer-chip architecture (anchor trigger,
 * `side="top"`, `portal`, trailing-check selection), the real trigger CSS
 * metrics (`inline-flex`, `max-width: 220px`, 14px icon shrink, conditional
 * `:has(.triggerIcon)` label collapse — see PermissionModeSelect.module.css),
 * and risk gating for the two auto-approve modes via `RiskConfirmation`
 * (`@primitives`) — structurally the same gate upstream applies to its own
 * `danger-full-access`/`auto` presets.
 * Cuts: the dynamic host-configurable preset catalog (`usePermissionCatalog`)
 * and badge system (`optionBadge`/`auto.badge`) — the assistant's
 * `PermissionMode` is a fixed six-state enum (five external + one
 * classifier-only `auto`), not a host-supplied catalog, so there is no
 * variable-badge concept to carry.
 *
 * See webui/UPSTREAM.md for the full deviation note.
 */

import { useState } from 'react'
import {
  IconChevronDownOutline14, IconShieldOutline16, Menu, RiskConfirmation,
  SHIELD_OUTLINE_PATH, SHIELD_OUTLINE_STROKE,
} from '@primitives'
import type { MenuEntry } from '@primitives'
import type { PermissionModeChoice, PermissionModeValue, SessionPermissionMode } from '../../src/api/types'
import { useTranslate } from '../../src/i18n/useTranslate'
import { PERMISSION_MODE_NS, permissionModeDicts } from '../../src/i18n/dictionaries/permissionMode'
import css from './PermissionModeSelect.module.css'

/** The two modes that auto-approve every tool call — gated behind RiskConfirmation. */
const RISKY = new Set<PermissionModeValue>(['bypassPermissions', 'dontAsk'])

/**
 * `PermissionMode.ColorKey` → a webui token, tinting the shield glyph.
 * Upstream's own icons carry no color axis (shape alone distinguishes its
 * three presets); the assistant's TUI does color by risk
 * (`LanternaTheme.colorFor`), so this tint is kept as TUI parity layered on
 * top of the borrowed shield geometry, not an upstream behavior. `design-
 * platform.css` has no teal/purple equivalents to the TUI's planTeal()/
 * acceptPurple(), so PLAN_MODE substitutes brand-accent blue and AUTO_ACCEPT
 * substitutes success green (closest semantic fit); ERROR/WARNING keep exact
 * TUI parity. Verified via grep against vendor/theme/styles/design-platform.css.
 */
function colorFor(colorKey: string | undefined): string {
  switch (colorKey) {
    case 'PLAN_MODE': return 'var(--dsw-alias-state-business-primary)'
    case 'AUTO_ACCEPT': return 'var(--dsw-alias-state-success-primary)'
    case 'ERROR': return 'var(--dsw-alias-state-error-primary)'
    case 'WARNING': return 'var(--dsw-alias-state-warn-primary)'
    default: return 'var(--dsw-alias-label-secondary)'
  }
}

/**
 * Shield-family glyphs (real vendored `SHIELD_OUTLINE_PATH`/`_STROKE`, see
 * module doc). `undefined` renders no icon at all — the trigger and menu rows
 * both branch on this the same way upstream's `permissionGlyph` does.
 */
function permissionGlyph(value: PermissionModeValue) {
  switch (value) {
    case 'default':
      return <IconShieldOutline16 />
    case 'plan':
      return (
        <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden>
          <path d={SHIELD_OUTLINE_PATH} stroke="currentColor" strokeWidth={SHIELD_OUTLINE_STROKE} strokeLinejoin="round" />
          <path d="M12.1654 5.7552L8.9447 9.41475C8.73044 9.65816 8.53628 9.8804 8.35774 10.0423C8.1713 10.2114 7.94235 10.3717 7.64016 10.4254C7.48207 10.4535 7.32 10.4552 7.16151 10.4294C6.85843 10.3801 6.62728 10.2223 6.43836 10.0559C6.25752 9.89653 6.06037 9.67732 5.84264 9.43705L4.72925 8.20897L5.63557 7.38707L6.74897 8.61594C6.98603 8.87755 7.12974 9.03533 7.24673 9.13839C7.31033 9.19443 7.34485 9.21476 7.35823 9.22122C7.38068 9.22484 7.40352 9.22515 7.42593 9.22122C7.40522 9.22502 7.42893 9.23294 7.53583 9.136C7.65132 9.03126 7.79316 8.87139 8.02643 8.60638L11.2479 4.94763L12.1654 5.7552Z" fill="currentColor" />
        </svg>
      )
    case 'acceptEdits':
      return (
        <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden>
          <path d="M8.08887 0.251709C8.20479 0.23085 8.32486 0.241168 8.43652 0.282959L15.0215 2.75171C15.2787 2.84819 15.4492 3.09414 15.4492 3.3689V7.0105C15.4492 7.10986 15.4441 7.2081 15.4414 7.30542C15.0285 7.07175 14.5905 6.87695 14.1309 6.73022V3.82495L8.20508 1.60327L2.2793 3.82495V7.0105C2.27936 9.7171 3.4745 11.5379 5.02734 12.7947C5.01025 12.9942 5 13.1962 5 13.4001C5.00001 13.7617 5.02722 14.1169 5.08008 14.4636C2.91555 13.0393 0.961014 10.752 0.960938 7.0105V3.3689C0.960938 3.09417 1.13146 2.84821 1.38867 2.75171L7.97461 0.282959L8.08887 0.251709Z" fill="currentColor" />
          <path d="M11.3525 5.64688V6.85688H5V5.64688H11.3525Z" fill="currentColor" />
          <path d="M9.5824 8.29376V9.50376H5V8.29376H9.5824Z" fill="currentColor" />
          <path d="M14.6647 15.6852H10.0338C10.3878 15.3751 10.7567 15.0517 11.0772 14.7706C11.2531 14.6164 11.4144 14.4746 11.5511 14.3547H14.6647V15.6852Z" fill="currentColor" />
          <path d="M8.14852 14.1308L7.33925 15.4976C7.22458 15.6912 7.42245 15.9194 7.63037 15.8333L9.09785 15.2254L15.0399 10.0719L14.0905 8.97733L8.14852 14.1308Z" fill="currentColor" />
        </svg>
      )
    case 'bypassPermissions':
      return (
        <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden>
          <path d={SHIELD_OUTLINE_PATH} stroke="currentColor" strokeWidth={SHIELD_OUTLINE_STROKE} strokeLinejoin="round" />
          <path d="M9.10094 4.5V8.75939H7.59888V4.5H9.10094Z" fill="currentColor" />
          <path d="M9.10094 9.8114V11.5H7.59888V9.8114H9.10094Z" fill="currentColor" />
        </svg>
      )
    case 'dontAsk':
      return undefined
    default:
      return undefined
  }
}

export function PermissionModeSelect(
  { state, busy, select }: {
    state: SessionPermissionMode | null
    busy: boolean
    select: (mode: string) => Promise<string | null>
  },
) {
  const t = useTranslate(PERMISSION_MODE_NS, permissionModeDicts)
  const [open, setOpen] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [confirmChoice, setConfirmChoice] = useState<PermissionModeChoice | null>(null)
  const [acknowledged, setAcknowledged] = useState(false)
  const [applying, setApplying] = useState(false)

  if (state == null) return null

  const choices = state.modes
  const current = choices.find(choice => choice.value === state.current)
  const triggerLabel = current?.short_title ?? t('trigger.fallback')
  const currentGlyph = current === undefined ? undefined : permissionGlyph(current.value)
  const currentIcon = currentGlyph === undefined
    ? undefined
    : <span style={{ color: colorFor(current?.color_key) }}>{currentGlyph}</span>

  const applyMode = (mode: PermissionModeValue): void => {
    setApplying(true)
    void select(mode).then((failure) => {
      setApplying(false)
      if (failure === null) {
        setConfirmChoice(null)
        setAcknowledged(false)
        return
      }
      setError(failure)
    })
  }

  const choose = (choice: PermissionModeChoice): void => {
    setOpen(false)
    if (choice.value === state.current) return
    if (RISKY.has(choice.value)) {
      setConfirmChoice(choice)
      return
    }
    applyMode(choice.value)
  }

  const reasonFor = (choice: PermissionModeChoice): string | undefined => {
    if (choice.available) return choice.title
    return choice.value === 'bypassPermissions'
      ? (state.bypass_permissions_disabled_by_policy ? t('bypassUnavailable.policy') : t('bypassUnavailable.flag'))
      : choice.title
  }

  const items: MenuEntry[] = [
    ...(error !== null
      ? [{ id: '__error', label: t('selectError', { message: error }), disabled: true, danger: true } satisfies MenuEntry]
      : []),
    ...choices.map((choice): MenuEntry => {
      const glyph = permissionGlyph(choice.value)
      const icon = glyph === undefined ? undefined : <span style={{ color: colorFor(choice.color_key) }}>{glyph}</span>
      return {
        id: choice.value,
        label: <span title={reasonFor(choice)}>{t(`mode.${choice.value}.title`)}</span>,
        disabled: busy || applying || !choice.available,
        danger: RISKY.has(choice.value),
        ...icon === undefined ? {} : { icon },
      }
    }),
  ]

  return (
    <>
      <Menu
        open={open}
        onClose={() => { setOpen(false); setError(null) }}
        items={items}
        selectedId={state.current}
        onSelect={(id) => {
          const choice = choices.find(candidate => candidate.value === id)
          if (choice !== undefined) choose(choice)
        }}
        side="top"
        portal
        anchor={(
          <button
            type="button"
            className={css.trigger}
            aria-label={t('trigger.aria', { title: triggerLabel })}
            aria-haspopup="menu"
            aria-expanded={open}
            title={triggerLabel}
            disabled={busy}
            onClick={() => { setError(null); setOpen((was) => !was) }}
          >
            {currentIcon !== undefined && (
              <span className={css.triggerIcon} aria-hidden>{currentIcon}</span>
            )}
            <span className={css.triggerLabel}>{triggerLabel}</span>
            <span className={open ? css.chevronOpen : css.chevron} aria-hidden>
              <IconChevronDownOutline14 />
            </span>
          </button>
        )}
      />

      {confirmChoice !== null && (
        <RiskConfirmation
          open
          title={t(`mode.${confirmChoice.value}.confirmTitle`)}
          description={t(`mode.${confirmChoice.value}.confirmDescription`)}
          acknowledgeLabel={t('confirm.acknowledge')}
          cancelLabel={t('confirm.cancel')}
          closeLabel={t('confirm.closeAria')}
          confirmLabel={t('confirm.confirm')}
          acknowledged={acknowledged}
          disabled={applying}
          onAcknowledgedChange={setAcknowledged}
          onCancel={() => { setConfirmChoice(null); setAcknowledged(false) }}
          onConfirm={() => { applyMode(confirmChoice.value) }}
        />
      )}
    </>
  )
}

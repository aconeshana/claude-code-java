# Vendored upstream assets

This directory vendors visual assets from [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness)
(MIT licensed) so the webui keeps pixel-level parity with the upstream chat
interface while owning its own protocol adapter (our gateway REST+SSE, not
cordis/Typert RPC).

- Upstream commit: `5dda764ed3aa172535a7967b06ff95d9cbfe536a` (master, 2026-09-08)

## Vendored trees

| Path here | Upstream path | Notes |
|-----------|---------------|-------|
| `vendor/theme/styles/` | `packages/client/ui-theme/src/styles/` | All 6 CSS files copied verbatim (`--dsw-*` design tokens, light/dark via `body[data-ds-dark-theme]`) |
| `vendor/ui-primitives/` | `packages/client/ui-primitives/src/` | Full package source; already cordis-free upstream (runtime deps are public npm packages only) |
| `vendor/dsh-composer-menu/` | `packages/client/ui-input-trigger/` + `ui-commands/` | The composer "+" menu — see the section below |
| `vendor/dsh-stats-pills/` | `packages/client/ui-chat/` | The composer session-stat pills + the turn-tail usage/time pills — see the section below |
| `vendor/dsh-turn-process/` | `packages/client/ui-chat/src/client/` (`contract/turn-process.ts`, `contract/assistant-content.ts`, `conversation-nodes/turn-process.ts`, `conversation-nodes/turn-process-presentation.ts`) | The turn-process fold rules — see the section below |
| `vendor/dsh-user-questions/` | `packages/client/ui-user-questions/` | The `AskUserQuestion` answer card (pager, single/multi-select, custom free text) — see the section below |
| `vendor/chat-styles/` | scattered `.module.css` from `ui-chat` / `ui-conversation` / `ui-approval` / `ui-layout` / `ui-sidebar` / `ui-settings-general` / `ui-permission-presets` / `ui-schedule` | Styles only — the paired `.tsx` files are deeply cordis-coupled upstream, so the components are re-written locally against the same class names |
| `vendor/dsh-context/` | **different upstream**: [bowenliang123/dsh-context](https://github.com/bowenliang123/dsh-context) `src/shared/*` + `src/client/*` (Apache-2.0, own `LICENSE`/`NOTICE` in the directory) | The Context tab / `/context` modal / Context Dashboard / Chat→Context jump — see the section below |

### `vendor/dsh-composer-menu/` — the composer "+" menu

Upstream's composer "+" button and a typed `/` open one menu (ui-commands'
README): an Add section (File, Goal, Plan, Feedback) and a Commands section
(Compact, Permission, Model, Export) in usage order, fed by ui-input-trigger's
menu pipeline over ui-commands' host command catalog. This app's "+" button
previously opened a raw file picker — the wrong control entirely. The menu is
now vendored from upstream's real source with these deliberate cuts:

| File here | Upstream source | Notes |
|-----------|----------------|-------|
| `menuCore.ts` | `ui-input-trigger/src/core/menu.ts` | The pure reducer, kept nearly verbatim: stale-while-revalidate refinement, the shared pointer/keyboard highlight (last input wins), wrap-around move, auto-close on all-empty. Cut: the multi-source roster seeding (this app has one command source — the roster's one group is seeded inline by `InputBar.tsx`'s `openMenu`) and `exactMatch` (its only consumer is the `/`-token adjudication this composer does not have). |
| `ComposerMenu.tsx` | `ui-input-trigger/src/client/MenuView.tsx` | The rendering, kept nearly verbatim: combobox pattern (focus never leaves the composer — rows are mousedown-handled, highlight rides `aria-activedescendant`), outside-close rule (a press outside menu AND card dismisses; presses on the textarea/button row keep it open), skeleton rows while pending, section titles, the title→alias→right-aligned-description row. Cut: the breadcrumb header and the drill seat (Tab keycap + chevron) — both serve `@`-reference directory descent this menu has no equivalent for — and the `useSyncExternalStore` plumbing over the cordis service (state arrives as one plain prop). The outside-close anchor lookup uses `.ccj-composer-card` (the class `InputBar.tsx` adds to the vendored card) instead of upstream's `[data-composer-card]` attribute. |
| `ComposerMenu.module.css` | `ui-input-trigger/src/client/MenuView.module.css` | Verbatim except the same drill/breadcrumb blocks cut from the TSX. All consumed `--dsw-*` tokens exist in the already-vendored `theme/styles/design-platform.css`. |
| `sectionRows.ts` | `ui-commands/src/client/presentation.ts` (`sectionRows`) | Upstream pins each section's rows to a fixed usage-order list (`add: [file, goal, plan, feedback]`, `commands: [compact, permission, model, export]`) and closes the Commands section with unlisted catalog rows. This app's catalog is the gateway's full `/api/commands` listing (every non-hidden registry command, skills included) with no fixed usage order, so: the Add section carries the one client-side addition (the File row) and every catalog row lands in the Commands section in registry order — the same merge shape dsh gets from host commands + client contributions. Upstream's `builtinRowFace` localized-title/icon table is not ported: our catalog rows carry their registry descriptions directly and the only labels are the File row and the section headings, both local. |

Product-scope deviations (documented, not gaps):

- **A pick inserts `/name ` as plain text; there is no argument-claim machine.**
  Upstream's `leadingInput` commands keep the claimed token in the draft and
  route the submit through `command.execute` RPC with argument hints and
  localized claim tokens. This gateway has no command-RPC route — a submitted
  `/name args` line goes through the turn pipeline, which dispatches slash
  commands server-side (`CommandRegistry.dispatchNonInteractive` on the
  headless session path). The menu therefore only inserts the token; Enter
  submits it like any typed slash line.
- **The `hint`/`argument_hint` field is carried but not rendered as ghost
  text.** Upstream renders a claim's hint as CSS generated content after the
  draft; that ghost-text mechanism is part of the claim machine above.
- **No `/`-typed trigger.** Upstream opens the same menu from a typed `/`
  under the caret (the ui-input-trigger detect pipeline). This composer opens
  the menu from the "+" button only; a typed `/` stays plain text. The detect
  pipeline is upstream's most cordis-coupled layer (per-hit candidate fetch
  with generation gating over a service store) and is the one piece that
  would need a real port rather than a trim if it is ever wanted.
- **No fuzzy query filtering.** Upstream's empty-query menu shows sections;
  a typed query ranks every row by name/label (`rankByName` from
  ui-primitives). The "+" menu here opens with an empty query and no search
  input — it is a pick list, not a search box.

### `vendor/dsh-model-select/` — the composer's model seat

Upstream's composer model seat (`conversation.input.model`, the trigger left
of the send button) shows the current model + reasoning effort and opens a
two-level dropdown (figma 496:26454): a root pane of Model/Effort cells,
each drilling into its own list. Vendored from
`ui-model-selection/src/ModelSelect.tsx` with these deliberate cuts:

| File here | Upstream source | Notes |
|-----------|----------------|-------|
| `ModelSelect.tsx` | `ui-model-selection/src/ModelSelect.tsx` | Kept nearly verbatim: the trigger chrome (13/20/500 label + caption-tone effort + chevron, `min(360px,45cqw)` cap, container-query icon fallback), the two-level pane drill with Escape backing out one level first, the portaled right-aligned fixed placement (measure-before-paint, viewport clamp, scroll/resize tracking), the trailing-check selection marker, and the focus model (trigger → items, ArrowUp/Down wrap, blur-out close). Cut: the per-session cordis `ModelDirectory` store (`useSyncExternalStore` + shared directory with generation gating — the selection and catalogue arrive as plain props from `/api/session/context` via `store/sessionContext.ts`), the provider-grouped catalogue rendering (our gateway serves one flat list — built-in families plus custom models — so the grouped `section`/`group` headers collapse to the plain list), the catalog load lifecycle (`.status`/loading rows — the gateway answer is synchronous, no lazy generations), the per-group failure warnings (`.warning` + Retry — no partial group loads exist here), and the rejection `Toast` (a rejected selection surfaces as an in-menu `.error` strip instead; this app renders no composer-anchored toast). |
| `ModelSelect.module.css` | `ui-model-selection/src/ModelSelect.module.css` | Verbatim except the blocks the cut TSX no longer addresses: `.status`/`.warning`/`.retry`, the group blocks (`.group + .group`, `.groupTitle`). All consumed `--dsw-*` tokens resolve across `theme/styles/design-platform.css` + `gradient-shadow-text.css` + `scrollbar.css`. |

Product-scope deviations (documented, not gaps):

- **Flat catalogue, not provider-grouped.** `/api/session/context` serves
  `models: [{name,label,description,default}]` — the TUI `/model` picker's
  catalogue projected through the live `SessionHostModelController` (built-in
  families, the `default` reset row, custom models, allowlist-filtered). No
  provider ids exist in that shape, so the model pane renders one flat list.
- **Effort semantics differ from upstream's per-model metadata.** Upstream
  reads each model's adapter-advertised `reasoning.efforts` with a
  `providerDefault` sentinel; our gateway exposes the session's live effort
  controller (`auto` clears the override, `low/medium/high` per model — the
  same list the TUI effort picker serves). The readout matches upstream's
  resolution rule: the server-resolved `effective` level drives the trigger
  (`Sonnet · high`, never a `Default` stand-in while a level resolves);
  the `providerDefault` label appears only for the `auto` row itself and
  when no level resolves (e.g. an unknown custom endpoint whose service
  default governs). The Effort cell only renders when the session's model
  advertises levels, matching upstream.
- **A running turn keeps its model.** Same as upstream: the POST applies to
  the next prompt assembly; the running step is untouched. The trigger
  stays clickable mid-turn (upstream disables on `busy` selecting state
  only).

### `vendor/dsh-permission-select/PermissionModeSelect.tsx` — composer permission-mode chip

The TUI's Shift+Tab cycle (`PermissionModeCycle.java`) has no webui
counterpart; the composer's `.modes` slot held only a static hint string.

Upstream: `packages/client/ui-permission-presets/src/client/PermissionSelect.tsx`
+ `PermissionSelect.module.css` — the composer-chip sibling of
`PermissionRow.tsx` (the Settings-panel row already vendored for
`SettingsPanel.tsx`, see "Product-scope deviations" below). Both files were
fetched verbatim via
`gh api repos/deepseek-ai/deepseek-harness/contents/<path> --jq '.content' | base64 -d`,
along with `packages/client/ui-conversation/src/client/skeleton/InputBar.tsx`
(+ `.module.css`) and `packages/client/ui-primitives/src/Menu.tsx` +
`src/icons/index.tsx`, after **two** prior revisions were caught shipping
plausible-but-unverified code:

1. The first revision skipped straight to a from-scratch component modeled
   on `dsh-model-select/ModelSelect.tsx`'s bespoke `createPortal` positioning
   without fetching `PermissionSelect.tsx` at all.
2. The second revision claimed to have corrected this onto the shared `Menu`
   primitive, but the claim itself was still inference dressed as fact — it
   had not actually fetched `PermissionSelect.tsx`'s content, and shipped
   `align="end"` (wrong: real upstream passes no `align` prop and inherits
   `Menu`'s own `align='start'` default — confirmed by grepping the real
   `Menu.tsx`) and mounted the trigger in `InputBar.tsx`'s `.trailing` group
   next to `ModelSelect` (wrong: real upstream's `InputBar.tsx` renders
   `conversation.input.permission` inside `.tools`'s `.modes` sub-container,
   immediately after the `+`/attach button, well before `.trailing`'s
   model-select + send group). Both mistakes produced a right-aligned menu
   positioned near the model selector — the user caught this with two
   screenshots showing the real upstream's left-aligned menu anchored next
   to the `+`/attach cluster at the bottom-left.

Corrected against the actually-fetched source:

- **Position**: `PermissionModeSelect` now mounts inside `InputBar.tsx`'s
  `.modes` container (`src/views/InputBar.tsx`), matching upstream's
  `renderSlot('conversation.input.permission', ...)` placement right after
  the `+` button.
- **Alignment**: the `<Menu>` call no longer passes `align` — it inherits
  `Menu.tsx`'s real default (`align = 'start'`, confirmed at
  `vendor/ui-primitives/Menu.tsx:84`), so the dropdown opens left-aligned to
  the trigger, matching the real upstream and the user's reference screenshot.
- **Trigger CSS metrics** (`PermissionModeSelect.module.css`) now copy the
  real `PermissionSelect.module.css` field-for-field: `display: inline-flex`
  (not `flex`), `max-width: 220px` (fixed, not a `min()` clamp),
  `.triggerIcon svg { width: 14px; height: 14px }` (the shared 16px glyph
  shrinks one step on the trigger only — dropdown rows keep 16px), and the
  narrow-composer label-collapse is qualified `.trigger:has(.triggerIcon)
  .triggerLabel` (only a trigger that actually carries a glyph drops its
  label at the `@container (max-width: 460px)` cut — an earlier revision
  collapsed unconditionally).
- **Icons.** The prior "no shield-glyph SVG set exists in `@primitives`" claim
  was itself unverified and turned out to be false: real upstream's
  `permissionGlyphs` compose a shared `SHIELD_OUTLINE_PATH`/
  `SHIELD_OUTLINE_STROKE` contour that IS exported from the primitives
  package (`ui-primitives/src/icons/index.tsx`). Both constants plus
  `IconShieldOutline16` are now vendored verbatim into
  `vendor/ui-primitives/icons/index.tsx`. `PermissionModeSelect.tsx` builds
  its own five glyphs on this shared contour, reusing upstream's exact inner
  paths where the assistant's `PermissionMode` states line up semantically:
  PLAN (deny writes / allow reads) reuses upstream's read-only check mark;
  ACCEPT_EDITS (auto-accept edits) reuses upstream's workspace-write pencil
  glyph; BYPASS_PERMISSIONS (skip every check) reuses upstream's
  danger-full-access exclamation mark. DEFAULT gets the bare shield contour
  (`IconShieldOutline16`, no upstream "ask every time" preset to borrow a mark
  from); DONT_ASK renders no icon, mirroring upstream's own pattern of
  leaving its second risky preset (`auto`) icon-less.
- **Preset catalog** is still not ported as a dynamic catalog — Menu items
  carry the assistant's five externally-selectable modes
  (`SdkControlBroker`'s valid-external-mode allow-list excludes `auto`, the
  internal classifier's own state), not a host-configurable
  `usePermissionCatalog` list. This is a legitimate cut: the assistant's
  `PermissionMode` is a fixed compile-time enum
  (`claude-code-permissions/.../PermissionMode.java`), not a host-supplied
  catalog, so there is no catalog/badge system to carry.
- **Risk confirmation** is `RiskConfirmation` (`@primitives`, already
  vendored), the same primitive `Sidebar.tsx`'s delete-session flow uses.
  Selecting `bypassPermissions`/`dontAsk` (`PermissionMode.ColorKey.ERROR` —
  the TUI's own risky-mode marker) opens the confirmation instead of calling
  select directly — structurally the same gate real upstream applies before
  its own `danger-full-access`/`auto` presets; cancelling leaves the mode
  unchanged.
- **Color-token substitution** (layered on top of the borrowed shield
  geometry — upstream's own icons carry no color axis, shape alone
  distinguishes its three presets). The TUI colors modes via
  `LanternaTheme.colorFor(PermissionMode)`: `PLAN_MODE`→teal,
  `AUTO_ACCEPT`→purple, `ERROR`→red, `WARNING`→amber. `design-platform.css`
  defines no teal or purple tokens, so `PermissionModeSelect.tsx`'s
  `colorFor()` substitutes the closest available semantic token instead of
  inventing a raw color: `PLAN_MODE`→`--dsw-alias-state-business-primary`
  (brand blue), `AUTO_ACCEPT`→`--dsw-alias-state-success-primary` (green —
  fits "accept" semantically), `ERROR`→`--dsw-alias-state-error-primary` and
  `WARNING`→`--dsw-alias-state-warn-primary` (exact TUI parity). Verified via
  `grep -n "alias-state-business-primary\|alias-state-success-primary\|alias-state-error-primary\|alias-state-warn-primary" vendor/theme/styles/design-platform.css`.
- Tokens on the trigger chrome itself (`--dsw-alias-label-secondary`,
  `--dsw-alias-interactive-bg-hover`, `--dsw-alias-border-l3`,
  `--dsw-alias-label-dimmed`, `--dsw-alias-label-caption`) verified the same
  way against `vendor/theme/styles/design-platform.css`.
- Backend surface: `GET`/`POST /api/session/permission-mode`
  (`GatewayPermissionModeHandler`), addressed the same way as
  `/api/session/context` (empty `session_id` = active TUI session, a known
  headless id = that session). Copy: `src/i18n/dictionaries/permissionMode.ts`
  (own namespace — not a translation of any dsh dictionary).

### `vendor/dsh-stats-pills/` — session-stat pills & turn-tail pills

Two pill groups from ui-chat: the composer-dock **StatsPills** (session
totals — a gauge pill opening the time-and-speed dialog, a database pill
opening the token-usage dialog) and the message-tail **TurnUsagePanel /
TurnTimePanel** (per-turn — the usage pill's buckets and the time pill's
wall clock, each opening its own details dialog). Upstream mounts the
second group in `TurnTailNodeView`'s `usageAction` slot; this app mounts
both from its own message/composer components against the same class
names and the same dialog seat.

| File here | Upstream source | Notes |
|-----------|----------------|-------|
| `stat-dialog.ts` | `ui-chat/src/client/chat/stat-dialog.ts` | Nearly verbatim (already cordis-free): the shared trigger-anchored dialog seat (measure-before-paint, viewport clamp with the 12px margin, 8px trigger gap, outside-pointer/Escape close). Only the `@primitives` import is local. |
| `token-format.ts` | `ui-chat/src/client/chat/token-format.ts` | Nearly verbatim: `formatTokens` (517 / 12.2K / 517K / 1.2M), `formatExactTokens` (thousands-separated), `formatCacheHitPercent` (rounds to the given decimals, `99.99% + 1 decimal` stays `99.9%` — no carry into 100%). Upstream's `ChatViewSlotProps` locale-seat type is renamed to the local `Translate`. |
| `message-chrome.ts` | `ui-chat/src/client/chat/message-chrome.ts` | `formatTokensPerSecond` (as before) plus the clock share lifted verbatim in the icon-row round: `startOfLocalDay`/`msUntilNextLocalMidnight` and `formatMessageClock` (same-day `HH:mm`, `clock.md`/`clock.ymd` date prefix beyond). The run-duration/latency helpers stay with `message-chrome-runtime.ts`. |
| `message-chrome-runtime.ts` | same file | New file holding just `formatRunDuration` (the wall-time label the turn-time pill shows), trimmed out of the same upstream source. |
| `use-calendar-day.ts` | `ui-chat/src/client/chat/use-calendar-day.ts` | Vendored verbatim: the component-local calendar-day tick that re-fires the clock label at local midnight without framework hooks. |
| `MessageIconActions.tsx` | `ui-chat/src/client/chat/MessageIconActions.tsx` | The shared copy/clock icon row, adapted: the branch action is cut (no fork-the-conversation feature here — upstream seats the row as copy → branch → usage pills → clock; here it is copy → usage pills → clock). The copy gesture keeps upstream's epoch-gated retry guards (copyEpoch/copyPending/one-second check swap) and the `Tooltip` seat; the `time`/`clock='start'|'end'` sides and the composed `className` keep their meaning. Seated under every user bubble (leading clock) and as the settled turn tail (trailing clock, `-6px` left from the vendored `TurnTailNodeView.module.css`). |
| `statsPillsModel.ts` | new | The StatsPills render-gate/label derivations factored out of the upstream JSX so the gate matrix (`steps===0 && !hasTokens` renders nothing, four-zero times render static spans, `showTime`/`showUsage` splits) is unit-testable without a render harness. The rules mirror upstream exactly; the inputs are the gateway's raw wire integers. |
| `StatsPills.tsx` | `ui-chat/src/client/chat/StatsPills.tsx` | Adapted: the cordis projection seats (`useChat`/`useProjection` over the sessionStats and tokenUsage projections) collapse into one plain `metrics` prop fed by `/api/session/context`, and the client window fold (`deriveStats`/`WindowStats`) is not ported — the durable backend fold replaces it. Everything else kept nearly verbatim: the memo wrapper, the exclusive `openPill` state, the TimePill/UsagePill JSX, portal + `useStatDialog`, `aria-haspopup="dialog"`, `MEASURE_STYLE`, `data-composer-stats`, the steps-zero gate, the static-span gate for non-interactive pills. |
| `TurnUsagePanel.tsx` | `ui-chat/src/client/chat/TurnUsagePanel.tsx` | Adapted: `usage` is the gateway's `TurnUsage` wire type (snake_case buckets, always-present) instead of upstream's attempt-lifecycle `TurnTokenUsage` (optional buckets). The model-route row renders from the gateway's `model` field (the turn's last-reported model id, wired through the turn.completed frame's usage body); upstream's provider/model routes collapse to that one id — this gateway has no per-attempt provider attribution. The reasoning-tokens row stays cut — no data source exists. The TPS/TTFT dialog rows stay prop-driven: the turn tail feeds `tokensPerSecond` (output tokens over the turn wall clock) and `ttftMs` (the frame's `ttft_ms`), both absent when the facts are missing (upstream's facts-absent-rows-omitted semantics). |
| `statsPillsModel.test.ts` | new | Gate matrix, label composition, and dialog-row derivation tests, including the compact-token ladder the Java-side `SessionMetricsFormatTest` pins with the same fixture rows (cross-language formula pinning). |

CSS (in `vendor/chat-styles/`, see the table there): `StatsPills.module.css`
(already vendored verbatim, now actually consumed), `stat-dialog.module.css`
(the dialog surface skin, verbatim), `TurnUsagePanel.module.css` (verbatim),
`MessageIconActions.module.css` (already vendored verbatim — the copy/clock
icon-row chrome — consumed since the icon-row round), and
`TurnTailNodeView.module.css` (verbatim — just the turn-tail column and its
`-6px` actions offset). All consumed `--dsw-*` tokens resolve in the vendored
theme styles except `--dsw-alias-separator-primary` — see the shim entry in
Local modifications.

Product-scope deviations (documented, not gaps):

- **Durable backend fold, not the client window fold.** Upstream derives
  session totals client-side (`deriveStats`/`turn-metrics.ts` over the
  visible window's attempt state). This app serves the engine's durable
  `SessionMetricsSnapshot` fold from `/api/session/context` — the same
  fold the TUI status line renders, restored from the persisted metrics
  event log for historical sessions — because
  docs/hud-metrics-specification.md §6 forbids reconstructing
  whole-session metrics from visible messages and forbids serving a
  partial coverage as a total. An incomplete fold serves `metrics: null`
  and the pills render nothing.
- **Per-turn data is a turn-boundary fold delta, not an attempt state
  machine.** Upstream's `deriveTurnTokenUsage` (token-meter/turn-usage.ts)
  accumulates per-attempt usage facts client-side. This app's gateway
  diffs the durable fold at the turn boundaries (`turn.completed`'s
  `turn_usage` = fold(after endTurn) − fold(at turn start)), so every
  step's usage is counted exactly once even though the wire stream
  replays the turn's usage per block; the snapshot path carries each
  assistant row's own usage buckets. Turn ordering is the stable join key
  across both paths.
- **Per-turn TTFT/TPS rows are omitted.** The runtime has no per-turn
  timing tracker readout (only whole-session TTFT averages/TPS in the
  fold); the dialog rows stay absent rather than showing a
  session-average figure on a per-turn dialog. To add them, runtime needs
  a per-turn timing source — a separate work item.
- **One plain `metrics` prop replaces the two cordis projection seats.**
  The render gates and formatting are upstream-verbatim; only the data
  plumbing differs.
- **The icon row's clock facts cross the wire as epoch ms.** Upstream's
  clock reads the host session event time (the snapshot node's `time`). This
  app's gateway stamps the snapshot's user/assistant rows and the
  `turn.started`/`turn.completed` frames with the durable transcript
  timestamp (`Message#timestamp` / wall clock on the live path); absent
  timestamps render no clock label, not a placeholder (facts-absent
  semantics).
- **Raw integers cross the wire; the formulas live here.** Locale-owned
  formatting (compact tokens, cache-hit percent, tok/s, durations) stays
  in the vendored formatters; the Java side pins the same fixture rows in
  `SessionMetricsFormatTest` so the two languages cannot drift.



Upstream's context-occupancy ring left of the send button with its
click-open panel, from
`ui-conversation/src/client/skeleton/ContextMeter.tsx`. Vendored with these
deliberate cuts:

| File here | Upstream source | Notes |
|-----------|----------------|-------|
| `ContextMeter.tsx` | `ui-conversation/src/client/skeleton/ContextMeter.tsx` | Kept nearly verbatim: the 14px ring (RADIUS 5.5, strokeDasharray by percent, rotate(-90)), the 28px circular trigger with Tooltip (this app's vendored `Tooltip` primitive, same `side`/`delayMs`/`disabled` contract), the outside-pointerdown/Escape close pattern, the unavailable-panel close effect, the click-open panel (menu surface, 264px, r12, `~used / window` figures with tabular nums), and the composition breakdown (system/tools/messages colored segments proportioned over the provider-exact percent, the swatch legend rows, the zero-width-part drop). Cut: the `useProjection` plumbing (`contextPressure`/`contextBreakdown` over the cordis token-meter service — both samples arrive as plain props from `/api/session/context`) and the locale-seat `t()` (label strings arrive as the `ariaLabel`/`headline`/`segmentLabels` props). |
| `contextOccupancy.ts` | `ui-conversation/src/client/context-occupancy.ts` | Kept nearly verbatim (the bounded percent + null-until-known resolution). The projection type collapses to `ContextPressureSample {usedTokens?, contextWindow?}` — upstream's `projectedTokens ?? pressureTokens` duality (heuristic reprice over the provider anchor) has no equivalent here because the gateway serves one provider-anchored number (see the deviation below). |
| `ContextMeter.module.css` | `ui-conversation/src/client/skeleton/ContextMeter.module.css` | Verbatim, including the per-segment color blocks (`.colorSystem`/`.colorTools`/`.colorMessages`, `.swatch`, `.rows`, `.row` — the breakdown panel addresses them). All consumed `--dsw-*` tokens resolve across the vendored theme styles (`design-platform.css` + `gradient-shadow-text.css` for `--dsw-elevation-prominent`). |

Product-scope deviations (documented, not gaps):

- **The breakdown is analyzer-sourced, not a client heuristic.** Upstream
  proportions the system/tools/messages split from `dsh-token-meter`'s
  client-side heuristic reprice over the provider anchor. Our gateway serves
  the split from the TUI `/context` analyzer's category accounting
  (`ContextUsageAnalyzer.analyze` — system prompt, memory files, custom
  agents, skills, tool definitions, messages), mapped onto dsh's three
  segments in the CLI adapter and carried as `breakdown` in
  `GET /api/session/context`; reserved categories (autocompact buffer, free
  space) are buffers, not composition, and never enter the split. The
  overall ring/percent stays the claude-hud provider-anchored accounting —
  the finalized usage anchor's input-token sum (`input + cache_creation +
  cache_read`, Codex-adjusted for GPT ids) over the model-resolved context
  window (`ModelContextWindows`: 200k default, 1M for `[1m]`/opus-5, 372k
  for GPT 5.6) — computed in `GatewaySessionContextHandler.usageOver`, the
  same `TokenEstimator` path the status line uses. Without a wired analyzer
  the field is absent and the panel renders the single-reading form
  upstream renders when the breakdown is undefined: one full-width segment,
  no legend rows. The `~` prefix stays — these are the same
  estimator-sourced numbers the status line reports, and upstream's panel
  keeps its `~` for exactly that reason.
- **`used_tokens` is absent until the first finalized API response.** The
  endpoint always serves `context_window`, but the token counts only once a
  finalized usage anchor exists; until then the meter renders nothing
  (upstream renders nothing until both pressure fields are known — same
  null-until-known rule through `contextOccupancy`).

### `vendor/chat-styles/` — settings & schedule additions

| File here | Upstream package | Upstream file |
|-----------|-------------------|----------------|
| `SettingsRoot.module.css` | `ui-settings-general` | `src/client/SettingsRoot.module.css` — the real Settings dialog shell (800px, two-column nav rail): `overlay`/`mask`/`panel`/`nav`/`navTitle`/`navList`/`navCell`/`content`/`header`/`actions`/`close`/`options`. `SettingsPanel.tsx` renders this shell directly instead of wrapping the generic `vendor/ui-primitives/Modal.tsx` (a 380px confirm-dialog primitive — a different, unrelated upstream component that an earlier revision wrongly used, which is why the panel did not visually resemble dsh's Settings dialog). The schedule surface renders inside this shell as the dialog's "定时任务" nav section (`ScheduleSection`, since 2026-09-13; its earlier revision stood as a separate overlay). |
| `GeneralSection.module.css` | `ui-settings-general` | `src/client/GeneralSection.module.css` — the plain flex-column section wrapper each settings section is rendered in |
| `PermissionRow.module.css` | `ui-permission-presets` | `src/client/PermissionRow.module.css` — the reusable settings-row visual pattern (`row`/`rowText`/`title`/`desc`/`selector`/`chevron`), reused in `SettingsPanel.tsx` for every field row via a local `SettingsRow` wrapper and a `MenuSelect` helper (the `Menu` primitive + `.selector`/`.chevron`) that replaces native `<select>` elements |
| `ScheduleCatalogAction.module.css` | `ui-schedule` | `src/client/ScheduleCatalogAction.module.css` — the task-row visual pattern (`row`/`status`/`statusDot`/`prompt`/`metadata`), reused in `SchedulePanel.tsx`'s task list. The `.menu`/`.trigger`/`.count`/`.triggerOpen` classes in this file belong to upstream's read-only header-popover trigger and are not used here. |
| `SidebarRoot.module.css` | `ui-sidebar` | `src/client/SidebarRoot.module.css` — the sidebar column frame `Sidebar.tsx` renders inside (`root`/`scroll`). `.newSession` (the 38px/12px-radius bar) and `.regionArea` (the seat that hosts the session browser, canceling the shell's edge inset so the nested scrollbar sits flush) **are** ported — `POST /api/sessions/open` already mints a session with no `session_id` (openapi.yaml), so this was a real backend-supported feature an earlier revision had wrongly dropped by omission, not a genuine gap. The rail-collapse classes (`root.collapsed`, `logoRow`, `brand`, collapse keyframes) were similarly dropped by omission in that same earlier pass despite already being vendored verbatim in this file — collapse is now ported; see the "sidebar collapse" section below. Its `.footArea` structure ("additive actions stack above Settings") is why `Sidebar.tsx` puts its settings trigger in the foot's `.settingsArea` seat; the `.footerActions` seat above it stays empty (upstream registers no default `sidebar.footer.action` occupant, and the schedule surface moved into the settings dialog on 2026-09-13 — see the Schedule seat deviation below). |
| `AppearanceRow.module.css` | `ui-theme` | `src/client/AppearanceRow.module.css` — the three-cube theme selector (`group`/`title`/`cubeRow`/`themeCube`/`selected`), rendered by a local `AppearanceRow` component in `SettingsPanel.tsx`'s general section and wired to `store/theme.ts` |
| `FontSizeRow.module.css` | `ui-theme` | `src/client/FontSizeRow.module.css` — the font-size stepper pill (`row`/`control`/`stepper`/`value`/`arrows`/`arrow`), rendered by a local `FontSizeRow` component in `SettingsPanel.tsx`'s general section and wired to `store/theme.ts`. `EnterBehaviorRow.module.css`/`TranscriptViewRow.module.css` are **not** vendored separately — both rows are pixel-identical to the already-vendored `PermissionRow.module.css` row/selector pattern and reuse `SettingsPanel.tsx`'s existing `SettingsRow`/`MenuSelect` helpers. |

### `vendor/chat-styles/` — session sidebar (`Sidebar.tsx`/`SessionRows.tsx`)

| File here | Upstream package | Upstream file |
|-----------|-------------------|----------------|
| `WorkspaceBrowser.module.css` | `ui-workspace` | `src/client/WorkspaceBrowser.module.css` — the session-list seat: its own `.root` wrapper (declares `--dsh-session-list-edge-inset`, canceling `SidebarRoot.module.css`'s `.regionArea` negative margin so the nested scrollbar sits flush), `.sectionHeader`/`.sectionLabel`/`.headerActions`, `.listArea`/`.treeBody`/`.list`/`.fade`, and the grouped-project row shell (`.groupSection`, `.sessionOverflowButton`). `.sectionHeader` uses `justify-content: flex-end`; upstream's dropped `.searchSlot` (`flex: 1; max-width: 28px; margin-left: auto`) is what pushes `.sectionLabel` left / `.headerActions` right, so a local `Sidebar.module.css` `.headerActions { margin-left: auto }` reproduces just that split without the search UI itself. The flat "In one list" view and its view-options menu are not ported (no backend surface — see `session sidebar` scope note in `Sidebar.tsx`'s class Javadoc). **Overflow-control semantics** (fixed 2026-09-13): upstream's `sessionOverflowButton` is a LOCAL fold toggle over `expandedSessionGroups` — expanded renders every group row and flips the button to `sessions.collapse` ("Show less"), `aria-expanded` carries the state, and the header's collapse ALSO drops the group from `expandedSessionGroups`. An earlier revision had wrongly wired the button to `growPerPage` alone (refetch with a larger `?per_project=` page) with a rows/hiddenCount derivation that contradicted itself (`expanded && hiddenCount === 0` gating against `session_count`-based hiddenCount), so clicking "Show {n} more sessions" never unfolded the group. The port now mirrors the local toggle (`expandedGroups` + `toggled()`), with one recorded deviation: upstream's client holds every account row, while this port's rows are a gateway page, so expanding ALSO grows the page one step while `session_count > sessions.length` (the paged-out remainder counts in the button's `n`). |

### Sidebar collapse (rail mode, ported 2026-09-18)

`SidebarRoot.module.css`'s rail-collapse classes (`.collapsed`, `.logoRow`, `.brand*`,
`.railIn`/`.fading`/`.wide` keyframes, `.quietBars`) were vendored verbatim from the
start but never consumed — `webui/UPSTREAM.md` previously (wrongly) recorded this as an
out-of-scope decision rather than an omission. `Sidebar.tsx` now ports
`ui-sidebar/src/client/SidebarRoot.tsx`'s state machine against those existing classes:

- **Collapse is a slide + crossfade, not a morph** (kept verbatim): expanded content
  freezes at its current width (`lastWideWidth` ref) and fades out in place over 150ms
  (`.fading`) while `AppFrame`'s grid track (`AppFrame.module.css`'s already-vendored
  `transition: grid-template-columns`) slides/clips it; the rail layout (`.collapsed`)
  only applies once the fade settles (`COLLAPSE_SETTLE_MS`), so nothing reflows
  mid-slide. `everWide` gates `.railIn` so a cold collapsed render is static, not
  crossfaded.
- **Scrollbar pointer-linger** (kept verbatim): the column tracks `pointermove` against
  its own `getBoundingClientRect()` (not `pointerleave`, since the Settings panel renders
  as a fixed-position descendant) and keeps the thumb drawn for `SCROLLBAR_LINGER_MS`
  (2000ms) after the pointer truly leaves the column's box.
- **State home**: `store/sidebarCollapse.ts`, a `localStorage`-persisted boolean
  (`webui-sidebar-collapsed`), mirroring the existing `store/theme.ts` /
  `store/transcriptView.ts` pattern — upstream holds this in a cross-slot `ui-layout`
  service (also shared with drag-resize and a right sidebar this app has neither of),
  which has no equivalent here.
- **`AppFrame.tsx`** grows a `collapsed` prop and switches the sidebar grid track between
  `SIDEBAR_WIDTH` (260px) and a 56px rail width, matching `SidebarRoot.module.css`'s rail
  geometry; the already-vendored `.frame` transition animates the slide.
- **`ContextDashboardButton`** (`views/context/ContextDashboard.tsx`) and the Settings
  trigger row already carried unused rail styling from earlier passes
  (`lc-ov-entry-rail`; `SettingsRoot.module.css`'s `.trigger.rail`/`.triggerRow.railRow`)
  — both now receive the real `wide` flag instead of always rendering wide.

**Sidebar brand row, ported from `SidebarRoot.tsx`'s `logoRow`.** Both halves are
in place against the vendored classes: expanded, `.brand`/`.brandIdentity`/
`.brandMark`/`.brandName` form the compound "brand mark + name" button that doubles
as a New Session shortcut; collapsed, the toggle rests on the brand mark and swaps
to the panel icon on hover (`.collapsed .toggle:hover .panelIcon` /
`.collapsed .toggle:hover .railMark`). The structure, the classes and the 24px mark
size are upstream's.

The **art** is not, and deliberately so. Upstream fills the mark and the name
through `sidebar.brand.mark` / `sidebar.brand.name` slots, falling back to a
local-build badge (`.fallbackBrandName` / `.localBuildBrand` / `.buildVersion`)
when nothing registers. The slot plumbing is cut per the vendoring rules, so this
port mounts its own art directly — `src/views/brand/CodeOrbMark.tsx` and
`src/views/brand/PocoWordmark.tsx` — rather than upstream's whale and `deepseek`
wordmark, which were deleted (`Local modifications` #1). The local-build fallback
is therefore unreachable here and its classes go unused; they stay in the vendored
CSS untouched. `PocoWordmark` does keep upstream's `HARNESS` badge verbatim —
rounded rect, seven letter paths, inverted-label fill — translated left because
"poco" is four letters where `deepseek` was eight; its provenance is recorded in
that file's doc comment.

Product-scope deviations (documented, not gaps):

- **The session browser unmounts entirely while collapsed**, rather than degrading to
  upstream's rail icon column (`ui-workspace`'s own `!wide` rendering — a search icon and
  grouping affordances this port's session tree has no equivalent of, consistent with the
  already-recorded "no search/view-options" cuts above). `regionArea` itself stays
  mounted (so the foot never moves); only its content is conditional on `wide`.

**Session row "..." menu (Rename / Fork / Archive), ported from `ui-workspace`'s
`Rows.tsx` `sessionMenuItems`.** `SessionNodeItem` in `SessionRows.tsx` opens a
`vendor/ui-primitives/Menu.tsx` from a new `IconEllipsisOutline16` trigger,
placed before the existing close-headless icon button (upstream's own row-
action ordering). **Rename** renames the session in place via a
`Modal`+`Input` dialog (`Sidebar.tsx`), prefilled with `displayTitle(session)`
— an unchanged title round-trips as a legal no-op. **Fork** forks the session
at its last completed turn with no confirmation dialog, matching upstream.
**Archive** hides the session from every catalog view (TUI `/resume` panel
and the webui sidebar both read the same `ProjectCatalog.aggregate()`
dedup pipeline) via a one-way `"archived"` JSONL event appended to the
session's own transcript — no confirmation, matching upstream. Per an
explicit product decision, **this pass does not build an unarchive/view-
archived-sessions surface**; archiving is one-way until a future pass adds
one. **Delete is a product-scope deviation with no upstream counterpart**:
this project already had `SessionManager.deleteSessionPermanently` (TUI
`/resume` picker) with no webui surface, so the row menu adds a fourth,
project-specific "删除" entry that permanently deletes the session's
transcript from disk. Its confirmation reuses the already-vendored
`RiskConfirmation.tsx` (checkbox-gated "我已知晓" acknowledgement) rather than
the TUI picker's simpler yes/no confirm — a deliberate choice to give the
irreversible webui action the stronger of the two confirmation patterns
already vendored in this codebase. Backend: `GatewaySessionActionsPort`
(`claude-code-gateway`) with `POST /api/sessions/{id}/rename|fork|archive`
and `DELETE /api/sessions/{id}`, backed by
`SessionOperationsService.renameSession`/`forkSession` (already existed) and
new `archiveSession`/`deleteSession` methods.

**Session row pending-interaction status, restored from `ui-workspace`'s
`Rows.tsx` `sessionStatuses` (2026-09-20).** An earlier pass recorded
"pending-interaction statuses" as dropped by subtraction; that was an omission,
not a scope cut — this gateway does produce pending interactions, they just had
no sidebar surface, so an approval raised in one session was invisible from any
other. `sessionStatus()` in `SessionRows.tsx` now follows upstream's ordering,
where a pending interaction is the **primary** status and outranks
running/completed: `approval` → `{ state: 'warning', label:
t('status.waitingApproval') }` and `question` → `{ state: 'warning', label:
t('status.waitingAnswer') }`, with both label strings copied verbatim from
`ui-workspace/src/client/locales.ts` into `i18n/dictionaries/workspace.ts`
(`等待审批`/`Waiting for approval`, `等待回答`/`Waiting for answer`). This
consumes the already-vendored `ui-primitives/StateDot.tsx`'s `'warning'` state
and the existing 16px `css.slot`, so no vendored file changed and no new DOM
grammar was invented.

Two recorded deviations:

- **The pending data source differs.** Upstream reads `node.pendingInteraction`
  off the session node itself; this gateway's `CatalogSession` carries no such
  field, so the status comes from the mirror stream's `PermissionAsk.session_id`
  via `store/approvals.ts`'s `pendingInteractions()` and arrives as a
  `SessionNodeItem` prop. `showStatus` was widened to `pending != null ||
  session.active || session.headless_open`, so a waiting session lights up even
  when it is neither live nor selected.
- **`plan-review` is still missing.** Upstream's third pending kind has no wire
  equivalent here; `PendingInteraction` is the two-value union `'approval' |
  'question'`. Upstream's `runningSubagentCount` secondary status and the
  visually-hidden status labels remain dropped as before.

The matching client-side rule is that the card itself is **never** shown
outside its own session: `App.tsx` reads `pendingAskFor(asks, selectedId)`,
which has no fallback to another session's ask. An earlier `?? asks[0]`
fallback rendered session B's approval under session A's composer, so opening
a fresh session hijacked an authorization the user never triggered there.

### `vendor/chat-styles/` — reasoning row & tool-call row

| File here | Upstream package | Upstream file |
|-----------|-------------------|----------------|
| `StatsPills.module.css` | `ui-chat` | `src/client/chat/StatsPills.module.css` — vendored verbatim in an earlier pass, consumed since the stats-pills round (see `vendor/dsh-stats-pills/`) |
| `stat-dialog.module.css` | `ui-chat` | `src/client/chat/stat-dialog.module.css` — the shared stat-dialog surface skin (panel/title/titleRule/details), verbatim; all consumed `--dsw-*` tokens resolve in the vendored theme styles |
| `TurnUsagePanel.module.css` | `ui-chat` | `src/client/chat/TurnUsagePanel.module.css` — the turn-tail pill chrome (root/trigger/label), verbatim; consumed by `vendor/dsh-stats-pills/TurnUsagePanel.tsx` |
| `MessageIconActions.module.css` | `ui-chat` | `src/client/chat/MessageIconActions.module.css` — the shared copy/clock icon-row chrome (actions row, `timeStart`/`timeEnd` clock sides, the 28px `.action` buttons, hover-reveal `data-actions-reveal` gate), vendored verbatim in an earlier pass; consumed since the icon-row round by `vendor/dsh-stats-pills/MessageIconActions.tsx` |
| `TurnTailNodeView.module.css` | `ui-chat` | `src/client/chat/TurnTailNodeView.module.css` — the turn-tail column and its `.actions { margin-left: -6px }` offset, verbatim; only the `.actions` class is consumed (the turn-tail wrapper here is the assistant stack itself, not a separate node view) |
| `TurnProcessNodeView.module.css` | `ui-chat` | `src/client/chat/TurnProcessNodeView.module.css` — the turn-level process-disclosure control (root/label/chevron, the 0.5px l2 divider, the 8px closed-gap margin, the -90°→0° chevron rotation), vendored verbatim; consumed since the turn-process fold round (2026-09-13) by `src/views/TurnProcessControl.tsx`, ported from `TurnProcessNodeView.tsx` (see the turn-process fold deviation below). |
| `ReasoningRow.module.css` | `ui-conversation` | `src/client/ReasoningRow.module.css` — already vendored verbatim in an earlier pass but never fully wired: `MessageItem.tsx` used to merge every `thinking` segment of a message into one string and wrap it in a single ad hoc `DisclosureRow` (hand-rolled `◦` icon, a title that toggled between "正在思考…"/"已深度思考"). The real upstream `ReasoningRow.tsx` renders **one row per thinking segment**, with a fixed title, the real `IconThinkOutline14` icon, and a collapsed summary derived from `firstLine`/`latestLine` (the latter only for the streaming segment) with `**` markdown markers stripped. `src/views/ReasoningRow.tsx` is a new small local component doing exactly that, and `MessageItem.tsx`'s `AssistantItem` now maps `message.thinkingBlocks` (already a per-segment `readonly string[]` in `store/conversations.ts`) to independent `<ReasoningRow>` instances instead of the merged single block. |
| `ToolRow.module.css` | `ui-tool` | `packages/client/ui-tool/src/client/tool/components/ToolRow.module.css` — newly vendored this round. `ToolCallRow.tsx` previously imported `TurnProcessNodeView.module.css`, which on inspection of upstream source is the style for a **turn-level** fold/unfold summary button (e.g. "3 次工具调用 · 2 条消息"), not a per-tool-call row — a wrong-source mistake from an earlier pass, not a deliberate scope cut. `TurnProcessNodeView.module.css` is left in the vendor directory (a future turn-level fold feature could still use it) but is no longer imported by `ToolCallRow.tsx`. `ToolCallRow.tsx` is rewritten against the real `ToolRow.tsx`: state-derived leading icon (`StateDot` replaces the icon on error, matching `leadingFor()`), summary-priority chain (error's first line > a bash call's own `description` argument > the existing command/path/pattern/query fallback chain, renamed `argsSummary()`), and an expanded body dispatched through `.bodyWrap`/`.ioCard`/`.ioSection`/`.ioLabel`/`.ioText`/`.ioDivider`/`.terminalBody`/`.diffBody`/`.readBody` in place of the old inline `style={{...}}` attributes. |

**Bash call summary now uses the `description` tool argument.** dsh's real
Bash-row collapsed summary is not the command or the result — it is the
Bash tool call's own optional `description` argument (a short human-readable
line such as "列出当前目录文件", from `terminal-card-model.ts`'s
`shellCall()`). This project's `BashTool.buildSchema()`
(`claude-code-tools/.../bash/BashTool.java:1219`) already declares this
exact optional `description` field with wording matching Claude Code's own
official Bash tool guidance — it was simply never read by the frontend. This
is a genuinely-supported backend field being wired up, not new backend work.

**Cut points (deliberate, not gaps):**
- **No exit-code/signal status pill on the terminal card.** dsh derives it by
  regex-parsing a trailing `\n[exit code: N]$` / `\n[killed by signal: X]$`
  marker out of the raw result text (`parseExitStatus()` in
  `terminal-card-model.ts` — even upstream does not get this from a
  structured field). This project's `BashTool`/`PowerShellTool` only append
  a differently-formatted `"\nExit code: N"` marker when the command has
  already been classified as a failure (`CommandSemantics.bash(...).isError()`)
  — successful runs get no marker at all, and some non-zero-exit commands
  (e.g. `grep`/`find`/`test`) are not classified as errors either. Parsing
  this partial signal would produce an inconsistent pill that is sometimes
  present and sometimes silently absent for equivalent command outcomes, so
  `TerminalBlock` is only given `running={call.status === 'pending'}` — no
  `exitCode`/`signal` props — relying on `call.status` (pending/executed/
  failed) as the one reliable state source instead.
- **Turn-level fold/unfold row (restored 2026-09-13, rules vendored
  2026-09-18).** An earlier revision recorded "no turn-level fold" as a
  structural consequence of this app's one-row-per-message model. The fold now
  exists, and since this round its **rules are vendored rather than
  re-derived**: `vendor/dsh-turn-process/turnProcessRules.ts` merges upstream's
  `contract/turn-process.ts`, `contract/assistant-content.ts`,
  `conversation-nodes/turn-process.ts` and
  `conversation-nodes/turn-process-presentation.ts`. `src/store/turnProcess.ts`
  is now only the manual-expansion store plus the adapter that maps the reduced
  message list onto the rules' `TurnInput`. Cut from upstream: the cordis
  `ConversationNodeDefinition` (match/start/update/publication/
  buildLocationData/buildViewNode), the event stream it reduces
  (`assistant/live-chunk`, `assistant/attempt`, `step/start`, `llm/retry`), the
  Location-data store with its reference-preserving equality gates, and the
  `ChatTurnProcessProjector` memo class — this app has no event registry and
  React's `useMemo` covers the caching. Substrate mapping: upstream's event
  `seq` is the transcript row index and a turn's `step` is the assistant row's
  ordinal in its turn.

  The previous hand-written port had drifted from these rules on five points,
  all corrected this round: the answer boundary is upstream's `latestAnswer`,
  which reads the turn's **last step and only that step** (the old port scanned
  for the last *qualifying* row, so a turn ending in a reasoning-only step
  folded when upstream keeps it open); `toolCallCount`/`subagentCount` count
  the **whole turn** while `messageCount` counts only steps before the answer
  step; `inlineReasoning` makes the answer row hide its own thinking blocks
  (`MessageItem`'s `hideReasoning`); `compactAnswer` gates the 8px follow-gap,
  so an intervening independent input restores normal spacing; and
  `hasExternalProcess` gates whether the control shows at all. Rows hide with
  `hidden="until-found"` (never unmounted — stateful tool renderers keep state,
  browser find reveals them, and a `beforematch` on a member expands the group
  via `ChatView.tsx`'s native listener). Manual expansion lives in the
  non-persisted `useTurnProcess` store (absent = collapsed); transcriptView
  "normal" disables folding entirely; an open turn and a no-answer turn never
  fold. The vendored `ChatView.module.css` gap rules and
  `TurnProcessNodeView.module.css` are consumed as-is.

  Two deviations:

  - **`isSubagentDelegationTool` matches `Agent`/`Task`, not upstream's
    `subagent`/`subagent_*`.** This product's delegation tool registers as
    `Agent` with the `Task` alias (`claude-code-tools` `AgentTool`), and its
    control-plane siblings (`SendMessage`, `TaskList`) carry unrelated names,
    so the same delegation-vs-control split holds under different literals.
    (An earlier revision of this file claimed this gateway has no subagent
    delegation tool and the segment always reads zero — that was wrong.)
  - **Step boundaries come from the gateway, backend-first.** Upstream's
    `assistant/message` events carry step identity natively. This app's mirror
    fans one assistant message out into one frame per content block, which
    erased that boundary, so every reply-bearing turn reduced to a single row
    and the fold could never find an answer — the feature never fired on the
    live path even though its unit tests passed against hand-assembled
    multi-row transcripts. `MirrorHub.publishAssistant` now stamps
    `message_id` (the assistant message uuid, the same fact the snapshot path
    serves as its entry id; see `MirrorFrame` in `openapi.yaml`) on
    `output.text`/`output.thinking`/`tool.started`, and
    `conversations.ts` splits rows on it. A synthetic System projection has no
    step and folds as a `context` row, never as an answer candidate. A frame
    with no `message_id` (an older gateway) keeps the old single-row behavior
    and simply does not fold.
- **No Inspect pill / trajectory-view jump.** Upstream's hover-revealed
  Inspect button on an expanded tool row jumps to a trajectory/replay view
  this app does not have; the button is not rendered.
- **`.visuallyHidden` merged into `ToolRow.module.css`, not its own file.**
  Upstream keeps this one utility class in a separate `accessibility.module.css`
  module shared by `ReasoningRow.tsx` and `ToolRow.tsx`. A single class does
  not warrant its own vendor file here; it lives in the already-vendored
  `ToolRow.module.css` and both `ReasoningRow.tsx` and `ToolCallRow.tsx`
  import it from there.

### `vendor/dsh-user-questions/` — the `AskUserQuestion` answer card

The gateway's `AskUserQuestionTool` was already projecting `questions`
(`question`/`header`/`multi_select`/`options[].label/.description`) into the
`permission.asked` frame and already accepted `updated_input` on respond —
the webui simply had no renderer for it and fell through to the generic
`ApprovalCard`, which `JSON.stringify`s any non-command/path tool input.
Vendored from `packages/client/ui-user-questions/src/client/QuestionComposer.tsx`
+ `QuestionComposer.module.css`:

| File here | Upstream source | Notes |
|-----------|----------------|-------|
| `QuestionComposer.module.css` | `QuestionComposer.module.css` | Kept verbatim except three cuts: `.cardMinimized`/`.cardMinimized .header` (the minimize toggle is deferred, see below), `.detail` (`AskUserQuestion` never sends a `detail` field), and `.customBlock`/`.customBlock:focus-within` (the optionless-question block variant — `AskUserQuestionTool.parseQuestions()` always requires 2-4 options, so that branch never fires). All consumed `--dsw-*`/`--dsh-*` tokens resolve in the already-vendored `theme/styles/design-platform.css` — the same pair `ApprovalPanel.module.css` already draws from. |
| `src/views/QuestionCard.tsx` | `QuestionComposer.tsx`'s `QuestionFlow` | The pager state machine (index + per-question `{selected, custom, skipped}` drafts), single-select auto-advance, multi-select checkbox toggling, the inline auto-growing `AnswerField` (hidden mirror `<div>` + `textarea` sharing one grid cell), `parseRecommendedLabel()`'s `(recommended)`/`（推荐）` suffix parsing for the badge, the skip-per-question and cancel-whole-ask buttons, and the IME-aware (`isComposing`) Enter-to-continue handling are all ported line-for-line. |

Product-scope deviations (documented, not gaps):

- **No Cordis Slot store.** Upstream keeps drafts in a Session-scoped Slot
  store (`useStore`/`actions.replace`/`actions.clear`) so a strict-mode
  remount of the composer entry restores the in-flight draft. This app has
  no such session-level slot infrastructure and, at any time, at most one
  `PermissionAsk` is pending per session — `QuestionCard` keeps drafts in
  local `useState` instead, and `App.tsx` mounts it with
  `key={visibleAsk.request_id}` so a new ask (a different `request_id`)
  gets a fresh draft rather than reusing stale state.
- **No `PlanReviewPanel` routing.** Upstream's `QuestionComposer` router
  dispatches to a plan-review presentation when `planReviewOf(questions)`
  detects an `exit_plan_mode`-style single-decision contract. This
  project's `AskUserQuestionTool` has no such contract, so the router and
  `PlanReviewPanel` are not ported — every ask renders as `QuestionFlow`.
- **No composer-takeover global routing.** Upstream's `QuestionComposer` is
  registered into a cordis slot that takes over the whole composer seat.
  `QuestionCard` instead mounts at the exact spot `ApprovalCard` already
  occupies in `App.tsx`'s composer column, branching on whether
  `visibleAsk.questions` is non-empty.
- **No minimize/collapse toggle.** Upstream's header carries a
  minimize button that collapses the card to its title strip
  (`cardMinimized`, Session-persisted). Deferred for this pass — not
  required to fix the underlying bug (being unable to select/submit
  answers at all) and can be added later without touching the answer
  contract.
- **No `question.detail` / `MarkdownText` rendering.** `AskUserQuestionTool`
  never populates a `detail` field on a question, so that upstream branch
  (and its `MarkdownText` dependency) is not ported.
- **No i18n `t()` dictionary.** Upstream localizes every label through a
  `t()` function backed by a dictionary. This app hardcodes the (small,
  fixed) set of Chinese strings directly, matching the neighboring
  `ApprovalCard.tsx`, which does the same.
- **No option `preview`/design-variant rendering.** Upstream's design-variant
  card (`isDesignVariant`) shows a preview pane fed by `option.preview`.
  `GatewayInteractionPresenter.putQuestions()` only ever projects
  `label`/`description` — `preview` is a TUI-only field — so the plain-list
  card is the only variant this project renders, and `annotations` in the
  submitted `updated_input` is always an empty object (there is never a
  `preview`/`notes` value to carry).
- **The recommended-suffix strip is applied to the submitted answer text,
  not just the badge.** Upstream's `choose()` stores the *raw* option label
  (including any `(recommended)`/`（推荐）` suffix) as the selected value, so
  the suffix can leak into its own submitted answer. `QuestionCard` tracks
  selection by option **index** instead of by label (avoiding any
  duplicate-label ambiguity) and formats the final answer text from
  `parseRecommendedLabel(...).label` — the badge and the submitted text
  both use the stripped label, so the model never receives its own
  redundant marker back.
- **Answer-string join rule mirrors the TUI, not upstream's structured
  payload.** Upstream's `pending.answer()` takes a structured
  `{answers: [{id, selected, custom?}]}` shape over its own RPC contract.
  This gateway's `AskUserQuestionTool.buildAnswerInput()` expects `answers`
  keyed by question text with a single joined string value (and always
  writes `annotations`, even empty) — `QuestionCard` builds that string
  with the same join rule as the TUI's `AnswerSubmission.listAnswer()`
  (selected labels joined by `, `, a custom answer appended after a comma
  if both are present, or standing alone if there is no selection), and
  a question that is neither answered nor explicitly skipped blocks
  submission (`completed()`), matching the TUI's requirement that every
  question resolve to either an answer or an explicit skip before the ask
  can go out.

### Product-scope deviations (settings & schedule)

These two features deviate from upstream in ways that are not gaps in the
port — upstream's real feature scope is narrower than what our backend
exposes, or shaped around a data model our backend does not have. Documented
here rather than silently improvised:

- **Permissions: raw allow/deny/ask rule CRUD, not the three named presets.**
  Upstream's `ui-permission-presets` `PermissionRow` picks between three fixed
  presets (Read Only / Workspace Write / Full access) that set the *default*
  mode for *future* sessions; a separate `/permission` slash-command popover
  switches the *current* session. Our backend (`SettingsEditor`) has no preset
  concept — only `writeDefaultPermissionMode` and
  `replacePermissionRules(behavior, rules, tier)` against raw rule arrays.
  `SettingsPanel.tsx` reuses `PermissionRow`'s row/selector *visual* language
  (via the local `SettingsRow`/`MenuSelect` helpers) for tier and mode
  pickers, but edits the raw allow/deny/ask arrays directly since that is the
  actual write surface available.
- **Schedule: full CRUD, not a read-only catalog.** Upstream's `ui-schedule`
  is explicitly read-only — "creating and deleting reminders remain with the
  Schedule tools" (its README) — a header popover bell with no id, no delete
  control, and no add form. Task #36 requires exposing `CronStore` create/
  delete to end users, so `SchedulePanel.tsx` keeps full CRUD (add-task form,
  per-row delete button) while adopting upstream's row visual language
  (`ScheduleCatalogAction.module.css`) for the task list itself. The add
  form's field rows reuse the same `SettingsRow`/`MenuSelect` helpers
  `SettingsPanel.tsx` uses (dsh's own `PermissionRow.module.css` row shape),
  so the form reads as part of the Settings family rather than a bespoke
  block.
- **Schedule: per-task model override.** Upstream's schedule records carry
  no model — a fired prompt rides the session's current model there. Our
  `CronStore` jobs accept an optional `model` field (POST `/api/schedule`
  `model`, persisted in `scheduled_tasks.json`, threaded `FiredTask` →
  `QueuedCommand.modelOverride` → `UserInput.modelOverride` at fire time),
  so the add form exposes a "执行模型" `MenuSelect` fed by the same
  `/api/session/context` model catalogue the composer's model seat reads
  ("跟随会话" = no override). A product-scope deviation with no upstream
  counterpart.
- **Trigger placement: sidebar footer, not a header icon pair.** The settings
  panel is opened from `Sidebar.tsx`'s footer, reusing
  `SettingsRoot.module.css`'s own `triggerRow`/`trigger`/`triggerLabel`
  classes (uniform 42px-height labeled rows) rather than ad hoc small icon
  buttons in the session-list header. This matches upstream's actual trigger
  location — dsh's real Settings trigger lives in the sidebar foot. An
  earlier revision placed both panels' triggers as small icon-only buttons in
  the session-list header instead — the wrong location and a visual style
  with no upstream basis.
- **Schedule seat (moved 2026-09-13): settings nav section, not a foot
  trigger.** An earlier revision opened the schedule surface as its own
  sidebar-foot overlay occupying `SidebarRoot.module.css`'s `.footerActions`
  seat. The seat assignment was wrong twice over: upstream's own schedule
  surface is a read-only conversation-header catalog (`ui-schedule`), and
  upstream's `.footerActions` is an empty horizontal flex (`renderSlot(
  'sidebar.footer.action')` registers no default occupant) — its comment
  describes where third-party additive actions *would* go, not one it ships
  itself. The schedule mutation UI now lives as the settings dialog's
  "定时任务" nav section (`SchedulePanel.tsx` exports `ScheduleSection`
  instead of the old overlay), so the sidebar foot is Settings-only,
  matching upstream's foot. Recorded here and in both components' class
  Javadocs since neither seat has an upstream occupant to align against.

### Product-scope deviations (general settings)

dsh's real "通用设置" (`GeneralSection` slot) shows 7 rows: Language,
Appearance, Font size, Transcript view, Busy-Enter behavior, Restart
Runtime, Keepalive mode. Only the first 5 are candidates for this app;
Restart Runtime and Keepalive mode need real process control this webui has
no access to and are out of scope entirely.

- **Language: framework landed, Settings panel is the first adopter.**
  `webui/src/i18n/` ports upstream's real `translate`/lookup-chain semantics
  (verified against the installed `@deepseek-ai/dsh-client-locale` package's
  compiled `lib/client.js`, not the possibly-stale decompiled tree): a
  namespace's active-locale entry, then that namespace's `zh` fallback, then
  the shared `common` namespace (same two-step fallback), then the key
  itself as a last resort so missing copy stays visible instead of blank.
  `store/locale.ts` persists the preference and detects the browser locale
  (`navigator.languages`) before falling back to `zh`, matching upstream's
  `resolveInitialLocale()`. Deliberately not ported: upstream's cordis-DI
  `register()`/unsubscribe/revision-counter machinery, which exists to
  support runtime plugin mount/unmount — this app's namespace dictionaries
  are static compile-time imports with no such use case. `LanguageRow` is
  the first real consumer (`i18n/dictionaries/settings.ts`'s `settings`
  namespace); the other ~44 files with hardcoded Chinese copy are left for
  incremental adoption in their own future tasks, matching upstream's own
  per-feature namespace-registration convention — this is a deliberate,
  scoped decision, not a silent gap.
- **Appearance, Font size: fully ported.** Both are genuinely pure frontend
  and already had complete persistence/DOM-apply logic in `theme.ts` (used
  by `index.html`'s pre-paint bootstrap) that was simply never wired to a
  control. `store/theme.ts` is a thin reactive wrapper around it; the row
  components are direct ports of upstream's `AppearanceRow`/`FontSizeRow`.
- **Transcript view (normal/compact): re-scoped semantic.** Upstream derives
  this from its own `ChatNode`/turn-process anchor model
  (`turn-process-presentation.ts`), which has no equivalent in this app's
  message schema. `store/transcriptView.ts` keeps only the portable part —
  a persisted preference — and uses it solely to set the *initial*
  open/closed state of thinking blocks and tool-call rows on newly rendered
  messages (`MessageItem.tsx`, `ToolCallRow.tsx`'s new `defaultOpen` prop).
  It does not retroactively re-collapse/expand already-rendered messages.
- **Busy-Enter behavior (queue/steer): `steer` has no backend hook.**
  Upstream's `steer` injects the new message into the agent's *currently
  running* turn; this gateway has no route for that (confirmed: no
  interrupt/injection endpoint exists). `queue` is implemented for real —
  `InputBar.tsx` holds the built content in `queuedContent` while
  `conversation.turnRunning` is true and flushes it via `onSubmit` on the
  `true → false` transition. `steer` falls through to today's immediate-send
  behavior; picking it does not change anything functionally yet.

### Product-scope deviations (models)

- **Flat `CustomModelConfig` list, not provider-grouped.** Upstream's real
  `ModelsSection` (578 lines) groups models under provider cards, each with
  an inline `ProviderEditor`, a first-use setup card that auto-expands, and
  separate Add/Add-custom actions. Our backend (`CustomModelCatalog`) has no
  provider grouping concept — one record is one `modelName` + `protocol` +
  `baseUrl` + `apiKey` + `headers` + `contextWindow` combination — so
  `ModelsSection.tsx` renders one flat list, one row per model, matching the
  data model instead of inventing a grouping the backend cannot express.
- **No delete-confirm modal.** Deletion is immediate on click, consistent
  with the existing rule/directory rows in `SettingsPanel.tsx`'s permissions
  and directories sections (neither confirms today) rather than adding
  upstream's `Modal`-based confirm only for this one section.
- **API key never echoed back.** `GatewayModelsPort.ModelEntry` carries
  `hasApiKey: boolean`, never the real secret; the webui shows a "已配置
  密钥/未配置密钥" pill instead of a masked value. Editing an entry leaves
  the API key field blank with a "留空则保留原值" placeholder — submitting
  without touching it omits `api_key` from the request (kept via the same
  missing/null/string tri-state `GatewaySettingsHandler.applyUserValue`
  already established for user-tier settings), matching the TUI's
  `CustomModelDialog` masked-bullet convention.
- **Shared backend, not a separate store.** `CliInteractiveSessionRunner`'s
  `gatewayModels(CustomModelCatalog)` factory wraps the exact
  `CustomModelJsonStore` instance the `/model` slash command already writes
  to (`~/.claude/model.json`), so edits from the webui's Models section and
  edits from `/model add-custom-model` are the same file under the same JVM
  lock — not two catalogs that need reconciling.
- **Field order and headers format mirror the TUI form.** `ModelsSection.tsx`
  uses the same field order as `CustomModelDialog.java` (name → protocol →
  base URL → API key → context window → headers) and the same single-line
  `"Name: Value; Name2: Value2"` headers text format (parsed with the same
  split-on-`;`-then-first-`:` rule), so the two surfaces feel identical even
  though neither ports the other's code.

### `vendor/dsh-context/` — context insight (Context tab, `/context`, Dashboard, jump)

Vendored from [bowenliang123/dsh-context](https://github.com/bowenliang123/dsh-context)
(Apache-2.0, **not** deepseek-harness), pinned at commit
`c463620b71426ea9b19ceb9f5c85b2ee55ab04c8` (package version `0.53.0`,
2026-09-16). The directory carries its own `LICENSE` and `NOTICE`. It is a
DeepSeek Harness plugin whose host half (`src/host/*`, an event-driven
projection fold) is **re-implemented in Java** (`claude-code-gateway`
`ContextTimelineFold` / `ContextTimelineLedger` / `GatewayContextTimelineHandler`)
and whose client half is vendored here minus the cordis plugin shell.

**Wire-shape exception.** The four new gateway routes
(`GET /api/session/context/{timeline,detail,content,overview}`) emit
dsh-context's `shared/types.ts` camelCase records (`ContextTimeline`,
`ContextTimelineDetail`, `ContextHeaders`, `ContextActivity`) verbatim so
`assemble.ts`, `categories.ts`, `headline.ts`, and the `DetailStore` run
unmodified. This is a deliberate exception to the gateway's snake_case
convention, recorded in the handler Javadoc and `openapi.yaml`. Two
extensions ride the detail payload: `agents: AgentRecord[]` (this session's
`Task`/`Agent` tool calls — our subagents are in-session calls, not child
sessions) and `headers: ContextHeaders` (one GET serves both). The existing
`GET /api/session/context` is untouched; `client/adapters.ts` maps it to
dsh's `ContextPressure` / `ContextBreakdown` / `TokenUsage`.

**Data source: live sessions only.** The ledger folds the process's active
TUI session and open headless sessions; a cold session yields `timeline:
null` and the tab shows the `shell.cold` note. The Dashboard lists only
those live sessions.

**Tailwind, scoped.** dsh-context's JSX is utility-class heavy, so
`tailwindcss@4` + `@tailwindcss/vite` compile **only** `vendor/dsh-context/**`
(`styles/tailwind.css`: `source(none)` + `@source "../client"`, no preflight,
no theme import — a hand-picked `@theme static` palette). No app markup can
pick up a utility class.

| File here | Upstream source | Notes |
|-----------|-----------------|-------|
| `shared/{types,estimate,fileOps,imageTokens,days,providers}.ts` | `src/shared/*` | Kept verbatim except `types.ts`: the cordis `declare module` augmentations are cut; `AgentRecord` and `ContextTimelineDetail.agents`/`.headers` are added (the claude-code-java extensions above). `shared/version.ts` is not vendored (no baseline gate). |
| `client/{categories,headline,assemble,brief,callSummary,format,dna,fileActivity,viewkit,i18n,icon,overscroll}.ts(x)` | same names | Pure helpers, kept verbatim (primitive imports repointed to `@primitives`). `i18n.ts` keys are re-exported through `src/i18n/dictionaries/context.ts` (ns `dsh-context`) with the shell's own `shell.*` keys appended. |
| `client/narrow.ts` | `src/client/services.ts` (extract) | Only the narrowing/sanitizing half (`asRecord`, `numOf`, `objectsOf`, `timelineOf`, `headersOf`, `detailOf`, `activityOf`, `ConversationNodeLike`…). Cut: `ClientCtx`, slots, remotes, `SessionsFace`, `useProjection` bindings. |
| `client/timelineSource.ts` | same | `DetailStore` rev cursor kept; `makeDetailFetcher` (harness remote) is cut and the fetcher is injected (`src/store/contextTimeline.ts` supplies `GET /detail`). `detailOf` also lifts `agents` and `headers`. |
| `client/overview.ts` | same | The pure pipeline (`filterRows`/`sortRows`/`pageOf`/`kpisOf`/`aggregateDays`/`relativeTime`) kept; `rowsOfSnapshot` and the workspace grouping (`sessionGroupsOf`/`groupCountsOf`/`inGroup`/`archivedSetOf`) are replaced by `rowsOfOverview` over the `/overview` payload. `usageTotalsOf` tolerates a null provider/model/bucket (the sanitizer's fast path leaves the cost tree unproven below two levels). |
| `client/agentTree.ts` | same | Layout half kept; the data half now consumes `AgentRecord[]` (`agentForestOf(agents, currentId, self)`, `subagentCostOf(agents)`) instead of the harness session list + `agentHeads`. |
| `client/cost.ts` | same | Verbatim plus one lookup patch: Anthropic dated snapshot ids (`claude-sonnet-4-5-20250929`) strip the `-YYYYMMDD` suffix before the price-book lookup. |
| `client/modelPrices.ts` | same | The models.dev fetch is replaced by a static Anthropic list-price table; unknown models render no cost cell. |
| `client/settings.ts` | same | `ctx.settingsScope` persistence replaced by `localStorage` (`dsh-context.settings`); `attach`/binder faces cut. `SettingsField` names are unchanged. |
| `client/workspacePath.ts` | `@deepseek-ai/dsh-util-workspace-path` | 2-function shim (`workspaceTitleOf`, `fileAddressFor`). |
| `client/adapters.ts` | new | `pressureOf` / `breakdownOf` / `usageOf`: the existing `/api/session/context` answer → dsh's meter types. |
| `client/components/{stackedBar,donut,sliceList,trendChart,requestDetail,browser,currentComposition,fileCard,statsTokens,statsTiming,events,richText,images,heatmap,overviewCard,detailNote,fetchOnMiss,errorBoundary,nodes}.tsx` | same | Pure-props cards, kept verbatim apart from `@primitives` imports and `exactOptionalPropertyTypes` widening (`?: T \| undefined`, function-typed optionals parenthesized). |
| `client/components/escapeClose.ts` | same | Overlays stack: only the newest active hook owns Escape (`stopImmediatePropagation`), so the `/context` modal and the Context Dashboard open together close one per press. |
| `client/components/agentGraph.tsx` | same | Props take `agents: AgentRecord[]`; the `useSessionsSnapshot`/`refreshSubagents`/session-switch plumbing is cut (open/keyOpen only pin the inspector). |
| `client/components/statsContext.tsx` | same | `subUsage` arrives as a prop (from `subagentCostOf(detail.agents)`) instead of the cordis subagent cost fold. |
| `styles/*.css` | `src/client/styles/*.css` | Verbatim; `tailwind.css` re-authored as described above. Imported once from `vendor/dsh-context/index.ts` in upstream cascade order. |
| **not vendored** | `contextView.tsx`, `contextModal.tsx`, `contextJump.tsx`, `overviewPanel.tsx`, `overviewButton.tsx`, `settingsCard.tsx`, `pluginInfo.tsx`, `upgradeGate.tsx`, `services.ts`, `command.ts`, `sidebar.ts`, `placement.ts`, `historyPage.ts`, `latestVersion.ts`, `meta.ts`, `agentHeads.ts`, `dockMeasure.ts`, `settingsJump.ts`, `modalStore.ts`, `viewFocus.ts`, `overviewStore.ts` | Cordis plugin shell, slot registrations, harness remotes, module stores. Re-assembled in `src/views/context/*` (`ContextView`, `ContextModal`, `ContextDashboard`, `ContextJumpButton`, `ContextSettingsSection`, `jump.ts`) over zustand stores (`src/store/{contextTimeline,contextOverview,conversationView}.ts`). |

**Cut points / deviations (deliberate):**
- **`/context` is a client-side command.** `InputBar` offers the trimmed draft
  to `onClientCommand`; `/context` opens the modal and never becomes a turn,
  exactly as dsh's slash-source registration does. No `TokenSpan` consume
  guard (our composer clears the draft itself).
- **Chat→Context jump resolves by time/turn.** dsh reads the request seq off
  its conversation-node seat; our chat rows carry the transcript timestamp
  and 1-based turn, so the relay carries those and `jump.ts` resolves the
  seq against the request ledger (exact seq → nearest time within 60s →
  last request of the turn → tab switch without a pin).
- **Agent Network is a one-level family.** Nodes are this session's
  `Task`/`Agent` tool calls settled from their `toolUseResult`; the ring is
  the subagent's own billed input/output split. No cross-session navigation.
- **No placement / right-sidebar surface, no Settings fold-out card.** The
  Context preferences render as rows of the host Settings dialog
  (`ContextSettingsSection`, "上下文" nav section); `defaultPlacement` is
  kept in storage but not offered.
- **No models.dev pricing, no DeepSeek peak/off-peak, no PTC `run_code`
  nested fileOps, no plugin-info / upgrade-gate cards.**
- **Dashboard has no workspace group chips** (no `useWorkspaces` seat); a card
  click selects the session through `useSessions.select`.

### `vendor/ui-attachment/` — chat-history image gallery + lightbox

Real image previews for pasted-image user turns, replacing the plain
`[Image #N]` text placeholder that was the only rendering before this
package existed. From `ui-attachment/src/client/MessageImage.tsx`
(single/tile layout) and `ui-attachment/src/client/ImageLightbox.tsx`
(click-to-enlarge), read via `gh api` before porting anything.

| File here | Upstream source | Notes |
|-----------|-----------------|-------|
| `MessageImages.tsx` (`ImageGallery`) | `client/MessageImage.tsx` | The `single`/`tile` variant split is ported (one image renders large and uncropped; two or more render as a fixed square row). The click-opens-lightbox interaction is ported. `thumbnail` (upstream's third, 48px uncropped variant) is not needed here and is not ported. |
| lightbox | `client/ImageLightbox.tsx` | Not re-ported: this app already has an adapted line-for-line port of the same upstream component at `vendor/dsh-context/client/components/images.tsx`'s `AttachmentLightbox` (portal to `document.body`, blurred mask, Escape/mask/close-button dismiss, focus restored to the opener), consuming the already-vendored `.lc-att-lightbox*` classes in `vendor/dsh-context/styles/attachments.css`. `AttachmentLightbox` was exported (it was previously private to that file) and is imported directly here so both call sites share one implementation and one set of tokens instead of a duplicate copy. |

Product-scope deviations (documented, not gaps):

- **Inline base64, not a durable attachment reference.** Upstream's
  multimodal pipeline stores a normalized reference per image and loads
  bytes lazily through an `ImageLoader` (an async `(attachment) =>
  Promise<string>` the harness backend serves). This app has no such
  store: the gateway projects the same base64 `{media_type, data}` shape
  the client already submits attachments in, straight into the
  `messages[]` snapshot row (`GatewayMessagesSnapshotHandler.userEntry`)
  and the `turn.started` mirror frame (`MirrorHub.onTurnStart`). Images
  are bounded by `ImageResizer`'s existing pre-send compression
  (2000×2000px, ~3.75MB raw / 5MB base64 cap), so snapshot payload growth
  stays bounded without a lazy-load path. `ImageLoader`, its `peek`
  prefetch, and all loading/error/retry UI states are cut — the bytes are
  already in hand when the row renders, so there is no load to await or
  retry.
- **`singleFit()`'s precomputed bounding box is not ported.** Upstream
  computes a scaled box from stored width/height *before* the image
  loads, so an async `ImageLoader` fetch has a jank-free placeholder to
  grow into. This app's images arrive already inline (no async load, no
  placeholder to reserve), and the wire payload carries no width/height
  metadata at all (only `media_type`/`data`) — so sizing is plain CSS
  (`max-width`/`max-height` + `object-fit: contain` for `single`,
  `object-fit: cover` on a fixed 64px box for `tile`) instead of a ported
  layout formula. The visual outcome (never upscale past the natural
  size, cap the long edge, crop multi-image tiles to a square) is the
  same; only the mechanism (CSS vs. precomputed JS box) differs.

## Local modifications

Keep this list complete; each entry needs a reason.

1. `vendor/ui-primitives/index.ts` — the `FishLogo` and `BrandWordmark`
   exports were **removed**, along with `FishLogo.tsx` and
   `BrandWordmark.tsx` themselves (2026-09-20). Both were upstream *brand*
   assets — the DeepSeek whale mark and the `deepseek` + `HARNESS` wordmark —
   not reusable primitives, and this product ships its own brand. Vendoring
   another project's brand art it can never render was the mistake; they had
   sat unused since the initial pass. The brand row now renders
   `src/views/brand/*` (see the "sidebar brand row" section above). Only the
   `HARNESS` badge geometry survives, copied verbatim into
   `PocoWordmark.tsx` and recorded there.
2. `webui/src/global.css` — `body { --dsw-alias-separator-primary:
   var(--dsw-alias-label-caption); }` shims a token that is **dangling
   upstream itself** (`StatsPills.module.css` consumes it but the whole
   upstream tree never defines it — verified at the pinned commit). Local
   convention: separator accents reuse the label-caption token. Delete
   this shim once upstream defines the token.
3. Vendored files must not be edited in place except through a recorded
   entry here. Prefer adapting at the app layer (extra `className`,
   wrapper) over patching vendored code.
4. `vendor/dsh-context/**` — every file that departs from upstream carries a
   `claude-code-java` note at the change; the per-file table in the
   `vendor/dsh-context/` section above is the ledger. Refresh from
   `https://raw.githubusercontent.com/bowenliang123/dsh-context/<commit>/src/<path>`
   (a different upstream from the deepseek-harness pin above).

## Refresh procedure

```bash
# Re-fetch one file at the pinned commit (never track master blindly):
curl -sfL "https://raw.githubusercontent.com/deepseek-ai/deepseek-harness/<commit>/<upstream-path>" -o <local-path>
# Then re-apply any local modifications recorded above and re-run:
#   cd webui && pnpm build && pnpm test
```

Bump the pinned commit only as a deliberate step: update this file, re-copy
the trees, re-apply the recorded modifications, and visually diff the
rendered UI against the previous build.

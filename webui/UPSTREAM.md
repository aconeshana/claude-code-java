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
| `vendor/chat-styles/` | scattered `.module.css` from `ui-chat` / `ui-conversation` / `ui-approval` / `ui-layout` / `ui-sidebar` / `ui-settings-general` / `ui-permission-presets` / `ui-schedule` | Styles only — the paired `.tsx` files are deeply cordis-coupled upstream, so the components are re-written locally against the same class names |

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
| `SettingsRoot.module.css` | `ui-settings-general` | `src/client/SettingsRoot.module.css` — the real Settings dialog shell (800px, two-column nav rail): `overlay`/`mask`/`panel`/`nav`/`navTitle`/`navList`/`navCell`/`content`/`header`/`actions`/`close`/`options`. `SettingsPanel.tsx` and `SchedulePanel.tsx` both render this shell directly instead of wrapping the generic `vendor/ui-primitives/Modal.tsx` (a 380px confirm-dialog primitive — a different, unrelated upstream component that earlier revisions of these two panels wrongly used, which is why they did not visually resemble dsh's Settings dialog). `SchedulePanel.tsx` omits the `nav`/`navList` classes since it has only one view. |
| `GeneralSection.module.css` | `ui-settings-general` | `src/client/GeneralSection.module.css` — the plain flex-column section wrapper each settings section is rendered in |
| `PermissionRow.module.css` | `ui-permission-presets` | `src/client/PermissionRow.module.css` — the reusable settings-row visual pattern (`row`/`rowText`/`title`/`desc`/`selector`/`chevron`), reused in `SettingsPanel.tsx` for every field row via a local `SettingsRow` wrapper and a `MenuSelect` helper (the `Menu` primitive + `.selector`/`.chevron`) that replaces native `<select>` elements |
| `ScheduleCatalogAction.module.css` | `ui-schedule` | `src/client/ScheduleCatalogAction.module.css` — the task-row visual pattern (`row`/`status`/`statusDot`/`prompt`/`metadata`), reused in `SchedulePanel.tsx`'s task list. The `.menu`/`.trigger`/`.count`/`.triggerOpen` classes in this file belong to upstream's read-only header-popover trigger and are not used here. |
| `SidebarRoot.module.css` | `ui-sidebar` | `src/client/SidebarRoot.module.css` — the sidebar column frame `Sidebar.tsx` renders inside (`root`/`scroll`). `.newSession` (the 38px/12px-radius bar) and `.regionArea` (the seat that hosts the session browser, canceling the shell's edge inset so the nested scrollbar sits flush) **are** ported — `POST /api/sessions/open` already mints a session with no `session_id` (openapi.yaml), so this was a real backend-supported feature an earlier revision had wrongly dropped by omission, not a genuine gap. Still out of scope: the rail-collapse classes (`root.collapsed`, `logoRow`, `brand`, collapse keyframes) — our sidebar has no collapse/rail mode or brand wordmark. Its `.footArea` comment ("additive actions stack above Settings: each occupant owns its button geometry") is the structural fact behind the trigger placement below: it is why `Sidebar.tsx` stacks its own trigger buttons in the footer rather than the header, why the schedule trigger sits above the settings trigger, and why the two occupy separate seats (`.footerActions` over `.settingsArea`, matching upstream's `sidebar.footer.action` / `sidebar.settings` slot split) — the seats are not interchangeable, because `.footerActions` is a horizontal flex while a `triggerRow` is `flex: none; width: calc(100% + 4px)`, so two rows in one seat push the second past the clipped sidebar column. |
| `AppearanceRow.module.css` | `ui-theme` | `src/client/AppearanceRow.module.css` — the three-cube theme selector (`group`/`title`/`cubeRow`/`themeCube`/`selected`), rendered by a local `AppearanceRow` component in `SettingsPanel.tsx`'s general section and wired to `store/theme.ts` |
| `FontSizeRow.module.css` | `ui-theme` | `src/client/FontSizeRow.module.css` — the font-size stepper pill (`row`/`control`/`stepper`/`value`/`arrows`/`arrow`), rendered by a local `FontSizeRow` component in `SettingsPanel.tsx`'s general section and wired to `store/theme.ts`. `EnterBehaviorRow.module.css`/`TranscriptViewRow.module.css` are **not** vendored separately — both rows are pixel-identical to the already-vendored `PermissionRow.module.css` row/selector pattern and reuse `SettingsPanel.tsx`'s existing `SettingsRow`/`MenuSelect` helpers. |

### `vendor/chat-styles/` — session sidebar (`Sidebar.tsx`/`SessionRows.tsx`)

| File here | Upstream package | Upstream file |
|-----------|-------------------|----------------|
| `WorkspaceBrowser.module.css` | `ui-workspace` | `src/client/WorkspaceBrowser.module.css` — the session-list seat: its own `.root` wrapper (declares `--dsh-session-list-edge-inset`, canceling `SidebarRoot.module.css`'s `.regionArea` negative margin so the nested scrollbar sits flush), `.sectionHeader`/`.sectionLabel`/`.headerActions`, `.listArea`/`.treeBody`/`.list`/`.fade`, and the grouped-project row shell (`.groupSection`, `.sessionOverflowButton`). `.sectionHeader` uses `justify-content: flex-end`; upstream's dropped `.searchSlot` (`flex: 1; max-width: 28px; margin-left: auto`) is what pushes `.sectionLabel` left / `.headerActions` right, so a local `Sidebar.module.css` `.headerActions { margin-left: auto }` reproduces just that split without the search UI itself. The flat "In one list" view and its view-options menu are not ported (no backend surface — see `session sidebar` scope note in `Sidebar.tsx`'s class Javadoc). |

### `vendor/chat-styles/` — reasoning row & tool-call row

| File here | Upstream package | Upstream file |
|-----------|-------------------|----------------|
| `StatsPills.module.css` | `ui-chat` | `src/client/chat/StatsPills.module.css` — vendored verbatim in an earlier pass, consumed since the stats-pills round (see `vendor/dsh-stats-pills/`) |
| `stat-dialog.module.css` | `ui-chat` | `src/client/chat/stat-dialog.module.css` — the shared stat-dialog surface skin (panel/title/titleRule/details), verbatim; all consumed `--dsw-*` tokens resolve in the vendored theme styles |
| `TurnUsagePanel.module.css` | `ui-chat` | `src/client/chat/TurnUsagePanel.module.css` — the turn-tail pill chrome (root/trigger/label), verbatim; consumed by `vendor/dsh-stats-pills/TurnUsagePanel.tsx` |
| `MessageIconActions.module.css` | `ui-chat` | `src/client/chat/MessageIconActions.module.css` — the shared copy/clock icon-row chrome (actions row, `timeStart`/`timeEnd` clock sides, the 28px `.action` buttons, hover-reveal `data-actions-reveal` gate), vendored verbatim in an earlier pass; consumed since the icon-row round by `vendor/dsh-stats-pills/MessageIconActions.tsx` |
| `TurnTailNodeView.module.css` | `ui-chat` | `src/client/chat/TurnTailNodeView.module.css` — the turn-tail column and its `.actions { margin-left: -6px }` offset, verbatim; only the `.actions` class is consumed (the turn-tail wrapper here is the assistant stack itself, not a separate node view) |
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
- **No turn-level fold/unfold row.** This app's message model renders one
  row per assistant message, not upstream's per-turn `ChatNode` grouping —
  there is no equivalent concept to fold multiple tool calls/messages under
  one `TurnProcessNodeView`-style summary button.
- **No Inspect pill / trajectory-view jump.** Upstream's hover-revealed
  Inspect button on an expanded tool row jumps to a trajectory/replay view
  this app does not have; the button is not rendered.
- **`.visuallyHidden` merged into `ToolRow.module.css`, not its own file.**
  Upstream keeps this one utility class in a separate `accessibility.module.css`
  module shared by `ReasoningRow.tsx` and `ToolRow.tsx`. A single class does
  not warrant its own vendor file here; it lives in the already-vendored
  `ToolRow.module.css` and both `ReasoningRow.tsx` and `ToolCallRow.tsx`
  import it from there.

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
- **Trigger placement: sidebar footer, not a header icon pair.** Both panels
  are opened from `Sidebar.tsx`'s footer, reusing `SettingsRoot.module.css`'s
  own `triggerRow`/`trigger`/`triggerLabel` classes (uniform 42px-height
  labeled rows) rather than ad hoc small icon buttons in the session-list
  header. This matches upstream's actual trigger location — dsh's real
  Settings trigger lives in the sidebar foot, and `SidebarRoot.module.css`'s
  `.footArea` comment documents that additive actions (like our schedule
  trigger, which has no upstream equivalent) stack above the Settings
  trigger rather than sitting beside it. An earlier revision placed both as
  small icon-only buttons in the session-list header instead — the wrong
  location and a visual style with no upstream basis.

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

## Local modifications

Keep this list complete; each entry needs a reason.

1. `vendor/ui-primitives/index.ts` — unchanged so far. `FishLogo` /
   `BrandWordmark` exports stay (other vendored components do not import
   them) but the app never renders them; brand marks are local by design.
2. `webui/src/global.css` — `body { --dsw-alias-separator-primary:
   var(--dsw-alias-label-caption); }` shims a token that is **dangling
   upstream itself** (`StatsPills.module.css` consumes it but the whole
   upstream tree never defines it — verified at the pinned commit). Local
   convention: separator accents reuse the label-caption token. Delete
   this shim once upstream defines the token.
3. Vendored files must not be edited in place except through a recorded
   entry here. Prefer adapting at the app layer (extra `className`,
   wrapper) over patching vendored code.

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

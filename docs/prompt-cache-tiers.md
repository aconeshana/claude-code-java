# Prompt Cache Tiers

Every model request pays for prompt tokens at one of three billing rates:
a **cache write** costs 1.25×, a **cache read** costs 0.1×, and an uncached
token costs 1×. Whether a request should carry `cache_control` markers —
and where — depends on whether any *later* request will replay its prefix.
A marker stamped on a prefix that nothing ever replays is a pure 25% write
premium.

This document defines the three tiers the codebase uses to classify every
non-main-loop model call, the `@CacheTier` annotation that records them,
and the settings keys that let each one-shot scenario use a different model.

## The three tiers

```
MAIN_LOOP:       ──●──●──●──●──→
                 (the main timeline; every later request extends it,
                  so every marker earns reads)

FORKED_PREFIX:   ──●──●──●──╮
                           ╰──○
                 (forks the main timeline at a node; the prefix keeps the
                  main line's markers — reads still hit — but the forked
                  branch has no successor, so its own marker is never read)

ONE_SHOT:        ○
                 (shares no prefix with the main timeline; the whole request
                  is never replayed, so no marker anywhere)
```

● = request prefix carries cache markers (write paid, reads follow)
○ = request carries no cache markers (plain 1× billing)

### MAIN_LOOP — full caching

The request *is* the main timeline, or a retry chain over the same prefix.
Later requests extend it, so markers are investments that later reads repay.
This is the `CreateMessageRequest` default (`promptCachingEnabled = true`),
no call-site action required.

Examples: the main conversation loop, the auto-mode stage-1/2 classifier
retry chain (a released 197 wire snapshot proves the markers), the
`/model <name>` server probe (`model_validation` — 236 sends it *with* a
`cache_control: ephemeral` marker because the probed model is about to
become the main model).

### FORKED_PREFIX — prefix cached, fork uncached

The request forks the main conversation at some node: the prefix is sent
as-is with its markers left **on** (reads hit the cache the main loop
already paid for), and only the appended one-shot tail is sent marker-free.
In 236 this is `cacheSafeParams` + `skipCacheWrite: true`; in Java it is
`SideQuery.Request.skipCacheWrite(true)`, which keeps
`promptCachingEnabled = true` while suppressing the trailing marker.

Examples (236 `w5` forks): `compact`, `away_summary`, `agent_summary`,
`rename_generate_name`, `side_question`.

Cost structure to keep in mind: the prefix discount **only exists when the
fork runs on the same model as the main loop**. A different model misses
the cache key, so the whole prefix is billed at 1× with no successor to
repay it — strictly worse than staying on the main model. That is why
forked-prefix scenarios are deliberately **not** configurable per scenario
(see below); compact additionally pins itself to the live main model.

### ONE_SHOT — no caching

The request shares no prefix with anything and is never replayed. Markers
anywhere are pure premium, so `promptCachingEnabled(false)` removes them
all and the request bills a flat 1×. In 236 these requests go through the
`$ae`/`j2t` helpers (`enablePromptCaching ?? false`) or raw `cme()` calls.

Examples: session titles, tool-use summaries, the permission explainer,
hook stop-condition and prompt-hook evaluators, insights facet analysis.

## Recording a tier: @CacheTier

`claude-code-core`'s `@CacheTier` annotation documents which tier a class
or method implements (`MAIN_LOOP`, `FORKED_PREFIX`, `ONE_SHOT`) and doubles
as the audit surface for the tier taxonomy. When you add or move a model
call, annotate it — the tier is a billing decision, not an implementation
detail.

## Scenario model overrides (ONE_SHOT only)

One-shot scenarios are leaf work, so pointing them at a cheaper model
saves output tokens with no prefix cost. Two settings families expose
this; **only ONE_SHOT scenarios get keys** — MAIN_LOOP follows the
session's model by definition, and FORKED_PREFIX scenarios would forfeit
the prefix read discount (see above).

### Small-fast family — `sideQueryModel` chain

Resolution order:

```
<scenario key>  >  sideQueryModel  >  ANTHROPIC_SMALL_FAST_MODEL
               >  ANTHROPIC_DEFAULT_HAIKU_MODEL  >  custom main model
               >  claude-haiku-4-5
```

| Key | Scenario |
|-----|----------|
| `sideQueryModel` | Global default for the family |
| `sessionTitleModel` | First-prompt terminal tab title |
| `renameModel` | `/rename` session-name generation |
| `toolSummaryModel` | One-line tool-use summaries |

A blank scenario value falls back to `sideQueryModel`, so the global key
stays the single default and per-scenario keys only narrow it.

### Main-model family — pinned scenarios

These scenarios are reasoning-sensitive, so 236 pins them to the main
model (or Opus) rather than the small-fast chain. Their keys therefore
fall back to the *released* resolution, not to `sideQueryModel` — a
configured global small model must not silently downgrade them:

| Key | Scenario | Blank-value default (236 parity) |
|-----|----------|----------------------------------|
| `permissionExplainerModel` | Permission explainer | Main model |
| `hookEvaluatorModel` | Hook stop-condition + prompt-hook evaluators | Main model (`claude-sonnet-4-20250514` if unknown) |
| `insightsModel` | `/insights` facet analysis | `ANTHROPIC_DEFAULT_OPUS_MODEL` → configured model |

All keys are `/config`-addressable (`/config set <key> <model>`) through
the `EXTENSION_SETTINGS` table; the Lanterna `ConfigPanel` intentionally
omits them to preserve the official 197 row inventory.

## Audit trail

| Tier | Scenario | Model resolution |
|------|----------|------------------|
| MAIN_LOOP | main loop, auto-mode classifier, `model_validation` | session model |
| FORKED_PREFIX | compact, away summary, agent summary, rename fork, side question | small-fast chain / live main model (compact) — **not** per-scenario configurable |
| ONE_SHOT | session titles, `/rename`, tool summaries, permission explainer, hook evaluators ×2, insights | per-scenario keys above |

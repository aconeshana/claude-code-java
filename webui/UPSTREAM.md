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
| `vendor/chat-styles/` | scattered `.module.css` from `ui-chat` / `ui-conversation` / `ui-approval` / `ui-layout` / `ui-sidebar` | Styles only — the paired `.tsx` files are deeply cordis-coupled upstream, so the components are re-written locally against the same class names |

## Local modifications

Keep this list complete; each entry needs a reason.

1. `vendor/ui-primitives/index.ts` — unchanged so far. `FishLogo` /
   `BrandWordmark` exports stay (other vendored components do not import
   them) but the app never renders them; brand marks are local by design.
2. Vendored files must not be edited in place except through a recorded
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

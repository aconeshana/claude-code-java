# Claude Code Java — Development Guide

A terminal and web-enabled coding agent implemented in Java 25. It supports Anthropic endpoints as
well as user-defined Anthropic-compatible, OpenAI-compatible (`chat` / `responses`), and Google
Gemini (`gemini`) model endpoints, with an in-process HTTP+SSE Web UI gateway and optional LAN
multi-protocol model relay. The repository contains the 16 product modules listed below, the
`webui/` React + Vite frontend, and the build-time `build-recipes` helper project.

## Quick Reference

| Task | Command |
|------|---------|
| Build | `./gradlew build` |
| Run | `java -jar claude-code-app/build/libs/poco-*.jar` |
| Test | `./gradlew test` |
| Package | `./gradlew :claude-code-app:shadowJar` |
| Native quick image | `./gradlew :claude-code-app:nativeQuickCompile` |
| Single module | `./gradlew :claude-code-core:build` |
| Commit-time quality gates | `./gradlew --no-configuration-cache --no-parallel preCommitQuality` |
| WebUI typecheck / test / build | `pnpm --dir webui exec tsc -b && pnpm --dir webui test && pnpm --dir webui build` |

The product modules and `build-recipes` use Gradle with Kotlin DSL. See
[docs/build-and-test.md](docs/build-and-test.md) for build, test, packaging, native-image,
and platform-asset details.

## Module Map

```
claude-code-core     → stable model protocols, policies, value objects
claude-code-http     → shared OkHttp transport
claude-code-api      → provider configuration, routing, multi-protocol wire codec, and API clients
claude-code-permissions → allow/deny/ask system
claude-code-runtime  → QuerySession + headless session/turn orchestration + UI application ports
claude-code-gateway  → in-process HTTP+SSE third session endpoint (web UI, scheduled tasks, LAN model proxy)
claude-code-tools    → built-in + runtime-provided tool system (some tools are platform- or feature-gated)
claude-code-commands → slash command registry (commands, availability gates, and hidden stubs)
claude-code-mcp      → Model Context Protocol
claude-code-session  → JSONL session persistence
claude-code-services → hooks, compaction, memory, settings, and plugins
claude-code-ui       → Lanterna TUI
claude-code-lsp      → LSP integration
claude-code-cli      → Picocli entry point and composition root
claude-code-sdk      → out-of-process Agent SDK and in-process SDK MCP servers
claude-code-app      → assembly + packaging
```

`build-recipes` is a build-time OpenRewrite/Refaster helper project and is not part of the
product runtime module graph. `webui/` is the standalone React + Vite frontend bundled into
`claude-code-app` via `:claude-code-app:buildWebui`. The executable module graph, allowed
dependency directions, and ownership boundaries live in `ModuleArchitectureTest`,
`GuideOwnershipTest`, and [docs/architecture.md](docs/architecture.md).

## Code Standards (enforced)

- **Java 25**: use records for immutable data carriers, sealed interfaces for closed unions,
  pattern-matching switches where they clarify modeled cases, and virtual threads for blocking I/O
- **Visibility**: package-private by default; `public` only for deliberate package or module APIs
- **`@Explanation` annotation**: briefly explain declarations that intentionally differ from the
  original product. Do not use it for ordinary refactoring, language-level implementation details,
  or unfinished work.

**IMPORTANT**: Before changing compatibility-sensitive behavior, read the public protocol
documentation, the current implementation, and the relevant tests. Do not invent APIs or wire
formats.

## Testing and Verification

- Run the full regression suite with `./gradlew test`.
- Run a focused module suite with `./gradlew :<module>:test`.
- Run the complete build with `./gradlew build`.
- Run commit-time PMD, dependency-convergence, and OpenRewrite gates with
  `./gradlew --no-configuration-cache --no-parallel preCommitQuality`.
- Package the executable fat JAR with `./gradlew :claude-code-app:shadowJar`.
- Run the opt-in process-level CLI startup smoke tests with
  `./gradlew :claude-code-app:flagStartupSmoke`.

### Mandatory Pre-Push Verification Gate (CI Alignment)

Before running `git push` to GitHub, you **MUST** verify all CI checks locally so that CI passes on the first push without repeated fixup commits:

1. **Untracked File Audit (`git status`)**: Verify that every newly created Java class, TypeScript module, CSS module, vendor package, or test fixture referenced by modified files is staged (`git add`) and not left untracked.
2. **Java CI Gate (`.github/workflows/java.yml` & quality gates)**:
   - Run `./gradlew test` (or module tests + `pmdMain pmdTest` + `rewriteDryRun` / `preCommitQuality`) on Java 25.
   - When touching reflection, Jackson models, CLI startup, or subprocess relaunch paths, also verify `./gradlew :claude-code-app:nativeQuickCompile` and the `web -d` / `web status` / `web stop` smoke lifecycle.
3. **WebUI CI Gate (`.github/workflows/webui.yml`)**:
   - In `webui/`, run `pnpm exec tsc -b` (typecheck), `pnpm test` (Vitest unit/component suite), and `pnpm build` (Vite production bundle).

The Gradle wrapper pins Gradle 9.7.0, product modules compile with the Java 25 toolchain and
`--release 25`, and Gradle itself must run on Java 25 or newer. Platform-specific distributions
bundle ripgrep for macOS, Linux, and Windows on `arm64` and `amd64`; macOS and Linux distributions
also bundle cc-connect. See [docs/build-and-test.md](docs/build-and-test.md) for native-image
commands, bundled asset verification, local Lanterna development, and checks outside the default
test task.

## Configuration

- Bearer authentication for the independent gateway channel: `ANTHROPIC_AUTH_TOKEN`.
- Custom model catalog (`$CLAUDE_CONFIG_DIR/model.json`, default `~/.claude/model.json`): defines
  custom endpoints with `protocol` (`anthropic`, `chat`, `responses`, or `gemini`), `baseUrl`,
  `apiKey`, `contextWindow`, `multimodal`, and custom `headers`. Managed via `/models` in TUI or
  the Web UI Models Settings (including single-provider `/v1/models` & `/v1beta/models` batch
  discovery).
- Web gateway & LAN model proxy:
  - Fixed single-instance port `19845` (test override: `CLAUDE_WEB_PORT`); discovery record at
    `$CLAUDE_CONFIG_DIR/web-gateway.json`.
  - Scheduled tasks store at `$CLAUDE_CONFIG_DIR/scheduled_tasks.json` (executed by both interactive
    TUI sessions and the resident `CliWebCronDaemon` hosted by `claude web`).
  - LAN model proxy configuration & keys (`ccgw_*`) at `$CLAUDE_CONFIG_DIR/lan-gateway.json`.
- File-backed settings sources: user-level `$CLAUDE_CONFIG_DIR/settings.json` (default:
  `~/.claude/settings.json`; `cowork_settings.json` when cowork plugins are enabled), project-level
  `<project>/.claude/settings.json`, local `<project>/.claude/settings.local.json`, the OS-specific
  administrator-managed `managed-settings.json` and sibling `managed-settings.d` drop-ins, and an
  optional `--settings` file or inline JSON object.
- Transcript storage: `$CLAUDE_CONFIG_DIR/projects/<sanitized-cwd>/<sessionId>.jsonl` (default:
  `~/.claude/projects/<sanitized-cwd>/<sessionId>.jsonl`).
- Configuration directory override: `CLAUDE_CONFIG_DIR` (default configuration home:
  `~/.claude`). It relocates the Claude configuration home and resolves the global config as
  `$CLAUDE_CONFIG_DIR/.claude.json` unless the legacy `$CLAUDE_CONFIG_DIR/.config.json` exists.

## Architecture Notes

- `claude-code-runtime` owns query-session creation, turn submission, streaming, tool-use
  orchestration, interruption recovery, and application-facing ports.
- `claude-code-gateway` owns the embedded HTTP+SSE server for the Web UI (`/`), session mirror/link
  multiplexing, workspace file tree/preview endpoints, scheduled tasks management, runtime control,
  and the multi-protocol LAN model gateway (`/v1/messages`, `/v1/chat/completions`, `/v1/responses`,
  `/v1beta/models/*`).
- `claude-code-services` owns concrete integrations such as hooks, compaction, memory,
  settings, plugins, and platform processes.
- `claude-code-sdk` is an external-consumer leaf. Its query implementation launches the CLI
  in a separate JVM and can host SDK MCP servers in process; the CLI does not depend on it.
- Model-visible tools are assembled dynamically from built-ins and runtime providers. Do not
  document a fixed tool count; use `ToolBootstrap` and `CliToolchainAssembler` as the sources
  of truth.

## Docs

- [Architecture & module dependencies](docs/architecture.md)
- [Development standards & type mapping](docs/dev-standards.md)
- [Terminal UI component catalog](docs/ui-components.md)
- [Build, test & configuration](docs/build-and-test.md)
- [Memory system — CLAUDE.md scopes + @include + /memory](docs/memory-system.md)
- [Prompt cache tiers — @CacheTier & scenario model keys](docs/prompt-cache-tiers.md)
- [Brand & theme assets](docs/brand-assets.md)
- [Session Host & IM integration](docs/session-host-im.md)
- [Web UI & TUI decoupled gateway architecture](docs/web-gateway-architecture.md)

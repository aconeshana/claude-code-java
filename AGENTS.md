# Claude Code Java — Development Guide

A terminal coding agent implemented in Java. It supports Anthropic endpoints and user-defined
Anthropic-compatible or OpenAI-compatible model endpoints. The repository contains 15 product
modules plus the build-time `build-recipes` helper project.

## Quick Reference

| Task | Command |
|------|---------|
| Build | `./gradlew build` |
| Run | `java -jar claude-code-app/build/libs/claude-code-app-0.1.0-SNAPSHOT.jar` |
| Test | `./gradlew test` |
| Package | `./gradlew :claude-code-app:shadowJar` |
| Single module | `./gradlew :claude-code-core:build` |
| Commit-time quality gates | `./gradlew --no-configuration-cache --no-parallel preCommitQuality` |

The product modules and `build-recipes` use Gradle with Kotlin DSL. See
[docs/build-and-test.md](docs/build-and-test.md) for build, test, packaging, native-image,
and platform-asset details.

## Module Map (15 product modules)

```
claude-code-core     → stable model protocols, policies, value objects
claude-code-http     → shared OkHttp transport
claude-code-api      → provider configuration, routing, and API clients
claude-code-permissions → allow/deny/ask system
claude-code-runtime  → QuerySession + headless session/turn orchestration + UI application ports
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
product runtime module graph.

### Key Dependency Chain

```
app → cli
sdk → { core, session, cli (runtime only) }
cli → { core, http, api, permissions, runtime, session, mcp, commands, ui, tools, services, lsp }
      # The composition root explicitly declares every module whose types it uses.
ui → { core, commands, permissions, tools, lsp, runtime }
     # UI must not depend on services, MCP, or session implementations; use runtime/application ports.
services → { core, http, api, mcp, tools, permissions, session, runtime }
commands → { core, runtime }
           # CLI leaf adapters provide session, permission, tools, configuration, and MCP capabilities.
api → { core, http }
mcp → { core, http }
lsp → core
runtime → { core, permissions }
tools → { core, http, permissions, session, lsp, mcp, runtime }
session → core
permissions → core
http → OkHttp (no internal module dependencies)
core → no internal module dependencies
```

See [docs/architecture.md](docs/architecture.md) for the complete dependency graph.

**Module dependency rule**: Internal Gradle project dependencies use
`implementation(project(...))` by default. `api(project(...))` is allowed only when the dependency's
types are deliberately exposed as part of the module's public ABI, and the exception must be recorded
in `ModuleArchitectureTest`. Every production import from another internal module requires a direct
production dependency. Test imports must be covered by a production dependency or an explicit
`testImplementation` or `testCompileOnly` dependency. Do not rely on compile-classpath leakage
through a third module. The module guard enforces allowed directions, acyclicity, direct dependency
declarations, reviewed internal `api` exports, and selected ownership boundaries. Its allowed-edge
policy is a ceiling, not an exact dependency snapshot, so obsolete edges may be removed.

**Tool ownership rule**: Built-in tool implementations, registration support, and private tool
state belong in `claude-code-tools`. Place an implementation in another module only when its actual
compile-time dependencies require that boundary.

## Code Standards (enforced)

- **Java 25**: use records for immutable data carriers, sealed interfaces for closed unions,
  pattern-matching switches where they clarify modeled cases, and virtual threads for blocking I/O
- **Visibility**: package-private by default; `public` only for deliberate package or module APIs
- **Async**: query orchestration uses virtual threads and `BlockingQueue`-backed streaming handoff;
  prefer `CompletionStage<T>` at API boundaries and `CompletableFuture<T>` when the implementation
  owns completion
- **`@Explanation` annotation**: briefly explain declarations that intentionally differ from the
  original product.

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

The Gradle wrapper pins Gradle 9.7.0, product modules compile with the Java 25 toolchain and
`--release 25`, and Gradle itself must run on Java 25 or newer. Platform-specific distributions
bundle ripgrep for macOS, Linux, and Windows on `arm64` and `amd64`; macOS and Linux distributions
also bundle cc-connect. See [docs/build-and-test.md](docs/build-and-test.md) for native-image
commands, bundled asset verification, local Lanterna development, and checks outside the default
test task.

## Configuration

- API key resolution order: `--api-key`, `ANTHROPIC_API_KEY`, then the
  `primaryApiKey` field in the global config (legacy field: `apiKey`). The default global config is
  `~/.claude.json`; with `CLAUDE_CONFIG_DIR`, it is resolved relative to that override.
- Bearer authentication for the independent gateway channel: `ANTHROPIC_AUTH_TOKEN`.
- File-backed settings sources: user-level `$CLAUDE_CONFIG_DIR/settings.json` (default:
  `~/.claude/settings.json`; `cowork_settings.json` when cowork plugins are enabled), project-level
  `<project>/.claude/settings.json`, local `<project>/.claude/settings.local.json`, the OS-specific
  administrator-managed `managed-settings.json` and sibling `managed-settings.d` drop-ins, and an
  optional `--settings` file or inline JSON object. File-backed sources are parsed as strict JSON
  after an optional UTF-8 BOM; comments and trailing commas are rejected.
- Transcript storage: `$CLAUDE_CONFIG_DIR/projects/<sanitized-cwd>/<sessionId>.jsonl` (default:
  `~/.claude/projects/<sanitized-cwd>/<sessionId>.jsonl`).
- Configuration directory override: `CLAUDE_CONFIG_DIR` (default configuration home:
  `~/.claude`). It relocates the Claude configuration home and resolves the global config as
  `$CLAUDE_CONFIG_DIR/.claude.json` unless the legacy `$CLAUDE_CONFIG_DIR/.config.json` exists.

The API key and bearer token are resolved independently; neither is a fallback or alias for the other.

## Architecture Notes

- `claude-code-runtime` owns query-session creation, turn submission, streaming, tool-use
  orchestration, interruption recovery, and application-facing ports.
- `claude-code-services` owns concrete integrations such as hooks, compaction, memory,
  settings, plugins, and platform processes.
- `claude-code-ui` is a presentation adapter. Its production code does not depend on
  `claude-code-services`, `claude-code-mcp`, or `claude-code-session`.
- `claude-code-cli` is the composition root and wires concrete implementations to runtime,
  command, and UI ports.
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

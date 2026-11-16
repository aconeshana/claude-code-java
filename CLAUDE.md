# Claude Code Java — Development Guide

A terminal coding agent implemented in Java, with Anthropic and user-defined Anthropic or
OpenAI-compatible model endpoints. The repository contains the product modules listed below plus
the build-time `build-recipes` helper project.

## Quick Reference

| Task | Command |
|------|---------|
| Build | `./gradlew build` |
| Run | `java -jar claude-code-app/build/libs/poco-*.jar` |
| Test | `./gradlew test` |
| Package | `./gradlew :claude-code-app:shadowJar` |
| Single module | `./gradlew :claude-code-core:build` |
| Commit-time quality gates | `./gradlew --no-configuration-cache --no-parallel preCommitQuality` |

Gradle with Kotlin DSL is the only build system. See
[docs/build-and-test.md](docs/build-and-test.md) for build, test, packaging, native-image,
and platform-asset details.

## Module Map

```
claude-code-core     → stable model protocols, policies, value objects
claude-code-http     → shared OkHttp transport
claude-code-api      → provider configuration, routing, and API clients
claude-code-permissions → allow/deny/ask system
claude-code-runtime  → QuerySession + headless session/turn orchestration + UI application ports
claude-code-gateway  → in-process HTTP+SSE third session endpoint (web/API entry)
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

`build-recipes` is a build-time OpenRewrite/Refaster helper project and is not part of the product
runtime module graph. The executable module graph, allowed dependency directions, and ownership
boundaries live in `ModuleArchitectureTest` and [docs/architecture.md](docs/architecture.md).

## Code Standards (enforced)

- **Java 25**: use records for immutable data carriers, sealed interfaces for closed unions,
  pattern-matching switches where they clarify modeled cases, and virtual threads for blocking I/O
- **Visibility**: package-private by default; `public` only for deliberate package or module APIs
- **`@Explanation` annotation**: briefly explain declarations that intentionally differ from the
  original product. Do not use it for ordinary refactoring, language-level implementation details,
  or unfinished work.

**IMPORTANT**: Before changing compatibility-sensitive behavior, read the public protocol documentation,
the local behavioral contract, and the relevant tests. Do not invent APIs or wire formats.

## Testing and Verification

- Run the full regression suite with `./gradlew test`.
- Run a focused module suite with `./gradlew :<module>:test`.
- Run the complete build with `./gradlew build`.
- Run commit-time PMD, dependency-convergence, and OpenRewrite gates with
  `./gradlew --no-configuration-cache --no-parallel preCommitQuality`.
- Package the executable fat JAR with `./gradlew :claude-code-app:shadowJar`.
- Run the opt-in process-level CLI startup smoke tests with
  `./gradlew :claude-code-app:flagStartupSmoke`.

The Gradle wrapper pins Gradle 9.7.0, and product modules compile with the Java 25 toolchain.
Platform-specific distributions bundle ripgrep for macOS, Linux, and Windows on `arm64` and
`amd64`; macOS and Linux distributions also bundle cc-connect. See
[docs/build-and-test.md](docs/build-and-test.md) for native-image commands, bundled asset
verification, local Lanterna development, and checks outside the default test task.

## Configuration

- Bearer authentication for the independent gateway channel: `ANTHROPIC_AUTH_TOKEN`.
- Settings sources: user-level `~/.claude/settings.json`, project-level
  `.claude/settings.json`, local `.claude/settings.local.json`, `--settings` file or inline overrides,
  and OS-specific managed policy settings.
- Transcript storage: `~/.claude/projects/<sanitized-cwd>/<sessionId>.jsonl`.
- Configuration directory override: `CLAUDE_CONFIG_DIR` (default: `~/.claude`). It relocates the
  Claude configuration home and resolves the global config as `$CLAUDE_CONFIG_DIR/.claude.json`
  unless the legacy `$CLAUDE_CONFIG_DIR/.config.json` exists.

## Architecture Notes

- `claude-code-runtime` owns query-session creation, turn submission, streaming, tool-use
  orchestration, interruption recovery, and application-facing ports.
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

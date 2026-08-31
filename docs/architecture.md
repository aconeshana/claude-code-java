# Architecture

## Module Structure

```text
claude-code-java/
├── claude-code-core/          # Stable model protocols, policies, and value objects
├── claude-code-http/          # Shared OkHttp transport infrastructure
├── claude-code-api/           # Provider configuration, routing, and API clients
├── claude-code-permissions/   # Permission system (allow/deny/ask)
├── claude-code-runtime/       # Query sessions plus headless session/turn orchestration
├── claude-code-tools/         # Built-in and runtime-provided tool system
├── claude-code-commands/      # Slash commands, availability gates, and hidden stubs
├── claude-code-mcp/           # Model Context Protocol integration
├── claude-code-session/       # JSONL session persistence
├── claude-code-services/      # Hooks, compaction, memory, settings, and plugins
├── claude-code-ui/            # Lanterna terminal UI
├── claude-code-lsp/           # Language Server Protocol integration
├── claude-code-cli/           # Picocli entry point and composition root
├── claude-code-sdk/           # Out-of-process Agent SDK and in-process SDK MCP servers
└── claude-code-app/           # Application assembly and packaging
```

The Gradle build also includes `build-recipes`, a build-time OpenRewrite/Refaster helper project.
It is not part of the 15-module product runtime graph.

## Module Dependencies

```text
claude-code-app → claude-code-cli

claude-code-sdk → { core, session, cli (runtime only) }

claude-code-cli → { core, http, api, permissions, runtime, session, mcp,
                    commands, ui, tools, services, lsp }
claude-code-ui → { core, commands, permissions, tools, lsp, runtime }
claude-code-commands → { core, runtime }
claude-code-services → { core, http, api, mcp, permissions, session, tools, runtime }
claude-code-tools → { core, http, permissions, session, lsp, mcp, runtime }

claude-code-runtime → { core, permissions }
claude-code-lsp → core
claude-code-mcp → { core, http }
claude-code-session → core
claude-code-permissions → core
claude-code-api → { core, http }
claude-code-http → OkHttp (no internal module dependencies)
claude-code-core → no internal module dependencies
```

The dependency direction is intentional:

- `core` owns stable domain values, model-facing protocols, pure policies, and tool-execution
  contracts. It does not own session lifecycle or query orchestration.
- `http` owns the shared OkHttp connection pool, dispatcher, and base client configuration used by
  HTTP-facing modules.
- `api` owns provider configuration and routing, Anthropic and OpenAI-compatible clients, and the
  current Bedrock and Vertex stub clients. `mcp` and `lsp` own their external-protocol clients,
  transports, discovery, and protocol data. Model-visible adapters for MCP and LSP live in `tools`,
  not in the protocol modules.
- `permissions` owns permission policy and allow/deny/ask decisions.
- `runtime` owns query-session creation, turn submission, streaming, tool-use orchestration,
  interruption recovery, session switching, and application-facing ports. It depends only on
  `core` and `permissions`.
- `session` owns JSONL transcript persistence, session discovery, session metadata, and statistics.
- `tools` owns built-in tool implementations, tool registration support, and private tool state.
- `services` owns concrete integrations such as hooks, compaction, memory, settings, plugins, and
  platform processes.
- `commands` owns slash-command registration and command-facing application ports. Its production
  code depends only on `core` and `runtime`.
- `ui` is a presentation adapter. It consumes runtime/application ports and does not depend on
  `services`, `mcp`, or `session` implementations.
- `cli` is the in-process composition root. It parses command-line options and wires the concrete
  API, runtime, services, tools, commands, session, and UI implementations.
- `sdk` is an external-consumer adapter. It owns the public Java Agent SDK facade, NDJSON transport,
  callbacks, SDK-hosted MCP servers, and optional `SessionStore` materialization. It launches the
  CLI in a separate JVM through a runtime-only dependency; the CLI does not depend on the SDK.
- `app` owns executable JAR and native-image assembly, bundled native assets, and packaging.

## Module Boundary Policy

A Gradle module is a compile-time dependency boundary, not a synonym for a product feature. A
feature may span stable contracts, runtime orchestration, concrete adapters, commands, and UI.
Separate modules are used to protect stable policy, isolate external protocols or heavyweight
dependencies, and establish independently testable lifecycle boundaries.

| Role | Modules | Direction rule |
|------|---------|----------------|
| Stable inner modules | `core`, `permissions`, `runtime` | Depend only on lower-level contracts and policy; never on concrete UI or service implementations |
| Shared infrastructure and protocol adapters | `http`, `api`, `session`, `mcp`, `lsp` | Contain transport, persistence, or external-protocol details; never depend on presentation modules |
| Application and feature implementations | `tools`, `services`, `commands` | May compose inner policies and protocol adapters, but must not become dependencies of lower-level modules |
| Presentation, composition, and distribution | `ui`, `cli`, `sdk`, `app` | `ui` renders and interacts, `cli` composes the in-process application, `sdk` controls the CLI out of process, and `app` packages the executable |

Internal project dependencies use `implementation(project(...))` by default. An `api` project
dependency is allowed only when the dependency's types are deliberately exposed as part of a
module's supported public ABI and the exception is recorded in `ModuleArchitectureTest`. Every
production import from another internal module requires a direct production dependency. Test
imports require either that production dependency or an explicit `testImplementation` or
`testCompileOnly` dependency; source code must not rely on compile-classpath leakage through
another module.

`claude-code-app/src/test/java/com/claudecode/app/ModuleArchitectureTest.java` is the executable
source of truth for the allowed graph. It verifies allowed edges, acyclicity, direct production and
test dependencies, and reviewed internal `api` exports. It also guards key ownership rules,
including the runtime query boundary, UI and command isolation, interactive runtime assembly in the
CLI, and construction of `DefaultQuerySessionFactory` in the CLI composition root. The allowed-edge
policy is a ceiling rather than an exact dependency snapshot, so obsolete edges can be removed
without weakening the test.

## Runtime Composition

### Query and turn processing

`claude-code-runtime/src/main/java/com/claudecode/runtime/`

- `QuerySession` is the application-facing boundary for a query conversation. It groups submission,
  conversation, configuration, execution, and cache-sharing fork capabilities.
- `QuerySessionFactory` is the creation seam; the standard `DefaultQuerySessionFactory` is composed
  by the CLI.
- The runtime query loop manages streaming responses, repeated `tool_use` iterations, token and cost
  accounting, compaction integration, and structured output.
- Streaming handoff uses virtual threads and `BlockingQueue`-backed sequencing.
- `SessionLifecycle` coordinates ordered in-process session switching.
- `InteractionCoordinator` provides the presentation-neutral lifecycle for permission prompts,
  `AskUserQuestion`, and local secret sudo input. Lanterna and Session Link act as endpoint adapters.

### Tool assembly

`claude-code-tools/src/main/java/com/claudecode/tools/`

The model-visible inventory is assembled at runtime from built-ins and runtime providers, including
MCP, skills, tasks, workflows, LSP, structured output, platform-specific tools, and feature-gated
tools. The inventory is not a fixed count. `ToolBootstrap` and `CliToolchainAssembler` are the
sources of truth for registration and composition.

### Interactive UI

`claude-code-ui/src/main/java/com/claudecode/ui/lanterna/`

The Lanterna UI receives application ports and feature runtime objects from the CLI composition
root. It owns terminal rendering, input routing, dialogs, overlays, transcript presentation, and
interactive session workflows. Persistence and service operations remain behind injected ports;
the UI does not construct session or service infrastructure.

`CliInteractiveRuntimeAssembler` creates the interactive application ports, shared feature runtime,
and launch state. `InteractiveSessionPort` is the UI-owned boundary for session discovery,
transcript reads, metadata, statistics, bash-output paths, and exit persistence; the CLI adapter
delegates those operations to `claude-code-session`.

### Session persistence

`claude-code-session/src/main/java/com/claudecode/session/`

- `SessionStorage` reads and appends JSONL transcript records and manages associated metadata.
- `TranscriptRecorder` sequences asynchronous writes per transcript file on virtual threads.
- `SessionFileLock` provides cross-process file locking used by transcript and task-state persistence.

### Hooks and compaction

`claude-code-services/src/main/java/com/claudecode/services/`

- Hook configuration supports shell-command, prompt, HTTP, and agent hook kinds. Internal code can
  also register callback hooks. `HookEvent` is the source of truth for the supported tool, prompt,
  session, subagent, permission, compaction, task, configuration, worktree, file,
  working-directory, and display lifecycle events.
- `CompactService` coordinates micro-compaction, automatic and manual full compaction, partial
  compaction, and opt-in recovery after provider context-limit errors.
- The automatic compaction threshold is derived from the model context window after reserving summary
  tokens and a fixed compaction buffer. `CLAUDE_CODE_AUTO_COMPACT_WINDOW` can cap the context window,
  and a valid `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE` selects the smaller of its percentage-based threshold
  and the normal buffered threshold; the threshold is not a universal fixed percentage.

## Tech Stack

| Category | Technology |
|----------|------------|
| Language | Java 25 (records, sealed interfaces, pattern matching, virtual threads) |
| Build | Gradle 9.7.0 with Kotlin DSL |
| JSON | Jackson 2.18.9 |
| Terminal UI | Lanterna 3.2.0-cc3 — [aconeshana/lanterna](https://github.com/aconeshana/lanterna), resolved from JitPack by default or `mavenLocal()` with `-PlanternaLocal` |
| CLI | Picocli 4.7.6 |
| Markdown | commonmark-java 0.22.0 |
| HTTP | OkHttp 5.4.0 with a shared pool, dispatcher, fast fallback, and environment proxy support |
| Logging | SLF4J 2.0.13 and Logback 1.5.34 |
| Utilities | Commons Lang3 3.18.0, Commons IO 2.16.1, ICU4J 74.2 |
| LSP | Eclipse LSP4J 0.24.0 |
| Testing | JUnit 5.10.3 and jqwik 1.9.0 |
| Native packaging | GraalVM Build Tools 1.1.9 and Shadow 9.6.1 |

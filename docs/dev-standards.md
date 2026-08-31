# Development Standards

## Java 25 patterns

Gradle must run on Java 25 or newer. All included Java subprojects, including
the build-time `build-recipes` project, compile with a Java 25 toolchain.

- Use records for immutable data carriers.
- Use sealed interfaces for closed type hierarchies.
- Use pattern-matching switch expressions when they make the modeled cases
  clearer.
- Prefer virtual threads for blocking I/O and blocking background work. Use a
  platform thread only when the lifecycle or integration requires one, such as
  a JVM shutdown hook or a dedicated terminal event loop.
- Keep types package-private by default; make a type public only when it belongs
  to a deliberate package or module API.

## Java design patterns

| Concern | Preferred Java pattern |
|---------|------------------------|
| Streaming production | `Iterator<T>` backed by a virtual-thread producer and `BlockingQueue` |
| Closed type hierarchy | `sealed interface` with permitted implementations |
| Immutable data | `record` and immutable collections |
| Optional configuration | `Optional<T>` at query boundaries or a builder with optional fields |
| Keyed data | `Map<K,V>` |
| Sequential data | `List<T>` or `T[]`, according to the API contract |
| Asynchronous result | `CompletionStage<T>` at API boundaries; `CompletableFuture<T>` when the implementation owns completion |
| Observable state | Immutable snapshots plus an explicit listener or subscription lifecycle |
| JSON validation | Jackson `JsonNode` values validated by `JsonSchemaValidator` against the subset it supports |

Do not treat these patterns as mechanical replacements. Preserve the public
protocol and behavioral contract, and choose an implementation that fits the
Java ownership and lifecycle model.

## Streaming model

`QuerySession.Submission.submitMessage(Object, SubmitOptions)` returns an
`Iterator<SDKMessage>`. `DefaultQuerySession` creates a `QueryLoop`, which
bridges its virtual-thread producer to the consumer through a
`LinkedBlockingQueue`:

~~~java
Iterator<SDKMessage> messages = querySession.submission().submitMessage(prompt, options);
while (messages.hasNext()) {
    SDKMessage message = messages.next();
    // Process the message.
}
~~~

## Adding functionality

### New tool

1. Add the implementation to the appropriate package under
   `claude-code-tools/src/main/java/com/claudecode/tools/`.
2. Extend `Tool<I, O>` and implement its required identity, description, input
   schema, and execution methods. Override validation, result mapping,
   permission checks, and capability metadata where the tool requires them.
3. Register the core built-in tool set in `ToolBootstrap`. Register tools and
   providers that require CLI-owned adapters, live settings, or platform and
   feature gates in `CliToolchainAssembler`. MCP proxy tools are synchronized by
   the CLI's MCP runtime adapters after server discovery.
4. Add focused tests under `claude-code-tools/src/test/java/` and integration
   tests in the owning assembly module when registration or lifecycle behavior
   is involved.

### New command

1. Add built-in implementations to the appropriate package under
   `claude-code-commands/src/main/java/com/claudecode/commands/impl/`.
2. Implement `Command`, including `CommandMetadata`, execution behavior, and
   any availability, visibility, argument, immediacy, long-running, or
   non-interactive policy that the command needs.
3. Register static built-ins in
   `claude-code-commands/src/main/java/com/claudecode/commands/bootstrap/CommandFactory.java`.
   Use the existing synchronization paths for plugin commands, workflows, and
   skills. The CLI composition root registers commands that require live
   adapters, including `/mcp` and MCP prompt commands, after those adapters are
   available.
4. Add metadata, policy, and behavior tests in the owning module.

### New service

1. Add the implementation to the appropriate package under
   `claude-code-services/src/main/java/com/claudecode/services/`.
2. Follow the existing feature package boundaries rather than creating a
   general-purpose service bucket.
3. Expose a narrow runtime/application port when commands or UI need the
   capability. Keep concrete service wiring in the CLI composition root.
4. Add tests in the corresponding service test package and integration tests
   where the adapter is assembled.

### New UI component

1. Place the component in the owning slice under
   `claude-code-ui/src/main/java/com/claudecode/ui/`, such as `render`,
   `syntax`, `lanterna/components`, `lanterna/dialog`, `lanterna/input`,
   `lanterna/overlay`, `lanterna/transcript`, `lanterna/status`,
   `lanterna/statusline`, `lanterna/stats`, or a feature package under
   `lanterna/features`.
2. Reuse `LanternaTheme` for shared theme colors and `MessagePanel.Segment`
   for transcript text runs instead of duplicating compatible styling or
   segment conventions.
3. Keep feature state and operations in the feature, controller, or runtime
   port that owns them rather than accumulating them in `LanternaReplScreen`.
4. Marshal component mutations onto the Lanterna GUI thread. Perform blocking
   work on a virtual thread and publish only the resulting UI update to the GUI
   thread.
5. Add focused tests under
   `claude-code-ui/src/test/java/com/claudecode/ui/` in the matching package.

## Module and dependency rules

- Add a direct Gradle project dependency for every cross-module production
  import; do not rely on a transitive compile classpath. Test-only imports must
  likewise be covered by a production dependency or explicit test dependency.
- Use `implementation(project(...))` by default. Use `api(project(...))` only
  when an internal module type is intentionally exposed in the public ABI, and
  record the exception in `EXPORTED_PROJECT_DEPENDENCIES` in
  `claude-code-app/src/test/java/com/claudecode/app/ModuleArchitectureTest.java`.
  The current graph has no exported internal project dependencies.
- Keep UI production code on its declared lower-level modules and
  runtime/application ports. It must not import `claude-code-services`,
  `claude-code-session`, or `claude-code-mcp`; tests may declare explicit test
  dependencies when integration coverage needs those modules.
- Keep `claude-code-cli` as the composition root for concrete adapters and
  cross-module runtime assembly.
- Update `ModuleArchitectureTest` when a new allowed dependency edge or
  exported edge is intentional. The test also guards acyclicity, direct
  dependency declarations, and selected package-boundary constraints.

See [Architecture](architecture.md) for the current module graph and boundary
policy.

## Intentional behavior documentation

Use `@Explanation` to record why a declaration intentionally differs from the
original product, including added capabilities, platform adaptations, and
provider compatibility. Do not use it for routine refactoring, language-level
implementation details, or unfinished work. When the explained behavior is
controlled by a setting, define a safe effective default so that an absent key
does not unexpectedly change behavior.

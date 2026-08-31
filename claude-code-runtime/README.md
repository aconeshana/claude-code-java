# `com.claudecode.runtime.turn` — headless turn engine

The front-end-agnostic core of a REPL session. Everything in this package drives one
conversation **turn** (submit → stream → complete) with **zero UI dependency**, so the
Lanterna TUI, and a future WebUI / API, are just interchangeable *sinks* on the same engine.

> **One-line rule:** nothing here may import UI/Lanterna or service implementation types.
> Gradle and the architecture tests both enforce this boundary.

---

## Background — why this exists

The turn orchestration (stream loop, interrupt/auto-restore, queue, skill/hook cleanup) used to
be welded to Lanterna inside `TurnExecutor` + `LanternaReplScreen`. The moment the goal became
**"support more than the TUI — WebUI, API"**, the right shape changed: not "give each UI widget a
narrow port", but an **event-stream architecture**.

`core` already emits the right stream — `SDKMessage`, from `QueryEngine.submitMessage() →
Iterator<SDKMessage>`. So each front-end is just a **sink** that renders that stream:

```
                          ┌── TUI  (LanternaSessionSink → Lanterna panels)
core ──SDKMessage──▶ TurnEngine ──SessionSink──┼── WebUI (→ JSON / WebSocket)   [future]
     (headless orchestration)                  └── API  (→ SSE)                 [future]
```

`core` itself is headless (nothing in it depends on `ui`), but so far nothing outside the
TUI drives turns. This package is the missing shared turn engine.

---

## What's in this package

| File | Role |
|---|---|
| `TurnEngine.java` | Orchestrates one turn: stream loop, in-flight queue, `turnInFlight`, meaningful-content tracking, interrupt/rewind/cleanup. Emits to a `SessionSink`. **The headless core.** |
| `SessionSink.java` | Output **port** a front-end implements: `onTurnStart` / `onMessage` / `onError` / `onTurnComplete` / `onIdle`. |
| `UserInput.java` | Input value: `displayText`, `queryContent`, `pasted`, `permissionMode`. What a front-end produces per submission. |
| `TurnOutcome.java` | Turn-level result handed to `onTurnComplete` (userCancel, restored, elapsed, restore payload). |
| `ConversationOps.java` | Narrow port for the two history ops on interrupt-restore (`dropLastPromptHistoryEntry`, `rewindBeforeLastRealUser`). Wired to existing impls so those classes need not move yet. |
| `TurnAwakeGuard.java` | Port for platform-specific sleep prevention while a turn is active. |

`QueuedCommand` remains a core queue value type and is consumed by the runtime engine.

TUI adapter (in `com.claudecode.ui.lanterna`, **not** this package):
- `LanternaSessionSink` — implements `SessionSink`, holds all Lanterna collaborators, renders the stream.
- `LanternaReplScreen.buildLayout` — constructs the engine + sink and wires the ports.

Tests (`claude-code-runtime/src/test/java/com/claudecode/runtime/turn/`):
- `TurnEngineTest` — drives a whole turn **synchronously** (`onUi`/`background` = `Runnable::run`, a `QueryEngine` subclass fake, a `RecordingSink`).
- `RecordingSink` — a `SessionSink` implemented with **zero Lanterna types**; the "second consumer" proof.
- `TurnEnginePackageBoundaryTest` — the boundary guard.

---

## Threading contract (do not break this)

The engine holds two injected executors and calls sink methods on specific threads. This exactly
reproduces the original executor's timing:

| Sink call | Thread | Sink must… |
|---|---|---|
| `onTurnStart(input)` | **synchronously on the submitting thread** (TUI: GUI thread) | render the echo *directly* — synchronous echo before any message streams is load-bearing (image de-dup timing depends on it). |
| `onMessage` / `onError` / `onTurnComplete` | the turn's **background** thread | marshal its own UI work (TUI sink wraps in `onUi`). |
| `onIdle` | the engine's **publish** thread (`onUi`) | render directly. |

- `onUi : Consumer<Runnable>` — publish executor. **Prod TUI:** `gui.getGUIThread()::invokeLater`. **Web:** direct / per-connection serialize. **Test:** `Runnable::run`.
- `background : Executor` — runs the blocking query loop. **Prod:** one virtual thread per turn. **Test:** `Runnable::run`.
- `submit` atomically rejects a second live turn. Adapters may use `isInFlight()` as a fast path,
  but must still treat rejection as the race-safe answer and enqueue the command.
- Completion is fail-safe: sink/publisher/cleanup callback failures are logged and isolated so the
  in-flight guard is always released and the queue can continue. A rejected publish falls back to
  running the continuation inline.

Injecting both as `Runnable::run` is what makes a whole turn unit-testable synchronously.

---

## How the boundary is guaranteed

Three layers, strongest first.

1. **Compiler boundary:** `claude-code-runtime` depends only on `core` and `permissions`, so
   UI/Lanterna/service imports cannot compile.
2. **Automated guards:** `TurnEnginePackageBoundaryTest` scans every `.java` in this package,
   while app-level `ModuleArchitectureTest` verifies the complete Gradle graph and forbidden imports.
   *Limitation:* it's an import-line scan — it won't catch fully-qualified references with no
   import (a pathological case), only real `import` violations.
3. **Design makes violation unnecessary:** the engine speaks only through its own interfaces
   (`SessionSink`, `ConversationOps`), JDK executors (`Consumer<Runnable>`, `Executor`), and
   core/JDK value types. There is no place that *needs* a UI type.
4. **Existence proof:** `RecordingSink` + `TurnEngineTest` implement/drive the whole thing with no
   Lanterna type. If a port leaked a TUI concept, this wouldn't compile.

---

## Handoff: adding a second adapter (WebUI / API)

When you build the second front-end, you do **not** touch `TurnEngine`. You provide:

1. **A `SessionSink` impl** — turn `SDKMessage` into your wire format (WebUI → JSON/WS, API → SSE).
   Respect the [threading contract](#threading-contract-do-not-break-this): `onTurnStart` is where
   you'd emit your "user submitted" event; `onMessage` is the streamed body; `onTurnComplete`
   carries interrupt/restore.
2. **`onUi` + `background`** — for web these are usually a per-connection serializer (or direct)
   and a request/worker thread pool. There is no GUI thread; `onUi` just means "publish".
3. **`ConversationOps`** — your history/rewind impl (or reuse a lowered `PromptHistory`).
4. **`onDrain : Consumer<QueuedCommand>`** — how a queued command is parsed/re-submitted (the TUI's
   is bash-wrap / slash-reroute / plain → `engine.submit`).
5. **`recordLastSubmitted`** — store last input if you support undo.
6. Build a `UserInput` from the request and call `engine.submit(...)`; use `engine.isInFlight()` to
   decide submit-vs-enqueue.

**The first thing you'll hit (and it's expected):** two concerns are **not** in `SessionSink` and
your adapter must handle them itself:
- **Permission protocol.** Today `QueryEngine.setPermissionAskCallback` **blocks** the turn thread
  waiting for a TUI dialog. Web can't block a request thread on user input — you need the async
  request/response protocol. Design it against the real
  transport, not before.
- **Multi-session routing.** `turnInFlight` + the queue are per-engine-instance (one session). The TUI
  has exactly one. Web/API are concurrent multi-session → construct **one `TurnEngine` per session**
  and route inputs to the right one. The engine is already instance-scoped, so this is
  "new one per session + a registry", not a state rewrite.
- **Command-level UI callbacks are a separate surface.** `SessionSink` covers the *turn stream* only.
  Slash-command UI (compact progress `CompactProgressEvent`, and the `/btw` `/effort` `/export`
  `/mcp` `/hooks` dialogs) go through `CommandContext` launcher callbacks, **not** `SessionSink`.
  Your adapter needs its own implementations of those (or lift them into a shared `CommandUi` port).

**The check that you got the abstraction right:** your `SessionSink` should compile and a smoke run
should work *without* re-touching `TurnEngine`. If you find yourself wanting to change the engine to
fit web, stop — the port is probably leaking a TUI assumption; fix the port, not the engine.

---

## Physical module boundary

The split is complete: `claude-code-runtime` depends only on `core` and `permissions`.
`LanternaSessionSink`, `SleepPreventer`, and `HookEngine` remain outside and are wired through
`SessionSink`, `TurnAwakeGuard`, and a turn-cleanup callback. Future Web/API adapters can depend
on runtime without pulling the terminal UI or the service layer into their compile classpath.

---

## Invariants (don't regress these)

- **No UI imports** in this package (guarded; see above).
- **`SessionSink` carries only `SDKMessage` + turn-level events** — never UI primitives
  (`appendLine`/`scrollUp`) and never `MessageCollapser` folding (that's a TUI presentation policy;
  each front-end decides its own).
- **`onTurnStart` stays synchronous** (echo-before-stream).
- **At most one live turn per engine.** `submit` must retain the atomic guard and every accepted
  turn must reach a release path, including setup/executor/sink/cleanup failures.
- **One `TurnEngine` = one session.** Keep it instance-scoped; don't reintroduce static turn state.

## Verify

```bash
./gradlew :claude-code-runtime:test \
  --tests 'com.claudecode.runtime.turn.TurnEngineTest' \
  --tests 'com.claudecode.runtime.turn.TurnEnginePackageBoundaryTest'
./gradlew :claude-code-app:test \
  --tests 'com.claudecode.app.ModuleArchitectureTest'
```

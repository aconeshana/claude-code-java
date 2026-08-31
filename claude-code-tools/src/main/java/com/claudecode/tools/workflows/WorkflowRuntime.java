package com.claudecode.tools.workflows;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.TurnTokenBudget;
import com.claudecode.core.util.AgentId;
import com.claudecode.tools.validation.SchemaValidator;
import com.claudecode.tools.output.SyntheticOutputTool;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;
import org.graalvm.polyglot.proxy.ProxyExecutable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;




public final class WorkflowRuntime {

    public static final int DEFAULT_MAX_AGENTS = 1_000;
    public static final int DEFAULT_MAX_COLLECTION_ITEMS = 4_096;
    static final long DEFAULT_STALL_MS = 180_000L;
    static final int MAX_STALL_RETRIES = 5;
    static final Duration THROTTLE_RETRY_DELAY = Duration.ofSeconds(45);
    private static final Duration EXECUTION_TIMEOUT = Duration.ofSeconds(30);
    private static final ScheduledExecutorService DEADLINE_TIMER =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "workflow-vm-deadline");
            thread.setDaemon(true);
            return thread;
        });

    private final WorkflowAgentExecutor agentExecutor;
    private final WorkflowCatalog catalog;
    private final int concurrency;
    private final int maxAgents;
    private final int maxCollectionItems;
    private final Duration executionTimeout;
    private final Duration throttleRetryDelay;

    /** Live progress/controller bridge used by WorkflowTask and /workflows. */
    public interface Listener {
        Listener NOOP = new Listener() {};
        default void onProgress(JsonNode progress) {}
        default void onFailure(String failure) {}
        default void onAgentController(String agentId, AbortController controller) {}
    }

    public WorkflowRuntime(WorkflowAgentExecutor agentExecutor, WorkflowCatalog catalog) {
        this(agentExecutor, catalog, defaultConcurrency());
    }

    public WorkflowRuntime(WorkflowAgentExecutor agentExecutor, WorkflowCatalog catalog,
                           int concurrency) {
        this(agentExecutor, catalog, concurrency,
            DEFAULT_MAX_AGENTS, DEFAULT_MAX_COLLECTION_ITEMS, EXECUTION_TIMEOUT,
            THROTTLE_RETRY_DELAY);
    }

    WorkflowRuntime(WorkflowAgentExecutor agentExecutor, WorkflowCatalog catalog,
                    int concurrency, int maxAgents, int maxCollectionItems) {
        this(agentExecutor, catalog, concurrency, maxAgents, maxCollectionItems,
            EXECUTION_TIMEOUT, THROTTLE_RETRY_DELAY);
    }

    WorkflowRuntime(WorkflowAgentExecutor agentExecutor, WorkflowCatalog catalog,
                    int concurrency, int maxAgents, int maxCollectionItems,
                    Duration executionTimeout) {
        this(agentExecutor, catalog, concurrency, maxAgents, maxCollectionItems,
            executionTimeout, THROTTLE_RETRY_DELAY);
    }

    WorkflowRuntime(WorkflowAgentExecutor agentExecutor, WorkflowCatalog catalog,
                    int concurrency, int maxAgents, int maxCollectionItems,
                    Duration executionTimeout, Duration throttleRetryDelay) {
        this.agentExecutor = Objects.requireNonNull(agentExecutor, "agentExecutor");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.concurrency = Math.max(1, concurrency);
        this.maxAgents = Math.max(1, maxAgents);
        this.maxCollectionItems = Math.max(1, maxCollectionItems);
        this.executionTimeout = executionTimeout == null || executionTimeout.isNegative()
            || executionTimeout.isZero() ? EXECUTION_TIMEOUT : executionTimeout;
        this.throttleRetryDelay = throttleRetryDelay == null || throttleRetryDelay.isNegative()
            ? THROTTLE_RETRY_DELAY : throttleRetryDelay;
    }

    /**
     * Compiles a parsed workflow body without executing it.
     */
    public Optional<String> validate(WorkflowDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        try (Context context = newContext()) {
            Source source = Source.newBuilder("js", "async function __workflowMain() {\n"
                    + definition.body() + "\n}\n", definition.path() == null
                    ? definition.metadata().name() + ".js" : definition.path().toString())
                .buildLiteral();
            context.parse(source);
            return Optional.empty();
        } catch (PolyglotException e) {
            return Optional.of(cleanPolyglotMessage(e));
        } catch (Exception e) {
            return Optional.of(messageOf(e));
        }
    }

    public WorkflowExecutionResult execute(WorkflowDefinition definition, JsonNode args,
                                           ToolExecutionContext parentContext) {
        return execute(definition, args, parentContext, List.of());
    }

/** Executes a run, reusing only the unchanged leading {@code agent} calls. */
    public WorkflowExecutionResult execute(WorkflowDefinition definition, JsonNode args,
                                           ToolExecutionContext parentContext,
                                           List<WorkflowAgentCacheEntry> resumeCache) {
        return execute(definition, args, parentContext, resumeCache, null, Listener.NOOP);
    }


    public WorkflowExecutionResult execute(WorkflowDefinition definition, JsonNode args,
                                           ToolExecutionContext parentContext,
                                           List<WorkflowAgentCacheEntry> resumeCache,
                                           WorkflowJournal journal, Listener listener) {
        return execute(definition, args, parentContext, resumeCache, 0,
            new SharedLimits(concurrency), journal,
            listener == null ? Listener.NOOP : listener);
    }

    private WorkflowExecutionResult execute(WorkflowDefinition definition, JsonNode args,
                                            ToolExecutionContext parentContext,
                                            List<WorkflowAgentCacheEntry> resumeCache,
                                            int nestingDepth, SharedLimits limits,
                                            WorkflowJournal journal, Listener listener) {
        Objects.requireNonNull(definition, "definition");

        if (definition.inlineScript()) WorkflowDeterminism.validate(definition.body());
        long started = System.currentTimeMillis();
        ExecutionState state = new ExecutionState(parentContext, limits, resumeCache,
            journal, listener);
        if (nestingDepth == 0) {
            for (WorkflowPhase phase : definition.metadata().phases()) {
                state.resolvePhase(phase.title(), null);
            }
        }
        AtomicBoolean timedOut = new AtomicBoolean();
        ScheduledFuture<?> deadline = null;

        try (Context context = newContext()) {
            deadline = DEADLINE_TIMER.schedule(() -> {
                timedOut.set(true);
                context.close(true);
            }, executionTimeout.toMillis(), TimeUnit.MILLISECONDS);
            installBindings(context, state, nestingDepth);
            String sourceText = bootstrap(args, nestingDepth) + "\nasync function __workflowMain() {\n"
                + definition.body() + "\n}\n"
                + "async function __workflowEntry() {\n"
                + "  const value = await __workflowMain();\n"
                + "  return JSON.stringify({value: value === undefined ? null : value});\n"
                + "}\n__workflowEntry().catch(error => Promise.reject(String(error?.message ?? error)));";
            Source source = Source.newBuilder("js", sourceText,
                    definition.path() == null ? definition.metadata().name() + ".js"
                        : definition.path().toString())
                .buildLiteral();
            Value promise = context.eval(source);
            deadline.cancel(false);
            deadline = null;
            CompletableFuture<String> completed = new CompletableFuture<>();
            promise.invokeMember("then",
                (ProxyExecutable) values -> {
                    completed.complete(values.length == 0 ? "{\"value\":null}" : values[0].asString());
                    return null;
                },
                (ProxyExecutable) values -> {
                    String message = values.length == 0 ? "Workflow failed" : guestError(values[0]);
                    completed.completeExceptionally(new WorkflowRuntimeException(message));
                    return null;
                });
            while (!completed.isDone()) {
                JsCompletion jsCompletion = state.completions.poll(100, TimeUnit.MILLISECONDS);
                if (jsCompletion != null) jsCompletion.deliver();
                checkAborted(parentContext);
            }
            String envelope = completed.get();
            JsonNode value = JsonUtils.parseTree(envelope).path("value");
            if (value.isMissingNode()) value = NullNode.getInstance();
            return new WorkflowExecutionResult(value, state.limits.agentCalls.get(),
                state.tokens.get(), state.toolUses.get(),
                System.currentTimeMillis() - started,
                state.logs, state.failures, state.phase.get(), state.agentCache);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorkflowRuntimeException("Workflow execution interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof WorkflowRuntimeException workflowError) throw workflowError;
            throw new WorkflowRuntimeException(messageOf(cause), cause);
        } catch (PolyglotException e) {
            if (timedOut.get()) {
                throw new WorkflowRuntimeException("Workflow script timed out after 30000ms", e);
            }
            throw new WorkflowRuntimeException(cleanPolyglotMessage(e), e);
        } catch (WorkflowRuntimeException e) {
            throw e;
        } catch (Exception e) {
            if (timedOut.get()) {
                throw new WorkflowRuntimeException("Workflow script timed out after 30000ms", e);
            }
            throw new WorkflowRuntimeException(messageOf(e), e);
        } finally {
            if (deadline != null) deadline.cancel(false);
            state.cancelTimers();
        }
    }

    private Context newContext() {
        return Context.newBuilder("js")
            .allowHostAccess(HostAccess.NONE)
            .allowHostClassLookup(_ -> false)
            .allowIO(IOAccess.NONE)
            .allowCreateThread(false)
            .allowNativeAccess(false)
            .allowExperimentalOptions(true)
            .option("js.ecmascript-version", "2024")
            .option("js.disable-eval", "true")
            .option("engine.WarnInterpreterOnly", "false")
            .build();
    }

    private void installBindings(Context context, ExecutionState state, int nestingDepth) {
        Value bindings = context.getBindings("js");
        bindings.putMember("__workflowLog", (ProxyExecutable) values -> {
            state.addLog(values.length == 0 ? "" : values[0].asString());
            return null;
        });
        bindings.putMember("__workflowRecordFailure", (ProxyExecutable) values -> {
            state.recordFailure(values.length == 0 ? "" : values[0].asString());
            return null;
        });
        bindings.putMember("__workflowPhase", (ProxyExecutable) values -> {
            state.setPhase(values.length == 0 ? null : values[0].asString());
            return null;
        });
        bindings.putMember("__workflowTotal", (ProxyExecutable) _ -> state.budget().total());
        bindings.putMember("__workflowSpent", (ProxyExecutable) _ -> state.budget().spent());
        bindings.putMember("__workflowRemaining", (ProxyExecutable) _ ->
            state.budget().total() == null ? Double.POSITIVE_INFINITY : state.budget().remaining());
        bindings.putMember("__workflowStartAgent", (ProxyExecutable) values -> {
            String prompt = values[0].asString();
            String optionsJson = values[1].asString();
            Value resolve = values[2];
            Value reject = values[3];
            startAgent(state, prompt, optionsJson, resolve, reject);
            return null;
        });
        bindings.putMember("__workflowStartChild", (ProxyExecutable) values -> {
            JsonNode reference = JsonUtils.parseTree(values[0].asString());
            JsonNode childArgs = JsonUtils.parseTree(values[1].asString());
            Value resolve = values[2];
            Value reject = values[3];
            startChildWorkflow(state, nestingDepth, reference, childArgs, resolve, reject);
            return null;
        });
        bindings.putMember("__workflowSetTimeout", (ProxyExecutable) values -> {
            Value callback = values[0];
            long delay = values.length > 1 && values[1].fitsInLong()
                ? Math.max(0, values[1].asLong()) : 0;
            long id = state.nextTimerId.getAndIncrement();
            ScheduledFuture<?> timer = DEADLINE_TIMER.schedule(
                () -> state.completions.offer(JsCompletion.timer(callback)), delay,
                TimeUnit.MILLISECONDS);
            state.timers.put(id, timer);
            return id;
        });
        bindings.putMember("__workflowClearTimeout", (ProxyExecutable) values -> {
            if (values.length > 0 && values[0].fitsInLong()) {
                ScheduledFuture<?> timer = state.timers.remove(values[0].asLong());
                if (timer != null) timer.cancel(false);
            }
            return null;
        });
    }

    private void startChildWorkflow(ExecutionState state, int nestingDepth, JsonNode reference,
                                    JsonNode args, Value resolve, Value reject) {
        if (nestingDepth >= 1) {
            reject.execute("workflow() cannot be called from within a child workflow — nesting is limited to one level. Inline the inner script or call its agents directly.");
            return;
        }
        Path cwd = Path.of(state.parentContext.workingDirectory()).toAbsolutePath().normalize();
        String name = reference.isTextual() ? reference.asText() : null;
        WorkflowDefinition child = name == null ? childFromPath(reference.path("scriptPath").asText(null), cwd)
            : catalog.find(name, cwd).orElse(null);
        if (child == null) {
            reject.execute("Workflow \"" + name + "\" not found");
            return;
        }
        Thread.startVirtualThread(() -> {
            try {
                WorkflowExecutionResult result = execute(child, args, state.parentContext,
                    List.of(), nestingDepth + 1, state.limits, state.journal, state.listener);
                state.completions.offer(new JsCompletion(resolve, reject, true,
                    JsonUtils.toJson(result.value())));
            } catch (RuntimeException error) {
                state.completions.offer(new JsCompletion(resolve, reject, false, messageOf(error)));
            }
        });
    }

    private static WorkflowDefinition childFromPath(String scriptPath, Path cwd) {
        if (StringUtils.isBlank(scriptPath)) return null;
        try {
            Path path = Path.of(scriptPath);
            if (!path.isAbsolute()) path = cwd.resolve(path);
            path = path.toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)
                    || Files.size(path) > WorkflowScriptParser.MAX_SCRIPT_BYTES) return null;
            String script = Files.readString(path);
            ParsedWorkflowScript parsed = WorkflowScriptParser.parse(script);
            return new WorkflowDefinition(parsed.metadata(), script, parsed.body(),
                WorkflowSource.PROJECT, path, null, false, false);
        } catch (Exception _) { return null; }
    }

    private void startAgent(ExecutionState state, String prompt, String optionsJson,
                            Value resolve, Value reject) {
        Long totalBudget = state.budget().total();
        if (totalBudget != null && state.budget().spent() >= totalBudget) {
            reject.execute("Workflow token budget exceeded ("
                + state.budget().spent() + " / " + totalBudget
                + " output tokens). Stopping further agent() calls. In-flight agents will complete; their results are preserved.");
            return;
        }
        int callNumber = state.limits.agentCalls.incrementAndGet();
        if (callNumber > maxAgents) {
            state.limits.agentCalls.decrementAndGet();
            reject.execute("Workflow agent() call cap reached (" + maxAgents
                + "). This usually means a loop using budget.remaining() never terminates because "
                + "no token budget was set — remaining() returns Infinity when budget.total is null. "
                + "Add a hard iteration cap to the loop, or pass a token budget.");
            return;
        }
        WorkflowAgentOptions options = parseOptions(optionsJson);
        if (options.schema() != null && !options.schema().isNull()) {
            SyntheticOutputTool.CreateResult schemaResult =
                SyntheticOutputTool.create(options.schema());
            if (schemaResult instanceof SyntheticOutputTool.CreateResult.Err(String error)) {
                String detail = error.replaceFirst("^Invalid --json-schema: ", "");
                reject.execute("agent({schema}) received an invalid JSON Schema: " + detail);
                return;
            }
        }
        if (Strings.CS.equals("remote", options.isolation())) {
            reject.execute("agent({isolation:'remote'}) is not available in this build");
            return;
        }
        JsonNode optionsNode = JsonUtils.parseTree(StringUtils.isBlank(optionsJson)
            ? "{}" : optionsJson);
        String label = displayLabel(prompt, options.label());
        Integer phaseIndex = options.phase() == null ? null
            : state.resolvePhase(options.phase(), null);
        long queuedAt = System.currentTimeMillis();
        String model = options.model() != null ? options.model()
            : state.parentContext.currentModel();
        JournalCall journalCall = state.nextJournalCall(prompt, optionsJson, optionsNode);
        if (journalCall.cachedOutput() != null) {
            String cachedAgentId = journalCall.cachedAgentId();
            state.emitAgent(callNumber, label, phaseIndex, options.phase(), cachedAgentId,
                model, "done", queuedAt, queuedAt, 1, null, 0, 0, 0,
                null, true, prompt, journalCall.cachedOutput());
            state.completions.offer(new JsCompletion(resolve, reject, true,
                journalCall.cachedOutput()));
            return;
        }
        state.emitAgent(callNumber, label, phaseIndex, options.phase(), null,
            model, "start", null, queuedAt, null, null, 0, 0, 0,
            null, false, prompt, null);
        Thread.startVirtualThread(() -> {
            boolean acquired = false;
            List<String> retryReasons = new ArrayList<>();
            boolean throttleTriggered = false;
            try {
                state.slots.acquire();
                acquired = true;
                checkAborted(state.parentContext);
                for (int attempt = 1; attempt <= MAX_STALL_RETRIES + 2; attempt++) {
                    int currentAttempt = attempt;
                    boolean throttleRetry = throttleTriggered && currentAttempt == 2;
                    String currentLabel = throttleRetry
                        ? label + " (throttle-retry)"
                        : currentAttempt > 1
                            ? label + " (retry " + (currentAttempt - 1) + ")"
                            : label;
                    String agentId = workflowAgentId();
                    AbortController agentAbort = new AbortController();
                    AutoCloseable parentAbort = null;
                    long startedAt = System.currentTimeMillis();
                    long stallMs = options.stallMs() == null
                        ? DEFAULT_STALL_MS : options.stallMs();
                    boolean agentCompletionEmitted = false;
                    try (StallWatchdog watchdog = new StallWatchdog(agentAbort, stallMs)) {
                        AbortController parent = state.parentContext.abortController();
                        if (parent != null) {
                            parentAbort = parent.registerOnAbort(() ->
                                agentAbort.abort(parent.getReason()));
                        }
                        state.listener.onAgentController(agentId, agentAbort);
                        if (state.journal != null && journalCall.key() != null) {
                            state.journal.appendStarted(journalCall.key(), agentId);
                        }
                        state.emitAgent(callNumber, currentLabel, phaseIndex, options.phase(), agentId,
                            model, "start", startedAt, queuedAt, currentAttempt,
                            retryReasons.isEmpty() ? null : retryReasons.getLast(), 0, 0, 0,
                            null, false, prompt, null);
                        watchdog.reset();
                        ToolExecutionContext agentContext =
                            state.parentContext.withAbortController(agentAbort);
                        WorkflowAgentResult result = agentExecutor.execute(
                            new WorkflowAgentRequest(prompt, options, agentContext, agentId,
                                (status, _) -> {
                                    watchdog.reset();
                                    state.emitAgent(callNumber, currentLabel,
                                        phaseIndex, options.phase(), agentId, model, "progress",
                                        startedAt, queuedAt, currentAttempt, null, 0, 0,
                                        System.currentTimeMillis() - startedAt, null, false,
                                        prompt, status);
                                }, state.journal == null
                                        ? null : state.journal.transcriptSubdir()));
                        state.tokens.addAndGet(result.tokensUsed());
                        state.toolUses.addAndGet(result.toolUseCount());
                        state.budget().addOutputTokens(result.outputTokens());
                        String abortReason = agentAbort.getReason();
                        if (Strings.CS.equals("user-skip", abortReason)) {
                            state.emitAgent(callNumber, currentLabel, phaseIndex, options.phase(), agentId,
                                model, "error", startedAt, queuedAt, currentAttempt, null,
                                result.tokensUsed(), result.toolUseCount(), result.durationMs(),
                                "skipped by user", false, prompt, null);
                            state.completions.put(new JsCompletion(resolve, reject, true, null));
                            return;
                        }
                        if (isRetryReason(abortReason)) {
                            if (throttleTriggered) {
                                throw terminalRetryFailure(abortReason, stallMs, 1);
                            }
                            scheduleRetryOrThrow(state, retryReasons, abortReason, currentAttempt,
                                callNumber, label, phaseIndex, options.phase(), agentId, model,
                                startedAt, queuedAt, result.tokensUsed(), result.toolUseCount(),
                                result.durationMs(), stallMs, prompt);
                            continue;
                        }
                        if (agentAbort.isAborted()) {
                            throw new WorkflowRuntimeException("Workflow aborted");
                        }
                        boolean degraded = result.stopReason() == null
                            && !result.structuredOutputPresent()
                            && result.outputTokens() < 50
                            && result.durationMs() > stallMs * 0.5;
                        if (currentAttempt == 1 && degraded) {
                            throttleTriggered = true;
                            state.emitLog("[" + label + "] throttled response (no stop_reason, "
                                + result.outputTokens() + " output tokens in "
                                + Math.round(result.durationMs() / 1000.0)
                                + "s) — sleeping 45s before retry");
                            sleepThrottleDelay(state.parentContext);
                            continue;
                        }
                        if (throttleRetry && degraded) {
                            state.emitLog("[" + label + "] throttle-retry also degraded — "
                                + "giving up on throttle backoff");
                        }
                        if (result.apiError() != null) {
                            String failure = "[" + label + "] failed: " + result.apiError();
                            state.recordFailure(failure);
                            state.addLog(failure);
                            state.emitAgent(callNumber, currentLabel, phaseIndex, options.phase(), agentId,
                                model, "error", startedAt, queuedAt, currentAttempt, null,
                                result.tokensUsed(), result.toolUseCount(), result.durationMs(),
                                result.apiError(), false, prompt, null);
                            state.completions.put(new JsCompletion(resolve, reject, true, null));
                            return;
                        }
                        String output = result.output();
                        // The subagent has completed before workflow-level schema

                        // row as done even if the workflow then rejects a missing
                        // or invalid StructuredOutput payload.
                        state.emitAgent(callNumber, currentLabel, phaseIndex, options.phase(), agentId,
                            model, "done", startedAt, queuedAt, currentAttempt, null,
                            result.tokensUsed(), result.toolUseCount(), result.durationMs(),
                            null, false, prompt,
                            options.schema() != null && !options.schema().isNull()
                                    && !result.structuredOutputPresent() ? null : output);
                        agentCompletionEmitted = true;
                        if (options.schema() != null && !options.schema().isNull()) {
                            if (!result.structuredOutputPresent()) {
                                throw new WorkflowRuntimeException(
                                    "agent({schema}): subagent completed without calling StructuredOutput (after in-conversation nudge)");
                            }
                            JsonNode structured;
                            try {
                                structured = JsonUtils.parseTree(output);
                            } catch (RuntimeException e) {
                                throw new WorkflowRuntimeException(
                                    "Workflow agent returned invalid JSON for structured output", e);
                            }
                            var validation = SchemaValidator.shared()
                                .validateAgainstJsonSchema(structured, options.schema());
                            if (validation.isFailure()) {
                                throw new WorkflowRuntimeException(
                                    "Workflow agent structured output failed schema validation: "
                                        + String.join("; ", validation.errors()));
                            }
                        }
                        state.agentCache.add(new WorkflowAgentCacheEntry(prompt, optionsJson, output));
                        if (state.journal != null && journalCall.key() != null) {
                            state.journal.appendResult(journalCall.key(), agentId, output);
                        }
                        state.completions.put(new JsCompletion(resolve, reject, true, output));
                        return;
                    } catch (Exception e) {
                        String reason = agentAbort.getReason();
                        if (e instanceof WorkflowRuntimeException
                                && currentAttempt > MAX_STALL_RETRIES
                                && isRetryReason(reason)) {
                            throw e;
                        }
                        if (Strings.CS.equals("user-skip", reason)) {
                            state.emitAgent(callNumber, currentLabel, phaseIndex, options.phase(), agentId,
                                model, "error", queuedAt, queuedAt, currentAttempt, null,
                                0, 0, 0, "skipped by user", false, prompt, null);
                            state.completions.put(new JsCompletion(resolve, reject, true, null));
                            return;
                        }
                        if (isRetryReason(reason)) {
                            if (throttleTriggered) {
                                throw terminalRetryFailure(reason, stallMs, 1);
                            }
                            scheduleRetryOrThrow(state, retryReasons, reason, currentAttempt,
                                callNumber, label, phaseIndex, options.phase(), agentId, model,
                                startedAt, queuedAt, 0, 0,
                                System.currentTimeMillis() - startedAt, stallMs, prompt);
                            continue;
                        }
                        if (!agentCompletionEmitted) {
                            state.emitAgent(callNumber, currentLabel, phaseIndex, options.phase(), agentId,
                                model, "error", startedAt, queuedAt, currentAttempt, null,
                                0, 0, 0, messageOf(e), false, prompt, null);
                        }
                        throw e;
                    } finally {
                        state.listener.onAgentController(agentId, null);
                        if (parentAbort != null) {
                            try { parentAbort.close(); } catch (Exception _) {}
                        }
                    }
                }
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                state.completions.offer(new JsCompletion(resolve, reject, false,
                    "Workflow agent interrupted"));
            } catch (Exception e) {
                state.completions.offer(new JsCompletion(resolve, reject, false, messageOf(e)));
            } finally {
                if (acquired) state.slots.release();
            }
        });
    }

    private static boolean isRetryReason(String reason) {
        return Strings.CS.equals("stalled", reason) || Strings.CS.equals("user-retry", reason);
    }

    private void sleepThrottleDelay(ToolExecutionContext context) throws InterruptedException {
        long remaining = throttleRetryDelay.toMillis();
        while (remaining > 0) {
            checkAborted(context);
            long slice = Math.min(remaining, 100L);
            Thread.sleep(slice);
            remaining -= slice;
        }
        checkAborted(context);
    }

    // Both call sites reach this only after the retry throttle fired, i.e. the
    // agent exhausted its single permitted attempt — the count is always 1, so
    // it is inlined rather than taken as a parameter.
    private static WorkflowRuntimeException terminalRetryFailure(String reason,
                                                                  long stallMs,
                                                                  int attempts) {
        if (Strings.CS.equals("user-retry", reason)) {
            return new WorkflowRuntimeException(
                "agent abandoned: user requested retry on all " + attempts + " attempts");
        }
        return new WorkflowRuntimeException("agent stalled on all " + attempts 
            + " attempts (no progress for " + stallMs + "ms each)");
    }

    private static void scheduleRetryOrThrow(
        ExecutionState state, List<String> reasons, String reason, int attempt,
        int agentIndex, String label, Integer phaseIndex, String phaseTitle,
        String agentId, String model, long startedAt, long queuedAt,
        long tokens, int toolCalls, long durationMs, long stallMs, String prompt) {
        reasons.add(reason);
        String userReason = Strings.CS.equals("user-retry", reason)
            ? "retry requested by user" : "stalled — no progress for " + stallMs + "ms";
        state.emitAgent(agentIndex, label, phaseIndex, phaseTitle, agentId,
            model, "error", startedAt, queuedAt, attempt, null,
            tokens, toolCalls, durationMs, userReason, false, prompt, null);
        if (attempt <= MAX_STALL_RETRIES) {
            String logReason = Strings.CS.equals("user-retry", reason)
                ? "retry requested by user" : "stalled (no progress)";
            state.emitLog("[stall] agent \"" + label + "\" " + logReason + " after "
                + Math.round(durationMs / 1000.0) + "s — retrying (" + attempt
                + "/" + MAX_STALL_RETRIES + ")");
            return;
        }
        int attempts = reasons.size();
        if (reasons.stream().allMatch("user-retry"::equals)) {
            throw new WorkflowRuntimeException("agent abandoned: user requested retry on all "
                + attempts + " attempts");
        }
        if (reasons.stream().allMatch("stalled"::equals)) {
            throw new WorkflowRuntimeException("agent stalled on all " + attempts
                + " attempts (no progress for " + stallMs + "ms each)");
        }
        throw new WorkflowRuntimeException("agent abandoned after " + attempts
            + " attempts (" + String.join(" → ", reasons) + ")");
    }

    private static String displayLabel(String prompt, String configured) {
        if (StringUtils.isNotBlank(configured)) {
            return configured.replaceAll("\\s+", " ").trim();
        }
        String compact = prompt == null ? "" : prompt.replaceAll("\\s+", " ").trim();
        return compact.length() <= 60 ? compact : compact.substring(0, 60);
    }

    private static String workflowAgentId() {
        return AgentId.create();
    }

    private WorkflowAgentOptions parseOptions(String json) {
        JsonNode options = JsonUtils.parseTree(StringUtils.isBlank(json) ? "{}" : json);
        JsonNode schema = options.get("schema");
        return new WorkflowAgentOptions(
            text(options, "label"), text(options, "phase"), schema,
            text(options, "model"), text(options, "effort"),
            text(options, "isolation"), text(options, "agentType"),
            options.has("stallMs") && options.get("stallMs").canConvertToLong()
                ? options.get("stallMs").asLong() : null);
    }

    private String bootstrap(JsonNode args, int nestingDepth) {
        String argsExpression = args == null ? "undefined" : JsonUtils.toJson(args);
        return """
            'use strict';
            globalThis.Java = undefined;
            globalThis.Polyglot = undefined;
            globalThis.args = %s;
            const __workflowFormat = value => {
              if (typeof value === 'string') return value;
              if (value === undefined) return 'undefined';
              try { return JSON.stringify(value); }
              catch (_) { return String(value); }
            };
            const __workflowConsole = (prefix, values) =>
              __workflowLog(prefix + values.map(__workflowFormat).join(' '));
            globalThis.console = Object.freeze({
              log: (...values) => __workflowConsole('', values),
              info: (...values) => __workflowConsole('', values),
              debug: (...values) => __workflowConsole('', values),
              warn: (...values) => __workflowConsole('[warn] ', values),
              error: (...values) => __workflowConsole('[error] ', values)
            });
            let __currentPhase = null;
            const __determinismError = %s;
            const __NativeDate = Date;
            __NativeDate.now = () => { throw new Error(__determinismError); };
            globalThis.Date = new Proxy(__NativeDate, {
              construct(target, values) {
                if (values.length === 0) throw new Error(__determinismError);
                return Reflect.construct(target, values);
              }
            });
            Math.random = () => { throw new Error(__determinismError); };
            globalThis.phase = title => {
              __currentPhase = String(title);
              __workflowPhase(__currentPhase);
            };
            globalThis.log = message => __workflowLog(String(message));
            globalThis.agent = (prompt, opts = {}) => {
              const options = {...opts};
              if (!options.phase && __currentPhase) options.phase = __currentPhase;
              return new Promise((resolve, reject) =>
                __workflowStartAgent(String(prompt), JSON.stringify(options), resolve, reject))
                .then(raw => options.schema ? JSON.parse(raw) : raw);
            };
            globalThis.parallel = async thunks => {
              await Promise.resolve();
              if (!Array.isArray(thunks)) throw new TypeError('parallel() expects an array of functions');
              if (thunks.length > %d) throw new Error('array length ' + thunks.length + ' exceeds the maximum of %d supported across the workflow VM boundary');
              for (const thunk of thunks) {
                if (typeof thunk !== 'function') throw new TypeError('parallel() expects an array of functions, not promises. Wrap each call: () => agent(...)');
              }
              const settled = await Promise.allSettled(thunks.map(thunk =>
                Promise.resolve().then(() => thunk())));
              let budgetDrops = 0;
              const results = settled.map((entry, index) => {
                if (entry.status === 'fulfilled') return entry.value;
                const message = entry.reason?.message ?? String(entry.reason);
                if (message.startsWith('Workflow token budget exceeded (')) {
                  budgetDrops++;
                  return null;
                }
                const failure = `parallel[${index}] failed: ${message}`;
                __workflowRecordFailure(failure);
                __workflowLog(failure);
                return null;
              });
              if (budgetDrops > 0) __workflowRecordFailure(`parallel: ${budgetDrops} ${budgetDrops === 1 ? 'slot' : 'slots'} dropped — token budget exceeded`);
              return results;
            };
            globalThis.pipeline = async (items, ...stages) => {
              await Promise.resolve();
              if (!Array.isArray(items)) throw new TypeError('pipeline() expects an array as the first argument');
              if (items.length > %d) throw new Error('array length ' + items.length + ' exceeds the maximum of %d supported across the workflow VM boundary');
              for (const stage of stages) {
                if (typeof stage !== 'function') throw new TypeError('pipeline() stages must be functions: pipeline(items, item => ..., result => ...)');
              }
              const settled = await Promise.allSettled(items.map(async (original, index) => {
                let value = original;
                for (const stage of stages) {
                  if (value === null) break;
                  value = await stage(value, original, index);
                }
                return value;
              }));
              let budgetDrops = 0;
              const results = settled.map((entry, index) => {
                if (entry.status === 'fulfilled') return entry.value;
                const message = entry.reason?.message ?? String(entry.reason);
                if (message.startsWith('Workflow token budget exceeded (')) {
                  budgetDrops++;
                  return null;
                }
                const failure = `pipeline[${index}] failed: ${message}`;
                __workflowRecordFailure(failure);
                __workflowLog(failure);
                return null;
              });
              if (budgetDrops > 0) __workflowRecordFailure(`pipeline: ${budgetDrops} ${budgetDrops === 1 ? 'slot' : 'slots'} dropped — token budget exceeded`);
              return results;
            };
            globalThis.workflow = (nameOrRef, childArgs = undefined) => {
              if (%d >= 1) return Promise.reject(new Error('workflow() cannot be called from within a child workflow — nesting is limited to one level. Inline the inner script or call its agents directly.'));
              return new Promise((resolve, reject) => __workflowStartChild(JSON.stringify(nameOrRef), JSON.stringify(childArgs === undefined ? null : childArgs), resolve, reject))
                .then(raw => JSON.parse(raw));
            };
            globalThis.setTimeout = (callback, delay = 0) => __workflowSetTimeout(callback, Number(delay));
            globalThis.clearTimeout = id => __workflowClearTimeout(Number(id));
            globalThis.budget = Object.freeze({
              total: __workflowTotal(),
              spent: () => __workflowSpent(),
              remaining: () => __workflowRemaining()
            });
            """.formatted(
                argsExpression,
                JsonUtils.toJson(WorkflowDeterminism.ERROR),
                maxCollectionItems, maxCollectionItems,
                maxCollectionItems, maxCollectionItems, nestingDepth);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static void checkAborted(ToolExecutionContext context) {
        AbortController controller = context == null ? null : context.abortController();
        if (controller != null && controller.isAborted()) {
            throw new WorkflowRuntimeException("Workflow was stopped");
        }
    }

    private static String guestError(Value value) {
        if (value == null) return "Workflow failed";
        if (value.isString()) return value.asString();
        return value.toString();
    }

    private static String cleanPolyglotMessage(PolyglotException error) {
        String message = error.getMessage();
        return StringUtils.isBlank(message) ? "Invalid workflow script" : message;
    }

    private static String messageOf(Throwable error) {
        if (error == null) return "Workflow failed";
        String message = error.getMessage();
        return StringUtils.isBlank(message) ? error.getClass().getSimpleName() : message;
    }

    private static int defaultConcurrency() {
        int cpus = Runtime.getRuntime().availableProcessors();
        return Math.max(2, Math.min(16, cpus - 2));
    }

    private static final class ExecutionState {
        private final ToolExecutionContext parentContext;
        private final SharedLimits limits;
        private final Semaphore slots;
        private final TurnTokenBudget turnTokenBudget;
        private final WorkflowJournal journal;
        private final Listener listener;
        private final LinkedBlockingQueue<JsCompletion> completions = new LinkedBlockingQueue<>();
        private final AtomicLong tokens;
        private final AtomicInteger toolUses;
        private final List<String> logs;
        private final List<String> failures;
        private final AtomicReference<String> phase;
        private final List<WorkflowAgentCacheEntry> resumeCache;
        private final List<WorkflowAgentCacheEntry> agentCache;
        private int resumeIndex;
        private boolean resumePrefix = true;
        private final AtomicLong nextTimerId = new AtomicLong(1);
        private final ConcurrentHashMap<Long, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();

        private ExecutionState(ToolExecutionContext parentContext, SharedLimits limits,
                               List<WorkflowAgentCacheEntry> resumeCache,
                               WorkflowJournal journal, Listener listener) {
            this.parentContext = parentContext;
            this.limits = limits;
            this.slots = limits.slots;
            this.turnTokenBudget = parentContext == null || parentContext.turnTokenBudget() == null
                ? TurnTokenBudget.unlimited() : parentContext.turnTokenBudget();
            this.journal = journal;
            this.listener = listener == null ? Listener.NOOP : listener;
            this.tokens = limits.tokens;
            this.toolUses = limits.toolUses;
            this.logs = limits.logs;
            this.failures = limits.failures;
            this.phase = limits.phase;
            this.agentCache = limits.agentCache;
            this.resumeCache = List.copyOf(resumeCache == null ? List.of() : resumeCache);
            limits.initializeJournal(journal);
        }

        private TurnTokenBudget budget() {
            return turnTokenBudget;
        }

        private void addLog(String message) {
            String safe = message == null ? "" : message;
            logs.add(safe);
            emitLog(safe);
        }

        private void emitLog(String message) {
            String safe = message == null ? "" : message;
            ObjectNode event = JsonUtils.getMapper().createObjectNode();
            event.put("type", "workflow_log");
            event.put("message", safe);
            listener.onProgress(event);
        }

        private void recordFailure(String message) {
            String safe = message == null ? "" : message;
            failures.add(safe);
            listener.onFailure(safe);
        }

        private void setPhase(String title) {
            if (StringUtils.isBlank(title)) return;
            phase.set(title);
            resolvePhase(title, null);
        }

        private Integer resolvePhase(String title, String kind) {
            if (StringUtils.isBlank(title)) return null;
            synchronized (limits.phaseIndices) {
                Integer existing = limits.phaseIndices.get(title);
                if (existing != null) return existing;
                int index = limits.nextPhaseIndex.incrementAndGet();
                limits.phaseIndices.put(title, index);
                ObjectNode event = JsonUtils.getMapper().createObjectNode();
                event.put("type", "workflow_phase");
                event.put("index", index);
                event.put("title", title);
                if (kind != null) event.put("kind", kind);
                listener.onProgress(event);
                return index;
            }
        }

        private JournalCall nextJournalCall(String prompt, String optionsJson, JsonNode options) {
            synchronized (limits.journalLock) {
                String key = WorkflowJournal.key(limits.previousJournalKey, prompt, options);
                limits.previousJournalKey = key;
                if (journal != null) {
                    WorkflowJournal.ResultEntry cached = limits.journalPrefix
                        ? limits.journalSnapshot.results().get(key) : null;
                    if (cached == null) {
                        limits.journalPrefix = false;
                        return new JournalCall(key, null, null);
                    }
                    WorkflowAgentCacheEntry entry = new WorkflowAgentCacheEntry(
                        prompt, optionsJson, cached.result());
                    agentCache.add(entry);
                    return new JournalCall(key, cached.agentId(), cached.result());
                }
                WorkflowAgentCacheEntry cached = nextCached(prompt, optionsJson);
                return cached == null
                    ? new JournalCall(null, null, null)
                    : new JournalCall(null, null, cached.output());
            }
        }

        private void emitAgent(int index, String label, Integer phaseIndex,
                               String phaseTitle, String agentId, String model,
                               String state, Long startedAt, long queuedAt,
                               Integer attempt, String lastAttemptReason,
                               long tokenCount, int toolCallCount, long durationMs,
                               String error, boolean cached, String prompt,
                               String resultOrProgress) {
            ObjectNode event = JsonUtils.getMapper().createObjectNode();
            event.put("type", "workflow_agent");
            event.put("index", index);
            event.put("label", label == null ? "" : label);
            if (phaseIndex != null) event.put("phaseIndex", phaseIndex);
            if (phaseTitle != null) event.put("phaseTitle", phaseTitle);
            if (agentId != null) event.put("agentId", agentId);
            if (model != null) event.put("model", model);
            event.put("state", state);
            if (startedAt != null) event.put("startedAt", startedAt);
            event.put("queuedAt", queuedAt);
            if (attempt != null) event.put("attempt", attempt);
            if (lastAttemptReason != null) event.put("lastAttemptReason", lastAttemptReason);
            event.put("promptPreview", preview(prompt));
            event.put("lastProgressAt", System.currentTimeMillis());
            if (tokenCount > 0 || Strings.CS.equals("done", state) || Strings.CS.equals("error", state)) {
                event.put("tokens", tokenCount);
                event.put("toolCalls", toolCallCount);
                event.put("durationMs", durationMs);
            }
            if (error != null) event.put("error", error);
            if (cached) event.put("cached", true);
            if (Strings.CS.equals("done", state) && resultOrProgress != null) {
                event.put("resultPreview", preview(resultOrProgress));
            } else if (Strings.CS.equals("progress", state) && resultOrProgress != null) {
                event.put("lastToolSummary", resultOrProgress);
            }
            listener.onProgress(event);
        }

        private synchronized WorkflowAgentCacheEntry nextCached(String prompt, String optionsJson) {
            if (!resumePrefix || resumeIndex >= resumeCache.size()) {
                resumePrefix = false;
                return null;
            }
            WorkflowAgentCacheEntry candidate = resumeCache.get(resumeIndex++);
            if (!candidate.matches(prompt, optionsJson)) {
                resumePrefix = false;
                return null;
            }
            agentCache.add(candidate);
            return candidate;
        }

        private void cancelTimers() {
            timers.values().forEach(timer -> timer.cancel(false));
            timers.clear();
        }

        private static String preview(String value) {
            if (value == null) return "";
            String trimmed = value.trim();
            return trimmed.length() <= 400 ? trimmed : trimmed.substring(0, 400) + "…";
        }
    }

/**
     * Parent and one child share these.
     */
    private static final class SharedLimits {
        private final Semaphore slots;
        private final AtomicInteger agentCalls = new AtomicInteger();
        private final AtomicLong tokens = new AtomicLong();
        private final AtomicInteger toolUses = new AtomicInteger();
        private final List<String> logs = Collections.synchronizedList(new ArrayList<>());
        private final List<String> failures = Collections.synchronizedList(new ArrayList<>());
        private final AtomicReference<String> phase = new AtomicReference<>();
        private final List<WorkflowAgentCacheEntry> agentCache =
            Collections.synchronizedList(new ArrayList<>());
        private final Map<String, Integer> phaseIndices = new LinkedHashMap<>();
        private final AtomicInteger nextPhaseIndex = new AtomicInteger();
        private final Object journalLock = new Object();
        private WorkflowJournal.Snapshot journalSnapshot = WorkflowJournal.Snapshot.empty();
        private boolean journalInitialized;
        private boolean journalPrefix = true;
        private String previousJournalKey = "";

        private SharedLimits(int concurrency) { this.slots = new Semaphore(concurrency); }

        private void initializeJournal(WorkflowJournal journal) {
            synchronized (journalLock) {
                if (journalInitialized) return;
                journalSnapshot = journal == null
                    ? WorkflowJournal.Snapshot.empty() : journal.load();
                journalInitialized = true;
            }
        }
    }

    private record JournalCall(String key, String cachedAgentId, String cachedOutput) {}


    private static final class StallWatchdog implements AutoCloseable {
        private final AbortController controller;
        private final long stallMs;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<ScheduledFuture<?>> timer = new AtomicReference<>();

        private StallWatchdog(AbortController controller, long stallMs) {
            this.controller = controller;
            this.stallMs = stallMs;
        }

        private void reset() {
            if (stallMs <= 0 || closed.get()) return;
            ScheduledFuture<?> next = DEADLINE_TIMER.schedule(() -> {
                if (!closed.get()) controller.abort("stalled");
            }, stallMs, TimeUnit.MILLISECONDS);
            ScheduledFuture<?> previous = timer.getAndSet(next);
            if (previous != null) previous.cancel(false);
        }

        @Override
        public void close() {
            closed.set(true);
            ScheduledFuture<?> previous = timer.getAndSet(null);
            if (previous != null) previous.cancel(false);
        }
    }

    /** Guest callbacks are delivered only by the context-owning thread. */
    private record JsCompletion(Value resolve, Value reject, boolean success, Object payload) {
        private static JsCompletion timer(Value callback) {
            return new JsCompletion(callback, null, true, null);
        }
        private void deliver() {
            (success ? resolve : reject).execute(payload);
        }
    }
}

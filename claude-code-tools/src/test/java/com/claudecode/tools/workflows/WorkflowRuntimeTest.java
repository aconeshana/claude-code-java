package com.claudecode.tools.workflows;

import java.util.Locale;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.TurnTokenBudget;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.tools.agent.SubAgentFactory;
import com.claudecode.tools.agent.SubAgentResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowRuntimeTest {

    @TempDir Path temp;

    @Test
    void executesAsyncAgentParallelPipelineAndReturnsJsonValue() {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        WorkflowAgentExecutor executor = request -> {
            int now = active.incrementAndGet();
            maxActive.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(80);
                return new WorkflowAgentResult(request.prompt().toUpperCase(Locale.ROOT), 10, 1, 80);
            } finally {
                active.decrementAndGet();
            }
        };
        WorkflowRuntime runtime = runtime(executor, 4);
        WorkflowDefinition definition = definition("parallel-test", """
            const first = await parallel(['a', 'b', 'c'].map(x => () => agent(x)));
            const piped = await pipeline([1, 2],
              x => agent(`p${x}`),
              (previous, original, index) => agent(`${previous}:${original}:${index}`));
            return {first, piped};
            """);

        WorkflowExecutionResult result = runtime.execute(definition, null, context());

        assertEquals(List.of("A", "B", "C"),
            JsonUtils.getMapper().convertValue(result.value().get("first"), List.class));
        assertEquals("P1:1:0", result.value().get("piped").get(0).asText());
        assertEquals("P2:2:1", result.value().get("piped").get(1).asText());
        assertTrue(maxActive.get() >= 2, "parallel() should overlap agent calls");
        assertEquals(7, result.agentCalls());
        assertEquals(70, result.tokensUsed());
    }

    @Test
    void passesAgentOptionsAndParsesStructuredOutput() {
        WorkflowAgentExecutor executor = request -> {
            assertEquals("haiku", request.options().model());
            assertEquals("low", request.options().effort());
            assertEquals("worktree", request.options().isolation());
            assertEquals("Explore", request.options().agentType());
            assertEquals("scan", request.options().label());
            assertEquals("Inspect", request.options().phase());
            assertTrue(request.options().schema().isObject());
            return WorkflowAgentResult.of("{\"ok\":true}", 4, 0, 1, 4,
                "end_turn", true);
        };
        WorkflowDefinition definition = definition("options", """
            return await agent('inspect', {
              model: 'haiku', effort: 'low', isolation: 'worktree',
              agentType: 'Explore', label: 'scan', phase: 'Inspect',
              schema: {type: 'object', properties: {ok: {type: 'boolean'}}}
            });
            """);

        WorkflowExecutionResult result = runtime(executor, 2).execute(definition, null, context());

        assertTrue(result.value().get("ok").asBoolean());
    }

    @Test
    void queuedProgressDoesNotPublishStartedAtBeforeAnAgentSlotIsAcquired() {
        List<JsonNode> progress = new CopyOnWriteArrayList<>();
        WorkflowRuntime runtime = runtime(_ -> WorkflowAgentResult.of("ok"), 1);

        runtime.execute(definition("queued-state", "return await agent('inspect');"),
            null, context(), List.of(), null, new WorkflowRuntime.Listener() {
                @Override public void onProgress(JsonNode event) {
                    if (Strings.CS.equals("workflow_agent", event.path("type").asText())) {
                        progress.add(event.deepCopy());
                    }
                }
            });

        JsonNode queued = progress.getFirst();
        assertTrue(queued.hasNonNull("queuedAt"));
        assertFalse(queued.has("startedAt"));
        assertFalse(queued.has("agentId"));
        assertTrue(progress.stream().skip(1).anyMatch(event -> event.hasNonNull("startedAt")
            && event.hasNonNull("agentId")));
    }

    @Test
    void exposesArgsLogsPhasesAndNoHostOrNodeApis() {
        WorkflowDefinition definition = definition("globals", """
            phase('Inspect');
            log(`target:${args.target}`);
            return {
              target: args.target,
              java: typeof Java,
              polyglot: typeof Polyglot,
              require: typeof require,
              process: typeof process,
              fetch: typeof fetch,
              URL: typeof URL,
              URLSearchParams: typeof URLSearchParams,
              TextEncoder: typeof TextEncoder,
              Buffer: typeof Buffer,
              phase: typeof phase,
              log: typeof log,
              agent: typeof agent,
              parallel: typeof parallel,
              pipeline: typeof pipeline,
              workflow: typeof workflow,
              setTimeout: typeof setTimeout,
              clearTimeout: typeof clearTimeout,
              budget: typeof budget,
              argsType: typeof args,
              console: typeof console
            };
            """);

        WorkflowExecutionResult result = runtime(_ -> WorkflowAgentResult.of("unused"), 2)
            .execute(definition, JsonUtils.parseTree("{\"target\":\"src\"}"), context());

        assertEquals("src", result.value().get("target").asText());
        assertEquals("undefined", result.value().get("java").asText());
        assertEquals("undefined", result.value().get("polyglot").asText());
        assertEquals("undefined", result.value().get("require").asText());
        assertEquals("undefined", result.value().get("process").asText());
        assertEquals("undefined", result.value().get("fetch").asText());
        assertEquals("undefined", result.value().get("URL").asText());
        assertEquals("undefined", result.value().get("URLSearchParams").asText());
        assertEquals("undefined", result.value().get("TextEncoder").asText());
        assertEquals("undefined", result.value().get("Buffer").asText());
        for (String function : List.of("phase", "log", "agent", "parallel", "pipeline",
                "workflow", "setTimeout", "clearTimeout")) {
            assertEquals("function", result.value().get(function).asText(), function);
        }
        assertEquals("object", result.value().get("budget").asText());
        assertEquals("object", result.value().get("argsType").asText());
        assertEquals("object", result.value().get("console").asText());
        assertEquals(List.of("target:src"), result.logs());
        assertEquals("Inspect", result.lastPhase());
    }

    @Test
    void doesNotInventAUrlGlobalMissingFromReleased197() {
        WorkflowRuntimeException error = assertThrows(WorkflowRuntimeException.class,
            () -> runtime(_ -> WorkflowAgentResult.of("unused"), 2)
                .execute(definition("url", """
                    return new URL('https://www.example.com/path/').hostname;
                    """), null, context()));

        assertTrue(Strings.CS.contains(error.getMessage(), "URL is not defined"), error.getMessage());
    }

    @Test
    void supportsTimersWithoutExposingHostSchedulingApis() {
        WorkflowExecutionResult result = runtime(_ -> WorkflowAgentResult.of("unused"), 2)
            .execute(definition("timer", """
                let fired = false;
                await new Promise(resolve => setTimeout(() => { fired = true; resolve(); }, 1));
                return fired;
                """), null, context());

        assertTrue(result.value().asBoolean());
    }

    @Test
    void rejectsNondeterminismBeforeSpawningAgents() {
        AtomicInteger calls = new AtomicInteger();
        WorkflowRuntime runtime = runtime(_ -> {
            calls.incrementAndGet();
            return WorkflowAgentResult.of("x");
        }, 2);

        for (String body : List.of(
                "return Date.now();",
                "return Math.random();",
                "return new Date();")) {
            WorkflowRuntimeException error = assertThrows(WorkflowRuntimeException.class,
                () -> runtime.execute(definition("bad", body), null, context()));
            assertTrue(Strings.CS.contains(error.getMessage(), "Workflow scripts must be deterministic"));
        }
        assertEquals(0, calls.get());
    }

    @Test
    void validatesSyntaxWithoutExecutingTheWorkflowBody() {
        WorkflowRuntime runtime = runtime(_ -> WorkflowAgentResult.of("unused"), 2);

        assertTrue(runtime.validate(definition("valid", "return {ok: true};")).isEmpty());
        assertTrue(Strings.CS.contains(runtime.validate(definition("invalid", "const = ;")).orElseThrow(), "SyntaxError"));
    }

    @Test
    void resumesOnlyTheUnchangedPrefixOfAgentCalls() {
        AtomicInteger liveCalls = new AtomicInteger();
        WorkflowRuntime runtime = runtime(_ ->
            new WorkflowAgentResult("live:" + liveCalls.incrementAndGet(), 10, 1, 1), 2);
        WorkflowDefinition definition = definition("resume", """
            const first = await agent('first');
            const second = await agent('second');
            return {first, second};
            """);
        List<WorkflowAgentCacheEntry> cache = List.of(
            new WorkflowAgentCacheEntry("first", "{}", "cached:first"),
            new WorkflowAgentCacheEntry("changed", "{}", "cached:changed"));

        WorkflowExecutionResult result = runtime.execute(definition, null, context(), cache);

        assertEquals("cached:first", result.value().path("first").asText());
        assertEquals("live:1", result.value().path("second").asText());
        assertEquals(1, liveCalls.get());
        assertEquals(2, result.agentCache().size());
        assertEquals("second", result.agentCache().get(1).prompt());
    }

    @Test
    void interruptsNonYieldingScriptsAtTheReleasedVmDeadline() {
        WorkflowRuntime runtime = new WorkflowRuntime(_ -> WorkflowAgentResult.of("unused"),
            new WorkflowCatalog(temp.resolve("user"), List.of(), List::of), 2,
            WorkflowRuntime.DEFAULT_MAX_AGENTS, WorkflowRuntime.DEFAULT_MAX_COLLECTION_ITEMS,
            Duration.ofMillis(100));

        WorkflowRuntimeException error = assertThrows(WorkflowRuntimeException.class,
            () -> runtime.execute(definition("loop", "while (true) {}"), null, context()));

        assertEquals("Workflow script timed out after 30000ms", error.getMessage());
    }

    @Test
    void syncDeadlineDoesNotCancelLongRunningAsyncAgentWork() {
        WorkflowRuntime runtime = new WorkflowRuntime(_ -> {
            try {
                Thread.sleep(700);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            return WorkflowAgentResult.of("done");
        }, new WorkflowCatalog(temp.resolve("user"), List.of(), List::of), 2,
            WorkflowRuntime.DEFAULT_MAX_AGENTS, WorkflowRuntime.DEFAULT_MAX_COLLECTION_ITEMS,
            Duration.ofMillis(500));

        WorkflowExecutionResult result = runtime.execute(
            definition("async-wait", "return await agent('slow');"), null, context());

        assertEquals("done", result.value().asText());
    }

    @Test
    void exposesSharedTurnBudgetAndStopsFutureAgentCallsAtTheCeiling() {
        AtomicInteger calls = new AtomicInteger();
        WorkflowRuntime runtime = runtime(_ -> {
            calls.incrementAndGet();
            return WorkflowAgentResult.of("ok", 25, 0, 1, 6);
        }, 2);
        TurnTokenBudget budget = new TurnTokenBudget(10L);
        budget.addOutputTokens(2L);
        ToolExecutionContext context = context().withTurnTokenBudget(budget);

        WorkflowExecutionResult observed = runtime.execute(definition("budget-values", """
            const before = {total: budget.total, spent: budget.spent(), remaining: budget.remaining()};
            await agent('one');
            return {before, after: {spent: budget.spent(), remaining: budget.remaining()}};
            """), null, context);

        assertEquals(10, observed.value().path("before").path("total").asInt());
        assertEquals(2, observed.value().path("before").path("spent").asInt());
        assertEquals(8, observed.value().path("after").path("spent").asInt());
        assertEquals(2, observed.value().path("after").path("remaining").asInt());

        WorkflowRuntimeException exceeded = assertThrows(WorkflowRuntimeException.class,
            () -> runtime.execute(definition("budget-cap", """
                await agent('one');
                await agent('two');
                return 'never';
                """), null, context));
        assertTrue(Strings.CS.contains(exceeded.getMessage(), "Workflow token budget exceeded"));
        assertEquals(2, calls.get(), "the over-budget second agent must never start");
    }

    @Test
    void redirectsConsoleAndMapsTerminalAgentApiErrorsToNull() {
        WorkflowRuntime runtime = runtime(_ ->
            WorkflowAgentResult.apiError("rate limited", 3, 0, 4, 2), 2);

        WorkflowExecutionResult result = runtime.execute(definition("console", """
            console.log('hello', {ok: true});
            console.warn('careful');
            return await agent('fails');
            """), null, context());

        assertTrue(result.value().isNull());
        assertEquals(List.of("hello {\"ok\":true}", "[warn] careful", "[fails] failed: rate limited"),
            result.logs());
        assertEquals(List.of("[fails] failed: rate limited"), result.failures());
    }

    @Test
    void matchesReleasedParallelAndPipelineValidationAndFailureLogging() {
        WorkflowRuntime runtime = runtime(request -> {
            if (Strings.CS.contains(request.prompt(), "bad")) throw new IllegalStateException("boom");
            return WorkflowAgentResult.of(request.prompt());
        }, 2);

        WorkflowRuntimeException promises = assertThrows(WorkflowRuntimeException.class,
            () -> runtime.execute(definition("parallel-promises", """
                return await parallel([agent('already-started')]);
                """), null, context()));
        assertEquals("parallel() expects an array of functions, not promises. "
            + "Wrap each call: () => agent(...)", promises.getMessage());

        WorkflowRuntimeException stages = assertThrows(WorkflowRuntimeException.class,
            () -> runtime.execute(definition("pipeline-stages", """
                return await pipeline([1], 'not-a-function');
                """), null, context()));
        assertEquals("pipeline() stages must be functions: "
            + "pipeline(items, item => ..., result => ...)", stages.getMessage());

        WorkflowExecutionResult result = runtime.execute(definition("slot-errors", """
            const parallelResult = await parallel([
              () => agent('good'),
              () => agent('bad-parallel')
            ]);
            const pipelineResult = await pipeline(['good', 'bad-pipeline'],
              item => agent(item));
            return {parallelResult, pipelineResult};
            """), null, context());

        assertTrue(result.value().path("parallelResult").get(1).isNull());
        assertTrue(result.value().path("pipelineResult").get(1).isNull());
        assertEquals(List.of(
            "parallel[1] failed: boom",
            "pipeline[1] failed: boom"), result.logs());
        assertEquals(List.of(
            "parallel[1] failed: boom",
            "pipeline[1] failed: boom"), result.failures());
    }

    @Test
    void rejectsUnavailableRemoteIsolationAndUsesCanonicalAgentIds() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> agentId = new AtomicReference<>();
        WorkflowRuntime runtime = runtime(request -> {
            calls.incrementAndGet();
            agentId.set(request.agentId());
            return WorkflowAgentResult.of("ok");
        }, 2);

        WorkflowRuntimeException remote = assertThrows(WorkflowRuntimeException.class,
            () -> runtime.execute(definition("remote", """
                return await agent('cloud', {isolation: 'remote'});
                """), null, context()));
        assertEquals("agent({isolation:'remote'}) is not available in this build",
            remote.getMessage());
        assertEquals(0, calls.get());

        runtime.execute(definition("id", "return await agent('local');"), null, context());
        assertTrue(agentId.get().matches("a[0-9a-f]{16}"), agentId.get());
    }

    @Test
    void abortsStalledAgentsAndRetriesFiveTimesLikeReleasedRuntime() {
        AtomicInteger calls = new AtomicInteger();
        WorkflowRuntime runtime = runtime(request -> {
            calls.incrementAndGet();
            while (!request.parentContext().abortController().isAborted()) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted", e);
                }
            }
            throw new IllegalStateException("aborted");
        }, 1);

        WorkflowRuntimeException stalled = assertThrows(WorkflowRuntimeException.class,
            () -> runtime.execute(definition("stall", """
                return await agent('slow', {stallMs: 10});
                """), null, context()));

        assertEquals("agent stalled on all 6 attempts (no progress for 10ms each)",
            stalled.getMessage());
        assertEquals(6, calls.get());
    }

    @Test
    void stallRetryAbortsTheRealSubAgentSessionController() {
        AtomicInteger calls = new AtomicInteger();
        SubAgentFactory factory = request -> {
            calls.incrementAndGet();
            if (request.abortController() == null) {
                return SubAgentResult.error("workflow controller was not forwarded");
            }
            while (!request.abortController().isAborted()) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    return SubAgentResult.error("interrupted");
                }
            }
            return SubAgentResult.error("aborted");
        };
        WorkflowRuntime runtime = runtime(new SubAgentWorkflowExecutor(factory), 1);

        WorkflowRuntimeException stalled = assertThrows(WorkflowRuntimeException.class,
            () -> runtime.execute(definition("real-stall", """
                return await agent('slow', {stallMs: 10});
                """), null, context()));

        assertEquals("agent stalled on all 6 attempts (no progress for 10ms each)",
            stalled.getMessage());
        assertEquals(6, calls.get());
    }

    @Test
    void retriesOneDegradedThrottleResponseAfterTheReleasedBackoff() {
        AtomicInteger calls = new AtomicInteger();
        List<JsonNode> progress = new CopyOnWriteArrayList<>();
        WorkflowRuntime runtime = new WorkflowRuntime(_ -> {
            int call = calls.incrementAndGet();
            return WorkflowAgentResult.of(call == 1 ? "partial" : "complete",
                7, 0, 1_000, 12, call == 1 ? null : "end_turn", false);
        }, new WorkflowCatalog(temp.resolve("user"), List.of(), List::of), 1,
            WorkflowRuntime.DEFAULT_MAX_AGENTS, WorkflowRuntime.DEFAULT_MAX_COLLECTION_ITEMS,
            Duration.ofSeconds(30), Duration.ZERO);

        WorkflowExecutionResult result = runtime.execute(
            definition("throttle", "return await agent('slow', {stallMs: 1000});"),
            null, context(), List.of(), null, new WorkflowRuntime.Listener() {
                @Override public void onProgress(JsonNode item) { progress.add(item); }
            });

        assertEquals("complete", result.value().asText());
        assertEquals(2, calls.get());
        assertTrue(progress.stream().anyMatch(item -> Strings.CS.equals(item.path("message").asText(),
            "[slow] throttled response (no stop_reason, 12 output tokens in 1s) — "
                + "sleeping 45s before retry")));
        assertTrue(progress.stream().anyMatch(item ->
            Strings.CS.equals("slow (throttle-retry)", item.path("label").asText())));
    }

    @Test
    void reportsSecondDegradedThrottleResponseAndUsesItsOutput() {
        AtomicInteger calls = new AtomicInteger();
        List<JsonNode> progress = new CopyOnWriteArrayList<>();
        WorkflowRuntime runtime = new WorkflowRuntime(_ ->
            WorkflowAgentResult.of("attempt-" + calls.incrementAndGet(), 1, 0,
                1_000, 2, null, false),
            new WorkflowCatalog(temp.resolve("user"), List.of(), List::of), 1,
            WorkflowRuntime.DEFAULT_MAX_AGENTS, WorkflowRuntime.DEFAULT_MAX_COLLECTION_ITEMS,
            Duration.ofSeconds(30), Duration.ZERO);

        WorkflowExecutionResult result = runtime.execute(
            definition("throttle-twice", "return await agent('slow', {stallMs: 1000});"),
            null, context(), List.of(), null, new WorkflowRuntime.Listener() {
                @Override public void onProgress(JsonNode item) { progress.add(item); }
            });

        assertEquals("attempt-2", result.value().asText());
        assertEquals(2, calls.get());
        assertTrue(progress.stream().anyMatch(item -> Strings.CS.equals(item.path("message").asText(), "[slow] throttle-retry also degraded — giving up on throttle backoff")));
    }

    @Test
    void schemaRequiresARealStructuredOutputCall() {
        WorkflowRuntime missing = runtime(_ -> WorkflowAgentResult.of(
            "{\"ok\":true}", 1, 0, 1, 1, "end_turn", false), 1);
        List<JsonNode> missingProgress = new CopyOnWriteArrayList<>();

        WorkflowRuntimeException error = assertThrows(WorkflowRuntimeException.class,
            () -> missing.execute(definition("missing-structured", """
                    return await agent('structured', {
                      schema: {type: 'object', properties: {ok: {type: 'boolean'}}}
                    });
                    """), null, context(), List.of(), null,
                new WorkflowRuntime.Listener() {
                    @Override public void onProgress(JsonNode item) {
                        missingProgress.add(item);
                    }
                }));
        assertEquals("agent({schema}): subagent completed without calling StructuredOutput "
            + "(after in-conversation nudge)", error.getMessage());
        JsonNode finalAgent = missingProgress.stream()
            .filter(item -> Strings.CS.equals("workflow_agent", item.path("type").asText()))
            .reduce((_, second) -> second).orElseThrow();
        assertEquals("done", finalAgent.path("state").asText());
        assertEquals(1, finalAgent.path("tokens").asInt());
        assertFalse(finalAgent.has("resultPreview"));

        WorkflowRuntime present = runtime(_ -> WorkflowAgentResult.of(
            "{\"ok\":true}", 1, 0, 1, 1, "end_turn", true), 1);
        assertTrue(present.execute(definition("real-structured", """
            return await agent('structured', {
              schema: {type: 'object', properties: {ok: {type: 'boolean'}}}
            });
            """), null, context()).value().path("ok").asBoolean());

        WorkflowRuntimeException invalid = assertThrows(WorkflowRuntimeException.class,
            () -> present.execute(definition("invalid-schema", """
                return await agent('structured', {schema: {type: 'wat'}});
                """), null, context()));
        assertTrue(Strings.CS.startsWith(invalid.getMessage(), "agent({schema}) received an invalid JSON Schema: $.type: invalid type value"));
    }

    @Test
    void invokesOneNamedChildWorkflow() {
        WorkflowDefinition child = definition("child", "return {answer: args.value};");
        WorkflowCatalog catalog = new WorkflowCatalog(temp.resolve("user"), List.of(child), List::of);
        WorkflowRuntime runtime = new WorkflowRuntime(_ -> WorkflowAgentResult.of("unused"), catalog, 2);

        WorkflowExecutionResult result = runtime.execute(definition("parent", """
            return await workflow('child', {value: 42});
            """), null, context());

        assertEquals(42, result.value().path("answer").asInt());
    }

    @Test
    void enforcesAgentAndCollectionCaps() {
        WorkflowRuntime runtime = new WorkflowRuntime(
            request -> WorkflowAgentResult.of(request.prompt()),
            new WorkflowCatalog(temp.resolve("user"), List.of(), List::of),
            2, 2, 3);

        WorkflowRuntimeException tooManyAgents = assertThrows(WorkflowRuntimeException.class,
            () -> runtime.execute(definition("cap", """
                await agent('1'); await agent('2'); await agent('3'); return 'never';
                """), null, context()));
        assertEquals("Workflow agent() call cap reached (2). This usually means a loop using "
            + "budget.remaining() never terminates because no token budget was set — "
            + "remaining() returns Infinity when budget.total is null. Add a hard iteration "
            + "cap to the loop, or pass a token budget.", tooManyAgents.getMessage());

        WorkflowRuntimeException tooManyItems = assertThrows(WorkflowRuntimeException.class,
            () -> runtime.execute(definition("items", """
                return await parallel([() => 1, () => 2, () => 3, () => 4]);
                """), null, context()));
        assertEquals("array length 4 exceeds the maximum of 3 supported across the "
            + "workflow VM boundary", tooManyItems.getMessage());
    }

    private WorkflowRuntime runtime(WorkflowAgentExecutor executor, int concurrency) {
        return new WorkflowRuntime(executor,
            new WorkflowCatalog(temp.resolve("user"), List.of(), List::of), concurrency);
    }

    private static ToolExecutionContext context() {
        return ToolExecutionContext.builder(new AbortController(), "session").workingDirectory(".").build();
    }

    private static WorkflowDefinition definition(String name, String body) {
        String script = "export const meta = { name: \"" + name
            + "\", description: \"test\" };\n" + body;
        ParsedWorkflowScript parsed = WorkflowScriptParser.parse(script);
        return new WorkflowDefinition(parsed.metadata(), script, parsed.body(),
            WorkflowSource.USER, null, null, false, true);
    }
}

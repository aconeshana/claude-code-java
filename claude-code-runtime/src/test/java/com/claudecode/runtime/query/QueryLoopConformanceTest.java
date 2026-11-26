package com.claudecode.runtime.query;

import com.claudecode.core.attachment.FeatureFlag;
import com.claudecode.core.attachment.FeatureFlagRegistry;
import com.claudecode.core.engine.HookDispatcher;
import com.claudecode.core.engine.MessageCompactor;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.message.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Conformance test suite bridging the JVM {@link QueryLoop} runtime and the
 * Lean 4 formal specification in {@code formal/AgentLoop/Step.lean}.
 *
 * <p>Drives the real {@link QueryLoop} through adversarial control-flow scenarios
 * (tool-use maxTurns, max_output_tokens escalation + recovery exhaustion, stop-hook
 * re-entry and prevention, mid-stream abort, budget exhaustion, blocking-limit guard,
 * and single-shot reactive compaction), writes the observed transitions to JSONL, and
 * validates every transition against the Lean 4 {@code trace-verifier} executable.
 */
class QueryLoopConformanceTest {

    private static final Logger log = LoggerFactory.getLogger(QueryLoopConformanceTest.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final class RecordingTraceObserver implements LoopTraceObserver {
        private final String scenario;
        private final List<String> jsonLines;
        private final List<OutcomeSnapshot> outcomes = new CopyOnWriteArrayList<>();

        RecordingTraceObserver(String scenario, List<String> jsonLines) {
            this.scenario = scenario;
            this.jsonLines = jsonLines;
        }

        @Override
        public void onTransition(ConfigSnapshot config,
                                 StateSnapshot before,
                                 EventSnapshot event,
                                 OutcomeSnapshot actual) {
            outcomes.add(actual);
            ObjectNode row = MAPPER.createObjectNode();
            row.put("scenario", scenario);
            row.set("config", config.toJson(MAPPER));
            row.set("before", before.toJson(MAPPER));
            row.set("event", event.toJson(MAPPER));
            row.set("actual", actual.toJson(MAPPER));
            jsonLines.add(row.toString());
        }

        List<OutcomeSnapshot> outcomes() {
            return outcomes;
        }
    }

    @Test
    void queryLoopTransitionsRefineLean4Specification() throws Exception {
        List<String> allJsonLines = new ArrayList<>();

        runSingleTurnNormalEnd(allJsonLines);
        runToolUseHitsMaxTurns(allJsonLines);
        runMaxOutputTokensWithoutEscalationExhaustsRecovery(allJsonLines);
        runMaxOutputTokensWithEscalationExhaustsRecovery(allJsonLines);
        runStopHookBlockingHitsMaxTurns(allJsonLines);
        runStopHookPreventsContinuation(allJsonLines);
        runAbortDuringStreaming(allJsonLines);
        runBudgetExceededPreTurn(allJsonLines);
        runReactiveCompactSingleShotThenStreamError(allJsonLines);

        assertFalse(allJsonLines.isEmpty(), "Expected recorded QueryLoop transitions");

        Path traceFile = Path.of("build", "formal-traces", "query-loop-traces.jsonl");
        Files.createDirectories(traceFile.getParent());
        Files.write(traceFile, allJsonLines);

        verifyWithLeanTraceVerifierIfAvailable(traceFile, allJsonLines.size());
    }

    private void runSingleTurnNormalEnd(List<String> sink) {
        var observer = new RecordingTraceObserver("single_turn_normal_end", sink);
        var engine = newEngine(QuerySessionSpec.builder().maxTurns(5), null, null);
        var params = paramsFor(engine, observer, _ -> endTurnEvents("hello").iterator(), null);

        drain(new QueryLoop(engine, params));
        assertSingleTerminal(observer, Terminal.Normal.class);
    }

    private void runToolUseHitsMaxTurns(List<String> sink) {
        var observer = new RecordingTraceObserver("tool_use_hits_max_turns", sink);
        var engine = newEngine(QuerySessionSpec.builder().maxTurns(3), null, null);
        AtomicInteger call = new AtomicInteger();
        ToolRunner stubTools = (blocks, sess, _, _, _, _) -> {
            List<ContentBlock> results = new ArrayList<>();
            for (ContentBlock b : blocks) {
                if (b instanceof ToolUseBlock tu) {
                    results.add(new ToolResultBlock(tu.id(), List.of(new TextBlock("ok")), false));
                }
            }
            UserMessage toolResultMsg = new UserMessage(
                UUID.randomUUID().toString(), MessageContent.ofBlocks(results));
            sess.getMutableMessages().add(toolResultMsg);
            return new ToolRunner.RunOutcome(false, null, 0, false, null, null);
        };
        var params = paramsFor(engine, observer,
            _ -> toolUseEvents("tu-" + call.incrementAndGet()).iterator(), stubTools);

        drain(new QueryLoop(engine, params));
        assertEquals(3, observer.outcomes().size());
        assertSingleTerminal(observer, Terminal.MaxTurns.class);
    }

    private void runMaxOutputTokensWithoutEscalationExhaustsRecovery(List<String> sink) {
        var observer = new RecordingTraceObserver("max_output_tokens_no_escalation", sink);
        var flags = FeatureFlagRegistry.allOff();
        var engine = newEngine(QuerySessionSpec.builder().maxTurns(10).featureFlags(flags), null, null);
        var params = paramsFor(engine, observer,
            _ -> outputTokenLimitEvents("max_tokens").iterator(), null);

        drain(new QueryLoop(engine, params));
        // 3 recovery attempts + 1 terminal normal = 4 transitions
        assertEquals(4, observer.outcomes().size());
        assertSingleTerminal(observer, Terminal.Normal.class);
    }

    private void runMaxOutputTokensWithEscalationExhaustsRecovery(List<String> sink) {
        var observer = new RecordingTraceObserver("max_output_tokens_with_escalation", sink);
        var flags = FeatureFlagRegistry.builder()
            .enable(FeatureFlag.MAX_OUTPUT_TOKENS_SLOT)
            .build();
        var engine = newEngine(QuerySessionSpec.builder().maxTurns(15).featureFlags(flags), null, null);
        var params = paramsFor(engine, observer,
            _ -> outputTokenLimitEvents("max_tokens").iterator(), null);

        drain(new QueryLoop(engine, params));
        // Escalation + 3 recoveries + final exhaustion — strictly bounded!
        assertTrue(observer.outcomes().size() >= 4 && observer.outcomes().size() <= 8,
            "Expected bounded escalation/recovery transitions, got " + observer.outcomes().size());
        assertSingleTerminal(observer, Terminal.Normal.class);
    }

    private static HookDispatcher stopHookOf(HookDispatcher.HookOutcome outcome) {
        return new HookDispatcher() {
            @Override
            public boolean dispatchPreToolUse(String toolName, JsonNode input, String toolUseId) {
                return true;
            }

            @Override
            public void dispatchPostToolUse(String toolName, JsonNode input, JsonNode output, String toolUseId) {
            }

            @Override
            public void dispatchUserPromptSubmit(String prompt) {
            }

            @Override
            public void dispatchSessionStart(String trigger) {
            }

            @Override
            public void dispatchStop(String reason) {
            }

            @Override
            public HookOutcome dispatchStopWithOutcome(String reason, boolean stopHookActive) {
                return outcome;
            }
        };
    }

    private void runStopHookBlockingHitsMaxTurns(List<String> sink) {
        var observer = new RecordingTraceObserver("stop_hook_blocking_hits_max_turns", sink);
        HookDispatcher blockingHook = stopHookOf(
            new HookDispatcher.HookOutcome(false, null, List.of("keep going"), false, null, null, null, null));
        var engine = newEngine(QuerySessionSpec.builder().maxTurns(3), null, blockingHook);
        var params = paramsFor(engine, observer, _ -> endTurnEvents("done?").iterator(), null);

        drain(new QueryLoop(engine, params));
        assertEquals(3, observer.outcomes().size());
        assertSingleTerminal(observer, Terminal.MaxTurns.class);
    }

    private void runStopHookPreventsContinuation(List<String> sink) {
        var observer = new RecordingTraceObserver("stop_hook_prevents_continuation", sink);
        HookDispatcher preventingHook = stopHookOf(
            new HookDispatcher.HookOutcome(false, null, List.of(), true, "policy_stop", null, null, null));
        var engine = newEngine(QuerySessionSpec.builder().maxTurns(5), null, preventingHook);
        var params = paramsFor(engine, observer, _ -> endTurnEvents("stopping").iterator(), null);

        drain(new QueryLoop(engine, params));
        assertEquals(1, observer.outcomes().size());
        assertSingleTerminal(observer, Terminal.StopHookPrevented.class);
    }

    private void runAbortDuringStreaming(List<String> sink) {
        var observer = new RecordingTraceObserver("abort_during_streaming", sink);
        var engine = newEngine(QuerySessionSpec.builder().maxTurns(5), null, null);
        var params = paramsFor(engine, observer, _ -> {
            engine.getAbortController().abort("user_cancelled");
            return endTurnEvents("partial").iterator();
        }, null);

        drain(new QueryLoop(engine, params));
        assertEquals(1, observer.outcomes().size());
        assertSingleTerminal(observer, Terminal.Aborted.class);
    }

    private void runBudgetExceededPreTurn(List<String> sink) {
        var observer = new RecordingTraceObserver("budget_exceeded_pre_turn", sink);
        var engine = newEngine(QuerySessionSpec.builder().maxTurns(5).maxBudgetUsd(0.0001), null, null);
        engine.setTotalUsage(new Usage(100_000, 100_000, 0, 0));
        var params = paramsFor(engine, observer, _ -> endTurnEvents("unused").iterator(), null);

        drain(new QueryLoop(engine, params));
        assertEquals(1, observer.outcomes().size());
        assertSingleTerminal(observer, Terminal.MaxBudget.class);
    }

    private void runReactiveCompactSingleShotThenStreamError(List<String> sink) {
        var observer = new RecordingTraceObserver("reactive_compact_single_shot", sink);
        var boundary = new SystemMessage("boundary", "compact_boundary", "info", "");
        var summary = new UserMessage("summary", MessageContent.ofText("Summary"));
        var compacted = new MessageCompactor.CompactionResult(
            boundary, List.of(summary), List.of(), List.of(), List.of(), 100L);
        MessageCompactor compactService = new MessageCompactor() {
            @Override
            public MicrocompactResult microcompactMessages(List<Message> messages) {
                return new MicrocompactResult(messages);
            }

            @Override
            public boolean shouldAutoCompact(List<Message> messages, String model, String querySource) {
                return false;
            }

            @Override
            public boolean isAtBlockingLimit(List<Message> messages, String model) {
                return false;
            }

            @Override
            public boolean isAutoCompactEnabled() {
                return true;
            }

            @Override
            public boolean isReactiveCompactEnabled() {
                return true;
            }

            @Override
            public CompactionResult compactConversation(List<Message> messages, boolean isAutoCompact) {
                return compacted;
            }
        };
        var engine = newEngine(QuerySessionSpec.builder().maxTurns(5), compactService, null);
        QueryDeps deps = new QueryDeps() {
            @Override
            public Iterator<StreamingClient.StreamingEvent> callModel(StreamingClient.StreamRequest request) {
                throw new RuntimeException("prompt is too long: 210000 tokens > 200000 maximum");
            }

            @Override
            public MessageCompactor.MicrocompactResult microcompact(List<Message> messages) {
                return new MessageCompactor.MicrocompactResult(messages);
            }

            @Override
            public boolean shouldAutoCompact(List<Message> messages, String model, String querySource) {
                return false;
            }

            @Override
            public AutoCompactResult autocompact(List<Message> messages, String model, String querySource,
                                                 AutoCompactTrackingState tracking, String customInstructions,
                                                 long snipTokensFreed) {
                return new AutoCompactResult(compacted, 0);
            }

            @Override
            public String uuid() {
                return UUID.randomUUID().toString();
            }

            @Override
            public LoopTraceObserver traceObserver() {
                return observer;
            }
        };
        var params = QueryParams.builder()
            .messages(engine.getMutableMessages())
            .systemPrompt("Be helpful")
            .model("test-model")
            .deps(deps)
            .build();

        drain(new QueryLoop(engine, params));
        // Attempt 1: reactive compact succeeds -> Continue.PromptTooLongRecovery
        // Attempt 2: already attempted reactive compact -> Terminal.StreamError
        assertEquals(2, observer.outcomes().size());
        assertSingleTerminal(observer, Terminal.StreamError.class);
    }

    private static void verifyWithLeanTraceVerifierIfAvailable(Path traceFile, int count) throws Exception {
        Path repoRoot = findRepoRoot();
        Path verifierBin = repoRoot.resolve("formal/.lake/build/bin/trace-verifier");
        boolean requireVerifier = Boolean.getBoolean("formal.requireVerifier")
            || Strings.CI.equals("true", System.getenv("FORMAL_REQUIRE_VERIFIER"));

        if (!Files.isExecutable(verifierBin)) {
            assertFalse(requireVerifier,
                "Expected compiled Lean 4 trace-verifier at " + verifierBin
                    + " (run `cd formal && lake build` first)");
            log.info("[QueryLoopConformanceTest] trace-verifier not built at {}; recorded {} transitions to {}. "
                    + "Run `cd formal && lake build` to enable Lean 4 trace verification.",
                verifierBin, count, traceFile.toAbsolutePath());
            return;
        }

        Process proc = new ProcessBuilder(verifierBin.toString(), traceFile.toAbsolutePath().toString())
            .redirectErrorStream(true)
            .start();
        String output = new String(proc.getInputStream().readAllBytes());
        int exitCode = proc.waitFor();
        assertEquals(0, exitCode, "Lean 4 trace-verifier rejected QueryLoop trace:\n" + output);
        assertTrue(Strings.CS.contains(output, "OK: Verified " + count),
            "Unexpected trace-verifier output: " + output);
    }

    private static Path findRepoRoot() {
        Path cur = Path.of("").toAbsolutePath();
        while (cur != null) {
            if (Files.exists(cur.resolve("formal/lakefile.toml"))) {
                return cur;
            }
            cur = cur.getParent();
        }
        return Path.of("").toAbsolutePath();
    }

    private static void assertSingleTerminal(RecordingTraceObserver observer, Class<? extends Terminal> expected) {
        List<LoopTraceObserver.OutcomeSnapshot> outcomes = observer.outcomes();
        assertFalse(outcomes.isEmpty(), "Expected at least one recorded transition");
        long doneCount = outcomes.stream()
            .filter(LoopTraceObserver.OutcomeSnapshot.Done.class::isInstance)
            .count();
        assertEquals(1L, doneCount, "Expected exactly one Terminal outcome, got: " + outcomes);
        LoopTraceObserver.OutcomeSnapshot last = outcomes.getLast();
        assertTrue(last instanceof LoopTraceObserver.OutcomeSnapshot.Done(Terminal t) && expected.isInstance(t),
            "Expected last outcome to be Done(" + expected.getSimpleName() + "), got: " + last);
    }

    private static DefaultQuerySession newEngine(QuerySessionSpec.Builder specBuilder,
                                                 MessageCompactor compactService,
                                                 HookDispatcher hookDispatcher) {
        StreamingClient dummyClient = new StreamingClient() {
            @Override
            public Iterator<StreamingEvent> createStream(StreamRequest request) {
                return endTurnEvents("unused").iterator();
            }

            @Override
            public String getModel() {
                return "test-model";
            }
        };
        QuerySessionSpec spec = specBuilder
            .llmClient(dummyClient)
            .systemPrompt("Be helpful")
            .build();
        DefaultQuerySession engine = compactService != null
            ? new DefaultQuerySession(spec, compactService)
            : new DefaultQuerySession(spec);
        if (hookDispatcher != null) {
            engine.setHookDispatcher(hookDispatcher);
        }
        engine.getMutableMessages().add(
            new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("Hi")));
        return engine;
    }

    private static QueryParams paramsFor(DefaultQuerySession engine,
                                         LoopTraceObserver observer,
                                         Function<StreamingClient.StreamRequest, Iterator<StreamingClient.StreamingEvent>> caller,
                                         ToolRunner customToolRunner) {
        QueryDeps deps = new QueryDeps() {
            @Override
            public Iterator<StreamingClient.StreamingEvent> callModel(StreamingClient.StreamRequest request) {
                return caller.apply(request);
            }

            @Override
            public MessageCompactor.MicrocompactResult microcompact(List<Message> messages) {
                return new MessageCompactor.MicrocompactResult(messages);
            }

            @Override
            public boolean shouldAutoCompact(List<Message> messages, String model, String querySource) {
                return false;
            }

            @Override
            public AutoCompactResult autocompact(List<Message> messages, String model, String querySource,
                                                 AutoCompactTrackingState tracking, String customInstructions,
                                                 long snipTokensFreed) {
                return new AutoCompactResult(null, null);
            }

            @Override
            public String uuid() {
                return UUID.randomUUID().toString();
            }

            @Override
            public ToolRunner toolRunner() {
                return customToolRunner != null ? customToolRunner : ToolRunner.resolve();
            }

            @Override
            public LoopTraceObserver traceObserver() {
                return observer;
            }
        };
        return QueryParams.builder()
            .messages(engine.getMutableMessages())
            .systemPrompt("Be helpful")
            .model("test-model")
            .deps(deps)
            .build();
    }

    private static List<StreamingClient.StreamingEvent> endTurnEvents(String text) {
        return List.of(
            new StreamingClient.StreamingEvent.MessageStartEvent(
                "msg-1", "test-model", List.of(), new Usage(10, 0, 0, 0)),
            new StreamingClient.StreamingEvent.ContentBlockDeltaEvent(0, "text_delta", text),
            new StreamingClient.StreamingEvent.MessageDeltaEvent("end_turn", new Usage(0, 5, 0, 0)),
            new StreamingClient.StreamingEvent.MessageStopEvent()
        );
    }

    private static List<StreamingClient.StreamingEvent> outputTokenLimitEvents(String reason) {
        return List.of(
            new StreamingClient.StreamingEvent.MessageStartEvent(
                "msg-limit", "test-model", List.of(), new Usage(10, 0, 0, 0)),
            new StreamingClient.StreamingEvent.MessageDeltaEvent(reason, new Usage(0, 8_000, 0, 0)),
            new StreamingClient.StreamingEvent.MessageStopEvent()
        );
    }

    private static List<StreamingClient.StreamingEvent> toolUseEvents(String toolUseId) {
        return List.of(
            new StreamingClient.StreamingEvent.MessageStartEvent(
                "msg-" + toolUseId, "test-model", List.of(), Usage.EMPTY),
            new StreamingClient.StreamingEvent.ContentBlockStartEvent(
                0, "tool_use", toolUseId, "Bash"),
            new StreamingClient.StreamingEvent.ContentBlockDeltaEvent(
                0, "input_json_delta", "{\"cmd\":\"true\"}"),
            new StreamingClient.StreamingEvent.ContentBlockStopEvent(0),
            new StreamingClient.StreamingEvent.MessageDeltaEvent("tool_use", Usage.EMPTY),
            new StreamingClient.StreamingEvent.MessageStopEvent()
        );
    }

    private static List<SDKMessage> drain(Iterator<SDKMessage> iter) {
        List<SDKMessage> msgs = new ArrayList<>();
        while (iter.hasNext()) {
            msgs.add(iter.next());
        }
        return msgs;
    }
}

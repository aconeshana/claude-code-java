package com.claudecode.runtime.query;

import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolExecutor;
import com.claudecode.core.engine.ToolResult;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;


class ConcurrentToolRunnerTest {

    private static final StreamingClient NOOP_CLIENT = new StreamingClient() {
        @Override
        public Iterator<StreamingClient.StreamingEvent> createStream(StreamingClient.StreamRequest request) {
            return Collections.emptyIterator();
        }
        @Override
        public String getModel() { return "test-model"; }
    };

    @AfterEach
    void clearCapProperty() {
        System.clearProperty("claude.code.maxToolUseConcurrency");
    }

    /** Configurable executor recording concurrency + working-directory. */
    private static final class TestToolExecutor implements ToolExecutor {
        final Set<String> safe;
        final Map<String, ToolResult> results;
        final Map<String, Long> sleeps;
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();
        volatile String capturedWorkingDirectory;

        TestToolExecutor(Set<String> safe, Map<String, ToolResult> results, Map<String, Long> sleeps) {
            this.safe = safe;
            this.results = results;
            this.sleeps = sleeps;
        }

        @Override
        public ToolResult execute(String toolName, JsonNode input, ToolExecutionContext ctx) {
            capturedWorkingDirectory = ctx.workingDirectory();
            int cur = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(cur, Math::max);
            Long ms = sleeps.get(toolName);
            if (ms != null && ms > 0) {
                try { Thread.sleep(ms); } catch (InterruptedException _) { Thread.currentThread().interrupt(); }
            }
            inFlight.decrementAndGet();
            ToolResult r = results.get(toolName);
            return r != null ? r : ToolResult.success("ok");
        }

        @Override
        public boolean isConcurrencySafe(String toolName, JsonNode input) {
            return safe.contains(toolName);
        }
    }

    private static DefaultQuerySession newEngine(ToolExecutor executor) {
        return new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT).toolExecutor(executor).build());
    }

    private static DefaultQuerySession newEngine(ToolExecutor executor, String workingDirectory) {
        return new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT).toolExecutor(executor).workingDirectory(workingDirectory).build());
    }

    private static ToolUseBlock tub(String id, String name) {
        return new ToolUseBlock(id, name, JsonUtils.getMapper().createObjectNode());
    }

    /** Ordered list of tool_use_ids among the result messages in history. */
    private static List<String> resultOrder(DefaultQuerySession engine) {
        List<String> ids = new ArrayList<>();
        for (Message m : engine.getMutableMessages()) {
            if (m instanceof UserMessage um) {
                MessageContent mc = um.message();
                if (mc != null && mc.blocks() != null && !mc.blocks().isEmpty()
                        && mc.blocks().getFirst() instanceof ToolResultBlock trb) {
                    ids.add(trb.toolUseId());
                }
            }
        }
        return ids;
    }

    @Test
    void parallelBatch_preservesOriginalOrder() {
        Set<String> safe = Set.of("Read");
        Map<String, ToolResult> results = Map.of("Read", ToolResult.success("ok"));
        Map<String, Long> sleeps = Map.of("Read", 60L);
        TestToolExecutor ex = new TestToolExecutor(safe, results, sleeps);
        DefaultQuerySession engine = newEngine(ex);

        List<ContentBlock> blocks = List.of(
            tub("tu-1", "Read"), tub("tu-2", "Read"), tub("tu-3", "Read"), tub("tu-4", "Read"));

        new ConcurrentToolRunner().run(blocks, engine, false, 1, _ -> {});

        assertEquals(List.of("tu-1", "tu-2", "tu-3", "tu-4"), resultOrder(engine));
        // Parallelism actually happened (cap default 10 >= 4).
        assertEquals(4, ex.maxInFlight.get());
    }

    @Test
    void concurrencyCap_isEnforced() {
        System.setProperty("claude.code.maxToolUseConcurrency", "2");
        Set<String> safe = Set.of("Read");
        Map<String, ToolResult> results = Map.of("Read", ToolResult.success("ok"));
        Map<String, Long> sleeps = Map.of("Read", 80L);
        TestToolExecutor ex = new TestToolExecutor(safe, results, sleeps);
        DefaultQuerySession engine = newEngine(ex);

        List<ContentBlock> blocks = new ArrayList<>();
        for (int i = 0; i < 5; i++) blocks.add(tub("tu-" + i, "Read"));

        new ConcurrentToolRunner().run(blocks, engine, false, 1, _ -> {});

        assertEquals(5, resultOrder(engine).size());
        // Fixed pool of 2 bounds in-flight tools deterministically.
        assertTrue(ex.maxInFlight.get() <= 2, "max in-flight was " + ex.maxInFlight.get());
    }

    @Test
    void bashError_abortsSiblingInBatch() {
        System.setProperty("claude.code.maxToolUseConcurrency", "2");
        Set<String> safe = Set.of("Read", "Bash");
        Map<String, ToolResult> results = new HashMap<>();
        results.put("Read", ToolResult.success("ok"));
        results.put("Bash", ToolResult.error("boom"));
        Map<String, Long> sleeps = new HashMap<>();
        sleeps.put("Read", 400L);
        sleeps.put("Bash", 5L);
        TestToolExecutor ex = new TestToolExecutor(safe, results, sleeps);
        DefaultQuerySession engine = newEngine(ex);


        // siblings are cancelled, but a sibling that has ALREADY completed keeps its
        // result. The large asymmetry removes the load-dependent race.
        List<ContentBlock> blocks = List.of(
            tub("tu-1", "Read"), tub("tu-2", "Bash"), tub("tu-3", "Read"));

        new ConcurrentToolRunner().run(blocks, engine, false, 1, _ -> {});

        assertEquals(List.of("tu-1", "tu-2", "tu-3"), resultOrder(engine));

        // Verify each result's content/error by id.
        Map<String, UserMessage> byId = new HashMap<>();
        for (Message m : engine.getMutableMessages()) {
            if (m instanceof UserMessage um) {
                MessageContent mc = um.message();
                if (mc != null && mc.blocks() != null && !mc.blocks().isEmpty()
                        && mc.blocks().getFirst() instanceof ToolResultBlock trb) {
                    byId.put(trb.toolUseId(), um);
                }
            }
        }
        ToolResultBlock tu1 = (ToolResultBlock) byId.get("tu-1").message().blocks().getFirst();
        ToolResultBlock tu2 = (ToolResultBlock) byId.get("tu-2").message().blocks().getFirst();
        ToolResultBlock tu3 = (ToolResultBlock) byId.get("tu-3").message().blocks().getFirst();
        // tu-1 was in-flight when the sibling Bash errored → interrupted, so it
        // is CANCELLED, not completed. A queued sibling (tu-3) is also cancelled.
        // A sibling cascade uses the distinct sibling-error message (not the
        // user-reject CANCEL_MESSAGE), since the engine itself was not aborted.
        assertTrue(tu1.isError());
        assertTrue(Strings.CS.contains(textOf(tu1), MessageConstants.siblingErrorMessage("Read")));
        assertTrue(tu2.isError());
        assertTrue(Strings.CS.contains(textOf(tu2), "boom"));
        // Sibling cancelled (sibling-error placeholder, is_error).
        assertTrue(tu3.isError());
        assertTrue(Strings.CS.contains(textOf(tu3), MessageConstants.siblingErrorMessage("Read")));
    }

    @Test
    void nonSafeTool_breaksConcurrentRun() {
        Set<String> safe = Set.of("Read"); // "Write" is not safe -> its own serial batch
        Map<String, ToolResult> results = Map.of("Read", ToolResult.success("ok"), "Write", ToolResult.success("ok"));
        Map<String, Long> sleeps = Map.of();
        TestToolExecutor ex = new TestToolExecutor(safe, results, sleeps);
        DefaultQuerySession engine = newEngine(ex);

        List<ContentBlock> blocks = List.of(
            tub("tu-1", "Read"), tub("tu-2", "Write"), tub("tu-3", "Read"));

        new ConcurrentToolRunner().run(blocks, engine, false, 1, _ -> {});

        assertEquals(List.of("tu-1", "tu-2", "tu-3"), resultOrder(engine));
        assertEquals(3, resultOrder(engine).size());
    }

    @Test
    void concurrentRunner_preservesToolOrder() {
        Set<String> safe = Set.of("Read");
        Map<String, ToolResult> results = Map.of("Read", ToolResult.success("ok"));
        Map<String, Long> sleeps = Map.of();
        TestToolExecutor ex = new TestToolExecutor(safe, results, sleeps);
        DefaultQuerySession engine = newEngine(ex);

        List<ContentBlock> blocks = List.of(
            tub("tu-1", "Read"), tub("tu-2", "Read"), tub("tu-3", "Read"));

        new ConcurrentToolRunner().run(blocks, engine, false, 1, _ -> {});

        // Concurrency-safe tools execute in parallel, but results are appended/emitted
        // in the original tool order so the conversation stays API-replayable.
        assertEquals(List.of("tu-1", "tu-2", "tu-3"), resultOrder(engine));
    }

    @Test
    void workingDirectory_isThreadedToExecutor(@TempDir Path configuredDir) {
        Set<String> safe = Set.of("Read");
        Map<String, ToolResult> results = Map.of("Read", ToolResult.success("ok"));
        Map<String, Long> sleeps = Map.of();
        TestToolExecutor ex = new TestToolExecutor(safe, results, sleeps);
        DefaultQuerySession engine = newEngine(ex, configuredDir.toString());

        List<ContentBlock> blocks = List.of(tub("tu-1", "Read"), tub("tu-2", "Read"));
        new ConcurrentToolRunner().run(blocks, engine, false, 1, _ -> {});

        assertEquals(configuredDir.toString(), ex.capturedWorkingDirectory);
    }

    @Test
    void siblingBashError_interruptsInFlightSibling() {

        // sibling-abort cancelling in-flight concurrency-safe tools). The Read
// executor is abort-aware (polls ctx.abortController.isAborted), exactly

        // the sibling abort fires. The batch finishing well under the 2s in-flight
        // timeout proves the in-flight sibling was interrupted, not awaited.
        System.setProperty("claude.code.maxToolUseConcurrency", "3");
        Set<String> safe = Set.of("Read", "Bash");
        AtomicBoolean inFlightReadInterrupted = new AtomicBoolean(false);
        ToolExecutor ex = new ToolExecutor() {
            @Override
            public ToolResult execute(String toolName, JsonNode input, ToolExecutionContext ctx) {
                if (Strings.CS.equals("Bash", toolName)) {
                    // Small delay so the in-flight Read reliably starts and
                    // registers its abort handler before the sibling-abort fires;
                    // that models the realistic "in-flight tool bails on a
                    // sibling Bash error" path (without it, the abort can land
                    // before the Read thread schedules, cancelling it pre-start).
                    try { Thread.sleep(20); } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                        return ToolResult.error("boom");
                    }
                    return ToolResult.error("boom");
                }
                // Production tools register their own abort handler (e.g. BashTool's
                // process.destroyForcibly) so an in-flight sibling abort is observed
// even if the tool body hasn't started polling yet. match that here.
                ctx.abortController().onAbort(() -> inFlightReadInterrupted.set(true));
                // Poll the abort signal; bail promptly when a sibling aborted the batch.
                for (int i = 0; i < 40; i++) {
                    if (ctx.abortController().isAborted()) {
                        inFlightReadInterrupted.set(true);
                        return ToolResult.success("interrupted");
                    }
                    try { Thread.sleep(50); } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                        inFlightReadInterrupted.set(true);
                        return ToolResult.success("interrupted");
                    }
                }
                return ToolResult.success("done");
            }
            @Override
            public boolean isConcurrencySafe(String toolName, JsonNode input) {
                return safe.contains(toolName);
            }
        };
        DefaultQuerySession engine = newEngine(ex);

        // tu-1 (Bash) errors immediately; tu-2 (Read) is in-flight; tu-3 (Read) queued.
        List<ContentBlock> blocks = List.of(tub("tu-1", "Bash"), tub("tu-2", "Read"), tub("tu-3", "Read"));
        long start = System.nanoTime();
        new ConcurrentToolRunner().run(blocks, engine, false, 1, _ -> {});
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // In-flight Read (tu-2) must have observed the sibling-abort (proving the
        // interrupt signal propagated to the running tool).
        assertTrue(inFlightReadInterrupted.get(),
            "in-flight Read (tu-2) must observe the sibling-abort and bail out");
        // The batch must not wait out either Read's full 2s window.
        assertTrue(elapsedMs < 1500, "batch must not wait out the in-flight Read: " + elapsedMs + "ms");
        assertEquals(List.of("tu-1", "tu-2", "tu-3"), resultOrder(engine));

        // And the in-flight + queued siblings are reported with the sibling-error
        // message (engine was not user-aborted) in history.
        Map<String, UserMessage> byId = new HashMap<>();
        for (Message m : engine.getMutableMessages()) {
            if (m instanceof UserMessage um) {
                MessageContent mc = um.message();
                if (mc != null && mc.blocks() != null && !mc.blocks().isEmpty()
                        && mc.blocks().getFirst() instanceof ToolResultBlock trb) {
                    byId.put(trb.toolUseId(), um);
                }
            }
        }
        ToolResultBlock tu2 = (ToolResultBlock) byId.get("tu-2").message().blocks().getFirst();
        ToolResultBlock tu3 = (ToolResultBlock) byId.get("tu-3").message().blocks().getFirst();
        assertTrue(tu2.isError() && Strings.CS.contains(textOf(tu2), MessageConstants.siblingErrorMessage("Read")),
            "in-flight Read (tu-2) must be cancelled by sibling-abort");
        assertTrue(tu3.isError() && Strings.CS.contains(textOf(tu3), MessageConstants.siblingErrorMessage("Read")),
            "queued Read (tu-3) must be cancelled by sibling-abort");
    }

    @Test
    void emitOrder_matchesOriginalOrder() {

        System.setProperty("claude.code.maxToolUseConcurrency", "3");
        Set<String> safe = Set.of("SlowA", "Fast", "Med");
        Map<String, ToolResult> results = Map.of(
            "SlowA", ToolResult.success("ok"),
            "Fast", ToolResult.success("ok"),
            "Med", ToolResult.success("ok"));
        Map<String, Long> sleeps = Map.of("SlowA", 200L, "Fast", 10L, "Med", 100L);
        TestToolExecutor ex = new TestToolExecutor(safe, results, sleeps);
        DefaultQuerySession engine = newEngine(ex);

        List<ContentBlock> blocks = List.of(
            tub("tu-1", "SlowA"), tub("tu-2", "Fast"), tub("tu-3", "Med"));

        List<String> emitOrder = new ArrayList<>();
        new ConcurrentToolRunner().run(blocks, engine, false, 1, msg -> {
            if (msg instanceof SDKMessage.User u) {
                UserMessage um = u.message();
                MessageContent mc = um.message();
                if (mc != null && mc.blocks() != null && !mc.blocks().isEmpty()
                        && mc.blocks().getFirst() instanceof ToolResultBlock trb) {
                    emitOrder.add(trb.toolUseId());
                }
            }
        });

        // Original order is preserved on the wire (SlowA blocks until done, gating
        // Fast/Med even though they finished first): tu-1, tu-2, tu-3.
        assertEquals(List.of("tu-1", "tu-2", "tu-3"), emitOrder);
        // History also original order.
        assertEquals(List.of("tu-1", "tu-2", "tu-3"), resultOrder(engine));
    }

    @Test
    void newMessages_injectedIntoHistoryAndStream() {

        // injects extra conversation messages must have them appended to history
        // and streamed right after the tool_result message.
        Set<String> safe = Set.of("Read");
        Message injected = new UserMessage("injected-uuid", MessageContent.ofText("injected"));
        Map<String, ToolResult> results = new HashMap<>();
        results.put("Read", ToolResult.success("ok").withNewMessages(List.of(injected)));
        TestToolExecutor ex = new TestToolExecutor(safe, results, Map.of());
        DefaultQuerySession engine = newEngine(ex);

        List<ContentBlock> blocks = List.of(tub("tu-1", "Read"));
        List<Message> emitted = new ArrayList<>();
        new ConcurrentToolRunner().run(blocks, engine, false, 1, msg -> {
            if (msg instanceof SDKMessage.User u) emitted.add(u.message());
        });

        // The injected message is appended to history (distinct from the tool_result).
        boolean sawInjected = engine.getMutableMessages().stream()
            .anyMatch(m -> m instanceof UserMessage um && Strings.CS.equals("injected-uuid", um.uuid()));
        assertTrue(sawInjected, "injected newMessage must be appended to history");
        // It was streamed as an SDKMessage.User.
        assertTrue(emitted.stream()
                .anyMatch(m -> m instanceof UserMessage um && Strings.CS.equals("injected-uuid", um.uuid())),
            "injected newMessage must be streamed");
    }

    @Test
    void structuredOutput_propagatesFromToolResult() {

        Set<String> safe = Set.of("Read");
        ObjectNode so = JsonUtils.getMapper().createObjectNode();
        so.put("answer", 42);
        Map<String, ToolResult> results = new HashMap<>();
        results.put("Read", ToolResult.success("ok").withStructuredOutput(so));
        TestToolExecutor ex = new TestToolExecutor(safe, results, Map.of());
        DefaultQuerySession engine = newEngine(ex);

        List<ContentBlock> blocks = List.of(tub("tu-1", "Read"));
        ToolRunner.RunOutcome outcome =
            new ConcurrentToolRunner().run(blocks, engine, false, 1, _ -> {});

        assertNotNull(outcome.structuredOutput(), "structured_output must propagate to RunOutcome");
        assertEquals(42, outcome.structuredOutput().path("answer").asInt());
    }

    @Test
    void engineAbort_userCancelReason_usesRejectMessage() {
        // An engine-level abort (reason "user-cancel", what DefaultQuerySession.interrupt

        // StreamingToolExecutor.createSyntheticErrorMessage reason
        // 'user_interrupted', which yields REJECT_MESSAGE (NOT CANCEL_MESSAGE and
        // NOT the sibling text). Every tool should report REJECT_MESSAGE.
        Set<String> safe = Set.of("Read");
        Map<String, ToolResult> results = Map.of("Read", ToolResult.success("ok"));
        TestToolExecutor ex = new TestToolExecutor(safe, results, Map.of());
        DefaultQuerySession engine = newEngine(ex);
        engine.getAbortController().abort("user-cancel");

        List<ContentBlock> blocks = List.of(tub("tu-1", "Read"), tub("tu-2", "Read"));
        new ConcurrentToolRunner().run(blocks, engine, false, 1, _ -> {});

        for (String id : List.of("tu-1", "tu-2")) {
            ToolResultBlock trb = resultFor(engine, id);
            assertNotNull(trb);
            assertTrue(Strings.CS.contains(textOf(trb), MessageConstants.REJECT_MESSAGE));
            assertFalse(Strings.CS.contains(textOf(trb), MessageConstants.siblingErrorMessage("Read")));
        }
    }

    @Test
    void engineAbort_anyReason_usesRejectMessage() {





        Set<String> safe = Set.of("Read");
        Map<String, ToolResult> results = Map.of("Read", ToolResult.success("ok"));
        TestToolExecutor ex = new TestToolExecutor(safe, results, Map.of());
        DefaultQuerySession engine = newEngine(ex);
        engine.getAbortController().abort("interrupt");

        List<ContentBlock> blocks = List.of(tub("tu-1", "Read"), tub("tu-2", "Read"));
        new ConcurrentToolRunner().run(blocks, engine, false, 1, _ -> {});

        for (String id : List.of("tu-1", "tu-2")) {
            ToolResultBlock trb = resultFor(engine, id);
            assertNotNull(trb);
            assertTrue(Strings.CS.contains(textOf(trb), MessageConstants.REJECT_MESSAGE));
            assertFalse(Strings.CS.contains(textOf(trb), MessageConstants.siblingErrorMessage("Read")));
        }
    }

    private static String textOf(ToolResultBlock trb) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : trb.content()) {
            if (b instanceof TextBlock tb) sb.append(tb.text());
        }
        return sb.toString();
    }

    /** Fetch a single tool_result block by its tool_use id from history. */
    private static ToolResultBlock resultFor(DefaultQuerySession engine, String id) {
        for (Message m : engine.getMutableMessages()) {
            if (m instanceof UserMessage um) {
                MessageContent mc = um.message();
                if (mc != null && mc.blocks() != null && !mc.blocks().isEmpty()
                        && mc.blocks().getFirst() instanceof ToolResultBlock trb
                        && id.equals(trb.toolUseId())) {
                    return trb;
                }
            }
        }
        return null;
    }
}

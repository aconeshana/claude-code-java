package com.claudecode.ui.lanterna.repl;

import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolExecutor;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.queue.InterruptBehavior;
import com.claudecode.core.queue.QueuedCommand;
import com.claudecode.runtime.query.DefaultQuerySession;
import com.claudecode.runtime.query.QuerySessionSpec;
import com.claudecode.runtime.turn.ConversationOps;
import com.claudecode.runtime.turn.SessionSink;
import com.claudecode.runtime.turn.TurnAwakeGuard;
import com.claudecode.runtime.turn.TurnEngine;
import com.claudecode.runtime.turn.TurnOutcome;
import com.claudecode.runtime.turn.UserInput;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Queue-steer decision at the submission edge over a real {@link TurnEngine}:
 * while a turn is in flight, the coordinator's steer gate must read the engine's
 * steer flag and call the abort port exactly once before enqueuing — the UI twin
 * of {@code handlePromptSubmit}'s {@code hasInterruptibleToolInProgress → abort →
 * enqueue} sequence. The flag itself is validated in the runtime module's tests;
 * this class fixes the coordinator's branch order against the engine ports.
 */
class ReplSubmissionCoordinatorSteerTest {

    private static final StreamingClient NOOP_CLIENT = new StreamingClient() {
        @Override
        public Iterator<StreamingClient.StreamingEvent> createStream(StreamingClient.StreamRequest request) {
            return Collections.emptyIterator();
        }
        @Override
        public String getModel() { return "test-model"; }
    };

    /** Streams exactly one assistant tool_use for "Gated" so the turn executes it. */
    private static final StreamingClient TOOL_USE_CLIENT = new StreamingClient() {
        @Override
        public Iterator<StreamingClient.StreamingEvent> createStream(StreamingClient.StreamRequest request) {
            return List.<StreamingClient.StreamingEvent>of(
                new StreamingClient.StreamingEvent.MessageStartEvent(
                    "msg-1", "test-model", List.of(), Usage.EMPTY),
                new StreamingClient.StreamingEvent.ContentBlockStartEvent(
                    0, "tool_use", "tu-1", "Gated"),
                new StreamingClient.StreamingEvent.ContentBlockDeltaEvent(
                    0, "input_json_delta", "{}"),
                new StreamingClient.StreamingEvent.ContentBlockStopEvent(0),
                new StreamingClient.StreamingEvent.MessageDeltaEvent(
                    "tool_use", Usage.EMPTY),
                new StreamingClient.StreamingEvent.MessageStopEvent()
            ).iterator();
        }
        @Override
        public String getModel() { return "test-model"; }
    };

    /** Executor whose one tool blocks forever until the test aborts the turn. */
    private static final class GatedExecutor implements ToolExecutor {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public ToolResult execute(String toolName, JsonNode input, ToolExecutionContext ctx) {
            calls.incrementAndGet();
            // Block until the turn is aborted; the steer interrupt must break us
            // out so the turn can unwind and the flag can fall.
            while (!ctx.abortController().isAborted()) {
                try { Thread.sleep(20); } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return ToolResult.success("ok");
        }

        @Override
        public InterruptBehavior interruptBehavior(String toolName) {
            return InterruptBehavior.CANCEL;
        }
    }

    private static DefaultQuerySession newSession(ToolExecutor executor) {
        return new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(TOOL_USE_CLIENT).toolExecutor(executor).build());
    }

    private static TurnEngine newTurnEngine(DefaultQuerySession session,
                                            List<String> order) {
        SessionSink sink = new SessionSink() {
            @Override public void onTurnStart(UserInput input) { order.add("turn-start"); }
            @Override public void onMessage(SDKMessage msg) { }
            @Override public void onIdle() { order.add("idle"); }
            @Override public void onTurnComplete(TurnOutcome outcome) { }
            @Override public void onError(Throwable error, boolean userCancel) { }
        };
        return new TurnEngine(session, () -> null, sink, new ConversationOps() {
            @Override public void dropLastPromptHistoryEntry() { }
            @Override public UserMessage rewindBeforeLastRealUser() { return null; }
            @Override public String restoredInput(UserMessage message) { return null; }
        },
            batch -> order.add("drain:" + batch.size()),
            Runnable::run,                                  // onUi inline
            r -> { order.add("background"); Thread.ofVirtual().start(r); },
            _ -> { },                                    // recordLastSubmitted
            TurnAwakeGuard.noop(), () -> { });
    }

    @Test
    void steerGate_abortsBusyTurnBeforeEnqueueWhileSteerableToolRuns() throws Exception {
        GatedExecutor executor = new GatedExecutor();
        DefaultQuerySession session = newSession(executor);
        List<String> order = new ArrayList<>();
        TurnEngine engine = newTurnEngine(session, order);

        // 1. Start a turn whose query content the model-facing path will treat
        //    as a prompt; the engine submits on a background thread.
        engine.submit(UserInput.of("run the gated tool", "run the gated tool",
            Map.of(), null, false));
        // Wait until the gated CANCEL tool is executing (flag rises).
        awaitFlag(engine, true, "the gated CANCEL tool must raise the steer flag");

        // 2. The coordinator's busy branch, verbatim from handleQuery: with an
        //    in-flight turn and the steer flag up, abort BEFORE enqueuing.
        assertTrue(engine.isInFlight());
        assertTrue(engine.hasInterruptibleToolInProgress());
        engine.interruptForQueuedSubmit();
        order.add("aborted");
        engine.enqueue(new QueuedCommand("next prompt", Map.of(), "prompt", null,
            false, null, false, false, null, null, null));
        order.add("enqueued");

        // 3. The abort unwinds the turn (flag falls) and the queued prompt is
        //    drained into a new turn.
        awaitFlag(engine, false, "the steer abort must unwind the executing tool");
        awaitDrain(order, "the queued submission must be drained after the abort");
    }

    @Test
    void steerGate_idleEngineDoesNotAbort() {
        GatedExecutor executor = new GatedExecutor();
        DefaultQuerySession session = newSession(executor);
        List<String> order = new ArrayList<>();
        TurnEngine engine = newTurnEngine(session, order);

        assertFalse(engine.isInFlight());
        assertFalse(engine.hasInterruptibleToolInProgress());
        // interruptForQueuedSubmit is a no-op on an idle engine.
        engine.interruptForQueuedSubmit();
        assertFalse(session.getAbortController().isAborted(),
            "an idle engine must not be aborted by the steer port");
    }

    private static void awaitFlag(TurnEngine engine, boolean expected, String message)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (engine.hasInterruptibleToolInProgress() == expected) return;
            Thread.sleep(20);
        }
        fail(message);
    }

    private static void awaitDrain(List<String> order, String message) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (order.stream().anyMatch(o -> Strings.CS.startsWith(o, "drain:"))) return;
            Thread.sleep(20);
        }
        fail(message + " — order was " + order);
    }
}

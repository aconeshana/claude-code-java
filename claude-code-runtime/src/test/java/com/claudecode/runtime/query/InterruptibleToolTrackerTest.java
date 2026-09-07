package com.claudecode.runtime.query;

import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolExecutor;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.queue.InterruptBehavior;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Transition matrix of the mid-turn steer flag — the runtime twin of
 * {@code StreamingToolExecutor.updateInterruptibleState}: the flag is true only
 * while at least one tool executes AND every executing tool is cancellable.
 */
class InterruptibleToolTrackerTest {

    private static final StreamingClient NOOP_CLIENT = new StreamingClient() {
        @Override
        public Iterator<StreamingClient.StreamingEvent> createStream(StreamingClient.StreamRequest request) {
            return Collections.emptyIterator();
        }
        @Override
        public String getModel() { return "test-model"; }
    };

    /** Executor whose tools block until the test releases them. */
    private static final class GatedExecutor implements ToolExecutor {
        final Set<String> cancellable;
        final Map<String, CountDownLatch> starts = new HashMap<>();
        final Map<String, CountDownLatch> releases = new HashMap<>();
        final Set<String> executedToCompletion = Collections.synchronizedSet(
            Collections.newSetFromMap(new ConcurrentHashMap<>()));

        GatedExecutor(Set<String> cancellable, String... toolNames) {
            this.cancellable = cancellable;
            for (String name : toolNames) {
                starts.put(name, new CountDownLatch(1));
                releases.put(name, new CountDownLatch(1));
            }
        }

        @Override
        public ToolResult execute(String toolName, JsonNode input, ToolExecutionContext ctx) {
            starts.get(toolName).countDown();
            try {
                releases.get(toolName).await(10, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return ToolResult.error("interrupted mid-tool");
            }
            executedToCompletion.add(toolName);
            return ToolResult.success("ok");
        }

        @Override
        public InterruptBehavior interruptBehavior(String toolName) {
            return cancellable.contains(toolName) ? InterruptBehavior.CANCEL : InterruptBehavior.BLOCK;
        }
    }

    private static DefaultQuerySession newEngine(ToolExecutor executor) {
        return new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT).toolExecutor(executor).build());
    }

    private static ToolUseBlock tub(String id, String name) {
        return new ToolUseBlock(id, name, JsonUtils.getMapper().createObjectNode());
    }

    @Test
    void steerFlag_isFalseWhenIdle() {
        InterruptibleToolTracker tracker = new InterruptibleToolTracker();
        assertFalse(tracker.hasInterruptibleToolInProgress(),
            "no tool executing → the flag must be false");
    }

    @Test
    void steerFlag_requiresAtLeastOneExecutingTool() {
        InterruptibleToolTracker tracker = new InterruptibleToolTracker();
        tracker.onToolStarted(InterruptBehavior.BLOCK);
        tracker.onToolFinished(InterruptBehavior.BLOCK);
        assertFalse(tracker.hasInterruptibleToolInProgress(),
            "every executing tool finished → the flag must fall back to false");
    }

    @Test
    void steerFlag_trueOnlyWhenEveryExecutingToolIsCancellable() {
        InterruptibleToolTracker tracker = new InterruptibleToolTracker();
        tracker.onToolStarted(InterruptBehavior.CANCEL);
        assertTrue(tracker.hasInterruptibleToolInProgress(),
            "one executing CANCEL tool and nothing else → true");
        tracker.onToolStarted(InterruptBehavior.BLOCK);
        assertFalse(tracker.hasInterruptibleToolInProgress(),
            "a BLOCK tool joined the executing set → false");
        tracker.onToolFinished(InterruptBehavior.BLOCK);
        assertTrue(tracker.hasInterruptibleToolInProgress(),
            "the BLOCK tool finished, the CANCEL tool still runs → true again");
        tracker.onToolFinished(InterruptBehavior.CANCEL);
        assertFalse(tracker.hasInterruptibleToolInProgress());
    }

    @Test
    void concurrentRun_tracksSteerFlagAcrossBatchTransitions() throws Exception {
        GatedExecutor executor = new GatedExecutor(Set.of("WebFetch"), "WebFetch");
        DefaultQuerySession engine = newEngine(executor);
        InterruptibleToolTracker tracker = new InterruptibleToolTracker();
        AtomicReference<String> steerWhileRunning = new AtomicReference<>("unset");

        Thread runner = new Thread(() -> new ConcurrentToolRunner().run(
            List.of(tub("tu-1", "WebFetch")), engine, false, 1, _ -> {}, null, tracker));
        runner.start();
        assertTrue(executor.starts.get("WebFetch").await(5, TimeUnit.SECONDS),
            "the tool must have started");
        steerWhileRunning.set(Boolean.toString(tracker.hasInterruptibleToolInProgress()));

        executor.releases.get("WebFetch").countDown();
        runner.join(10_000);
        String afterFinish = Boolean.toString(tracker.hasInterruptibleToolInProgress());

        assertEquals("true", steerWhileRunning.get(),
            "a single executing CANCEL tool must raise the steer flag");
        assertEquals("false", afterFinish,
            "the flag must clear once the tool completes");
    }

    @Test
    void concurrentRun_blockToolHoldsTheSteerFlagDown() throws Exception {
        GatedExecutor executor = new GatedExecutor(Set.of(), "Bash");
        DefaultQuerySession engine = newEngine(executor);
        InterruptibleToolTracker tracker = new InterruptibleToolTracker();

        Thread runner = new Thread(() -> new ConcurrentToolRunner().run(
            List.of(tub("tu-1", "Bash")), engine, false, 1, _ -> {}, null, tracker));
        runner.start();
        assertTrue(executor.starts.get("Bash").await(5, TimeUnit.SECONDS));
        boolean whileRunning = tracker.hasInterruptibleToolInProgress();

        executor.releases.get("Bash").countDown();
        runner.join(10_000);

        assertFalse(whileRunning, "an executing BLOCK tool must keep the steer flag down");
    }
}

package com.claudecode.runtime.query;

import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolExecutor;
import com.claudecode.core.engine.ToolResult;

import com.claudecode.core.message.ProgressMessage;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.serialization.JsonUtils;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the field-for-field projection from a tool's {@link
 * ToolExecutionContext.ProgressUpdate} onto the streamed {@link ProgressMessage.ProgressData}.
 *
 * <p>The projection is positional — {@code ProgressData} carries eighteen
 * constructor arguments, several of them adjacent and type-compatible
 * ({@code totalLines}/{@code totalBytes} are both {@code Long}). A reordered
 * argument list would compile and silently mislabel the progress payload, so
 * each field is asserted by its wire name here rather than trusting the order.
 * The progress path has no other coverage: a minted update that never reaches
 * the stream, or one that arrives with fields shifted by one, is invisible to
 * every other runtime test.
 */
class ToolExecutionProgressSinkTest {

    private static final StreamingClient NOOP_CLIENT = new StreamingClient() {
        @Override
        public Iterator<StreamingEvent> createStream(StreamRequest request) {
            return Collections.emptyIterator();
        }
        @Override
        public String getModel() { return "test-model"; }
    };

    private static DefaultQuerySession newEngine(ToolExecutor executor,
                                                 ToolExecutionContext.ProgressSink sink) {
        return new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT).toolExecutor(executor).progressSink(sink).build());
    }

    private static ToolUseBlock readBlock() {
        return new ToolUseBlock("tu-1", "Read", JsonUtils.getMapper().createObjectNode());
    }

    /** A tool that mints exactly one structured progress update then succeeds. */
    private static ToolExecutor emitting(
            ToolExecutionContext.ProgressUpdate update,
            AtomicReference<ToolExecutionContext.ProgressUpdate> seen) {
        return (_, _, context) -> {
            context.progressSink().accept(update);
            if (seen != null) seen.set(update);
            return ToolResult.success("ok");
        };
    }

    private static ProgressMessage.ProgressData firstProgressData(List<SDKMessage> emitted) {
        return emitted.stream()
            .filter(SDKMessage.Progress.class::isInstance)
            .map(SDKMessage.Progress.class::cast)
            .map(progress -> progress.message().data())
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "no SDKMessage.Progress emitted; saw " + emitted));
    }

    @Test
    void progressUpdateIsProjectedOntoTheStreamedPayloadFieldForField() {
        ToolExecutionContext.ProgressUpdate update = ToolExecutionContext.ProgressUpdate.of(
                0.5, "halfway", "bash", "tu-1", null,
                "out", "full output", 10L, 100L, 1.5, 3_000L, false)
            .toBuilder().resolvedModel("claude-x").build();
        List<SDKMessage> emitted = new ArrayList<>();

        ToolExecution.execute(readBlock(), newEngine(emitting(update, null),
            ToolExecutionContext.ProgressSink.NOOP), null, emitted::add, "assistant-1");

        ProgressMessage.ProgressData data = firstProgressData(emitted);
        assertEquals("bash", data.type());
        assertEquals("out", data.output());
        assertEquals("full output", data.fullOutput());
        assertEquals(1.5, data.elapsedTimeSeconds());
        // Adjacent and both Long: a swap here compiles and is caught only by name.
        assertEquals(10L, data.totalLines());
        assertEquals(100L, data.totalBytes());
        assertEquals(3_000L, data.timeoutMs());
        // ToolExecutionContext.ProgressUpdate has no taskId field, so this slot stays null by design —
        // not because the projection forgot to map something.
        assertNull(data.taskId());
        // complete=false inverts to isIncomplete=true.
        assertEquals(Boolean.TRUE, data.isIncomplete());
        // The two records disagree on where status text lives: ProgressUpdate keeps
        // it in `message`, which ProgressData has no slot for, and ProgressData's
        // own `message` slot is the agent child Message — a different thing sharing
        // the name. So the "halfway" text is dropped by this projection, and the
        // string that lands in `prompt` is ProgressUpdate.prompt, which this
        // factory leaves null.
        assertNull(data.message());
        assertNull(data.prompt());
        assertNull(data.agentId());
        assertEquals("claude-x", data.resolvedModel());
    }

    /**
     * The agent_progress shape is the one case where {@code ProgressUpdate.prompt}
     * is actually populated, so it is the only path that can prove the projection
     * wires that slot through rather than leaving it permanently null.
     */
    @Test
    void agentPromptAndAgentIdSurviveTheProjection() {
        ToolExecutionContext.ProgressUpdate update = ToolExecutionContext.ProgressUpdate.agent(
            null, "summarize the repo", "agent-7", "claude-sonnet-x");
        List<SDKMessage> emitted = new ArrayList<>();

        ToolExecution.execute(readBlock(), newEngine(emitting(update, null),
            ToolExecutionContext.ProgressSink.NOOP), null, emitted::add, "assistant-1");

        ProgressMessage.ProgressData data = firstProgressData(emitted);
        assertEquals("agent_progress", data.type());
        assertEquals("summarize the repo", data.prompt());
        assertEquals("agent-7", data.agentId());
        assertEquals("claude-sonnet-x", data.resolvedModel());
    }

    @Test
    void completeProgressUpdateInvertsToIsIncompleteFalse() {
        ToolExecutionContext.ProgressUpdate update = ToolExecutionContext.ProgressUpdate.of(
                1.0, "done", "bash", "tu-1", null,
                "out", "full", 1L, 1L, 2.0, 1_000L, true);
        List<SDKMessage> emitted = new ArrayList<>();

        ToolExecution.execute(readBlock(), newEngine(emitting(update, null),
            ToolExecutionContext.ProgressSink.NOOP), null, emitted::add, "assistant-1");

        assertEquals(Boolean.FALSE, firstProgressData(emitted).isIncomplete());
    }

    /**
     * The engine-level sink is the status-line side channel. It must observe every
     * update — including ones that carry no structured dataType and are therefore
     * never streamed — stamped with the tool-use identity the tool itself does not
     * know ({@code ProgressUpdate.withIdentity(tub.id(), null)}).
     */
    @Test
    void engineSinkReceivesStampedUpdatesEvenWhenNothingIsStreamed() {
        AtomicReference<ToolExecutionContext.ProgressUpdate> received = new AtomicReference<>();
        // No dataType: structured emit is skipped, the side channel still fires.
        ToolExecutionContext.ProgressUpdate plain = ToolExecutionContext.ProgressUpdate.of(0.25, "working");
        List<SDKMessage> emitted = new ArrayList<>();

        ToolExecution.execute(readBlock(), newEngine(emitting(plain, null), received::set),
            null, emitted::add, "assistant-1");

        assertNotNull(received.get(), "the engine progress sink must observe the update");
        assertEquals("tu-1", received.get().toolUseId(),
            "the update must be stamped with the tool-use id it was minted for");
        assertNull(received.get().parentToolUseId());
        assertEquals("working", received.get().message());
        assertTrue(emitted.stream().noneMatch(SDKMessage.Progress.class::isInstance),
            "a dataType-less update must not become a streamed progress message");
    }

    /**
     * The status-line affordance hint ({@code agent_background_hint}) is UI-only:
     * it reaches the side channel but must never be streamed to the model.
     */
    @Test
    void uiAffordanceOnlyUpdateNeverReachesTheStream() {
        AtomicReference<ToolExecutionContext.ProgressUpdate> received = new AtomicReference<>();
        List<SDKMessage> emitted = new ArrayList<>();
        ToolExecutionContext.ProgressUpdate hint =
            ToolExecutionContext.ProgressUpdate.agentBackgroundHint();

        ToolExecution.execute(readBlock(), newEngine(emitting(hint, null), received::set),
            null, emitted::add, "assistant-1");

        assertNotNull(received.get(), "the UI-only hint still drives the status line");
        assertTrue(emitted.stream().noneMatch(SDKMessage.Progress.class::isInstance),
            "a uiAffordanceOnly update must not be streamed");
    }

    /** A null emit consumer must not suppress the side channel, nor NPE. */
    @Test
    void nullEmitStillDrivesTheSideChannel() {
        AtomicReference<ToolExecutionContext.ProgressUpdate> received = new AtomicReference<>();
        ToolExecutionContext.ProgressUpdate update = ToolExecutionContext.ProgressUpdate.of(
            0.5, "halfway", "bash", "tu-1", null,
            "out", "full", 1L, 1L, 1.0, 500L, false);

        ToolExecution.ToolStep step = ToolExecution.execute(
            readBlock(), newEngine(emitting(update, null), received::set), null, null,
            "assistant-1");

        assertNotNull(received.get(), "side channel fires with no stream consumer");
        assertFalse(step.error());
        ToolResultBlock block = assertInstanceOf(
            ToolResultBlock.class, step.resultMsg().message().blocks().getFirst());
        assertInstanceOf(TextBlock.class, block.content().getFirst());
    }
}
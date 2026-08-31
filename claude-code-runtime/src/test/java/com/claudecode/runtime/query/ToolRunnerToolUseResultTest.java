package com.claudecode.runtime.query;

import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.SessionCostState;
import com.claudecode.core.engine.ToolExecutor;
import com.claudecode.core.engine.ToolResult;

import com.claudecode.core.message.*;
import com.claudecode.core.serialization.JsonUtils;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;


class ToolRunnerToolUseResultTest {

    private static final StreamingClient NOOP_CLIENT = new StreamingClient() {
        @Override
        public Iterator<StreamingEvent> createStream(StreamRequest request) {
            return Collections.emptyIterator();
        }
        @Override
        public String getModel() { return "test-model"; }
    };

    private static DefaultQuerySession newEngine(ToolExecutor executor) {
        return new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT).toolExecutor(executor).build());
    }

    private static UserMessage runAndGetToolResultMessage(DefaultQuerySession engine) {
        ToolUseBlock tub = new ToolUseBlock("tu-1", "Edit", JsonUtils.getMapper().createObjectNode());
        new ConcurrentToolRunner().run(List.of(tub), engine, false, 1, _ -> {});
        Object last = engine.getMutableMessages().getLast();
        return assertInstanceOf(UserMessage.class, last);
    }

    @Test
    void structuredPayloadRidesOnToolResultUserMessage() {
        Object payload = new Object();
        DefaultQuerySession engine = newEngine((_, _, _) ->
            ToolResult.success("ok").withToolUseResult(payload));

        UserMessage msg = runAndGetToolResultMessage(engine);

        assertSame(payload, msg.toolUseResult());
    }

    @Test
    void acceptFeedbackPathAlsoCarriesToolUseResult() {
        Object payload = new Object();
        DefaultQuerySession engine = newEngine((_, _, _) ->
            ToolResult.success("ok").withToolUseResult(payload).withAcceptFeedback("looks good"));

        ToolUseBlock tub = new ToolUseBlock("tu-1", "Edit", JsonUtils.getMapper().createObjectNode());
        new ConcurrentToolRunner().run(List.of(tub), engine, false, 1, _ -> {});

        UserMessage toolResultMsg = engine.getMutableMessages().stream()
            .filter(UserMessage.class::isInstance)
            .map(UserMessage.class::cast)
            .filter(m -> m.toolUseResult() != null)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no user message carried toolUseResult"));
        assertSame(payload, toolResultMsg.toolUseResult());
    }

    @Test
    void permissionFeedbackImagesShareTheToolResultUserMessage() {
        var source = JsonUtils.getMapper().createObjectNode()
            .put("type", "base64").put("media_type", "image/png").put("data", "aW1hZ2U=");
        DefaultQuerySession engine = newEngine((_, _, _) ->
            ToolResult.error("rejected").withUserFeedbackBlocks(List.of(new ImageBlock(source))));

        UserMessage msg = runAndGetToolResultMessage(engine);

        assertEquals(2, msg.message().blocks().size());
        assertInstanceOf(ToolResultBlock.class, msg.message().blocks().getFirst());
        assertInstanceOf(ImageBlock.class, msg.message().blocks().get(1));
    }

    @Test
    void plainToolResultLeavesToolUseResultNull() {
        DefaultQuerySession engine = newEngine((_, _, _) -> ToolResult.success("ok"));

        UserMessage msg = runAndGetToolResultMessage(engine);

        assertNull(msg.toolUseResult());
    }

    @Test
    void toolResultIsRecordedToTranscriptWithStructuredPayload() {
        Object payload = new Object();
        DefaultQuerySession engine = newEngine((_, _, _) ->
            ToolResult.success("ok").withToolUseResult(payload));
        List<Message> transcript = new ArrayList<>();
        engine.setTranscriptSink((_, message) -> transcript.add(message));

        ToolUseBlock tub = new ToolUseBlock("tu-1", "Edit", JsonUtils.getMapper().createObjectNode());
        new ConcurrentToolRunner().run(List.of(tub), engine, false, 1, _ -> {}, "assistant-1");

        UserMessage persisted = assertInstanceOf(UserMessage.class, transcript.getFirst());
        assertSame(payload, persisted.toolUseResult());
        assertEquals("assistant-1", persisted.sourceToolAssistantUUID());
    }

    @Test
    void plainToolResultIsAlsoRecordedToTranscript() {
        DefaultQuerySession engine = newEngine((_, _, _) -> ToolResult.success("ok"));
        List<Message> transcript = new ArrayList<>();
        engine.setTranscriptSink((_, message) -> transcript.add(message));

        runAndGetToolResultMessage(engine);

        assertEquals(1, transcript.size(),
            "tool results without structured payload still belong in replayable JSONL");
        assertInstanceOf(UserMessage.class, transcript.getFirst());
    }

    @Test
    void explicitFalseErrorFieldSurvivesTheNormalNoFeedbackExecutionPath() {
        DefaultQuerySession engine = newEngine((_, _, _) ->
            ToolResult.success("ready: true").withExplicitIsErrorField());

        UserMessage message = runAndGetToolResultMessage(engine);

        ToolResultBlock block = assertInstanceOf(
            ToolResultBlock.class, message.message().blocks().getFirst());
        assertFalse(block.isError());
        assertTrue(block.includeIsErrorField());
    }

    @Test
    void postEmitCallbackRunsAfterTheToolResultSdkUser() {
        List<String> order = new ArrayList<>();
        DefaultQuerySession engine = newEngine((_, _, _) ->
            ToolResult.success("ok").withAfterResultEmitted(() -> order.add("after")));
        ToolUseBlock tub = new ToolUseBlock(
            "tu-1", "Edit", JsonUtils.getMapper().createObjectNode());

        new ConcurrentToolRunner().run(List.of(tub), engine, false, 1, sdk -> {
            if (sdk instanceof SDKMessage.User) {
                order.add("emit");
            }
        });

        assertEquals(List.of("emit", "after"), order);
    }

    @Test
    void actualToolCallContributesToReleasedCumulativeToolDuration() throws Exception {
        SessionCostState.get().reset();
        try {
            DefaultQuerySession engine = newEngine((_, _, _) -> {
                LockSupport.parkNanos(8_000_000L);
                return ToolResult.success("ok");
            });

            runAndGetToolResultMessage(engine);

            assertTrue(SessionCostState.get().toolDurationMs() >= 1,
                "the tool clock must wrap executor.execute, not only the outer turn");
        } finally {
            SessionCostState.get().reset();
        }
    }

    @Test
    void executorReportedToolDurationDoesNotAlsoCountOuterPermissionOrWrapperTime() {
        SessionCostState.get().reset();
        try {
            DefaultQuerySession engine = newEngine((_, _, context) -> {
                context.toolDurationTiming().recordElapsed(7L);
                LockSupport.parkNanos(20_000_000L);
                return ToolResult.success("ok");
            });

            runAndGetToolResultMessage(engine);

            assertEquals(7L, SessionCostState.get().toolDurationMs(),
                "an executor that reports the real call boundary must suppress the outer fallback timer");
        } finally {
            SessionCostState.get().reset();
        }
    }
}

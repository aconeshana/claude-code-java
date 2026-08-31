package com.claudecode.services.model;

import com.claudecode.api.*;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.engine.SessionCostState;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolUseBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Guards the small-fast model resolution used by Claude Code.
 */
class SideQueryTest {

    @AfterEach
    void resetCostState() {
        SessionCostState.get().reset();
    }

    @Test
    void customMainModelIsUsedWhenNoSmallFastOverrideExists() {
        assertEquals("glm-5.2",
            SideQuery.resolveSmallFastModel("glm-5.2", null, null));
    }

    @Test
    void claudeMainModelStillUsesTheDefaultHaikuHelper() {
        assertEquals(SideQuery.DEFAULT_HAIKU_MODEL,
            SideQuery.resolveSmallFastModel("claude-sonnet-4-6", null, null));
    }

    @Test
    void explicitSmallFastAndDefaultHaikuOverridesKeepTheirPrecedence() {
        assertEquals("gateway-small",
            SideQuery.resolveSmallFastModel("glm-5.2", "gateway-small", "gateway-haiku"));
        assertEquals("gateway-haiku",
            SideQuery.resolveSmallFastModel("glm-5.2", null, "gateway-haiku"));
    }

    @Test
    void helperRequestsCarryBackgroundQuerySources() {
        CapturingClient client = new CapturingClient();
        SideQuery sideQuery = new SideQuery(client);

        sideQuery.queryText("model", "system", "prompt", 32);
        assertEquals("side_query", client.request.querySource());

        sideQuery.queryText("model", "system", "prompt", 32, 1_000L, "hook_prompt");
        assertEquals("hook_prompt", client.request.querySource());
    }

    @Test
    void throwingEntryPointPreservesPromptTooLongException() {
        PromptTooLongException expected = new PromptTooLongException(
            "Prompt is too long", 400, "invalid_request_error", null);
        SideQuery sideQuery = new SideQuery(new ThrowingClient(expected));

        PromptTooLongException actual = assertThrows(PromptTooLongException.class,
            () -> sideQuery.queryTextOrThrow(new SideQuery.Request()
                .model("model")
                .systemPrompt("system")
                .userPrompt("prompt")));

        assertEquals(expected, actual);
    }

    @Test
    void streamingTextQueryCarriesMetadataAndCollectsTextDeltas() {
        CapturingClient client = new CapturingClient();
        SideQuery sideQuery = new SideQuery(client);
        var metadata = JsonUtils.getMapper().createObjectNode().put("user_id", "wire-user");

        String text = sideQuery.queryTextOrThrow(new SideQuery.Request()
            .model("claude-sonnet-4-6")
            .systemPrompt("system")
            .userPrompt("prompt")
            .maxTokens(32_000)
            .metadata(metadata)
            .streaming(true));

        assertEquals("ok", text);
        assertTrue(client.request.stream());
        assertEquals(metadata, client.request.metadata());
        assertEquals(1, SessionCostState.get().usageByModel()
            .get("claude-sonnet-4-6").inputTokens());
        assertEquals(2, SessionCostState.get().usageByModel()
            .get("claude-sonnet-4-6").outputTokens());
    }

    @Test
    void haikuUsesReleasedStreamingPresetAndReturnsTheCompleteAssistantMessage() {
        CapturingClient client = new CapturingClient();
        SideQuery sideQuery = new SideQuery(client);

        ApiMessage message = sideQuery.queryHaikuMessage("system", "prompt");

        assertNotNull(message);
        assertEquals("ok", ((TextBlock) message.content().getFirst()).text());
        assertTrue(client.request.stream());
        assertEquals(32_000, client.request.maxTokens());
        assertEquals(List.of(), client.request.tools());
        assertEquals(CreateMessageRequest.ThinkingConfig.disabled(), client.request.thinking());
        assertFalse(client.request.promptCachingEnabled());
    }

    @Test
    void streamingCollectorReconstructsTextAndToolUseBlocks() {
        CompleteMessageClient client = new CompleteMessageClient();
        SideQuery sideQuery = new SideQuery(client);

        ApiMessage message = sideQuery.queryStreamingMessageOrThrow(new SideQuery.Request()
            .model("claude-haiku-4-5")
            .userPrompt("prompt")
            .maxTokens(32_000)
            .streaming(true));

        assertEquals("msg_stream", message.id());
        assertEquals("tool_use", message.stopReason());
        assertEquals(new TextBlock("hello"), message.content().get(0));
        ToolUseBlock tool = (ToolUseBlock) message.content().get(1);
        assertEquals("tool-1", tool.id());
        assertEquals("Lookup", tool.name());
        assertEquals("java", tool.input().get("query").asText());
        assertEquals(3, message.usage().outputTokens());
    }

    @Test
    void streamingCollectorRejectsAStreamWithoutAnAssistantMessage() {
        SideQuery sideQuery = new SideQuery(new EmptyStreamClient());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> sideQuery.queryStreamingMessageOrThrow(new SideQuery.Request()
                .model("claude-haiku-4-5")
                .userPrompt("prompt")
                .streaming(true)));

        assertEquals("No assistant message found", failure.getMessage());
    }

    @Test
    void explicitEmptyToolsArePreservedForReleasedEvaluatorRequests() {
        CapturingClient client = new CapturingClient();
        SideQuery sideQuery = new SideQuery(client);

        sideQuery.queryTextOrThrow(new SideQuery.Request()
            .model("claude-sonnet-4-6")
            .systemPrompt("system")
            .userPrompt("prompt")
            .tools(List.of())
            .streaming(true));

        assertEquals(List.of(), client.request.tools());
    }

    @Test
    void nonStreamingHelperSeparatesRetryChainFromFinalAttemptDuration() {
        SideQuery sideQuery = new SideQuery(new TimedRetryClient());

        sideQuery.queryText("claude-sonnet-4-6", "system", "prompt", 32);

        assertTrue(SessionCostState.get().apiDurationMs() >= 60,
            "the full helper request must include the simulated retry wait");
        assertTrue(SessionCostState.get().apiDurationWithoutRetriesMs() >= 10);
        assertTrue(SessionCostState.get().apiDurationMs()
                - SessionCostState.get().apiDurationWithoutRetriesMs() >= 30,
            "the no-retry clock must exclude the simulated retry wait");
    }

    private static final class CapturingClient implements LlmClient {
        private CreateMessageRequest request;

        @Override
        public Iterator<StreamEvent> createMessageStream(CreateMessageRequest request) {
            this.request = request;
            return List.<StreamEvent>of(
                new StreamEvent.MessageStart(ApiMessage.builder()
                    .id("msg_helper").model(request.model()).usage(new Usage(1, 0, 0, 0)).build()),
                new StreamEvent.ContentBlockStart(0, new TextBlock("")),
                new StreamEvent.ContentBlockDelta(0, new Delta.TextDelta("o")),
                new StreamEvent.ContentBlockDelta(0, new Delta.TextDelta("k")),
                new StreamEvent.ContentBlockStop(0),
                new StreamEvent.MessageDelta(
                    new MessageDeltaData("end_turn", null),
                    new Usage(1, 2, 0, 0)),
                new StreamEvent.MessageStop()).iterator();
        }

        @Override
        public ApiMessage createMessage(CreateMessageRequest request) {
            this.request = request;
            return ApiMessage.stub(request.model(), "ok");
        }

        @Override
        public ApiMessage createMessage(CreateMessageRequest request, long timeoutMillis) {
            this.request = request;
            return ApiMessage.stub(request.model(), "ok");
        }

        @Override
        public String getModel() {
            return "model";
        }
    }

    private static final class CompleteMessageClient implements LlmClient {
        @Override
        public Iterator<StreamEvent> createMessageStream(CreateMessageRequest request) {
            return List.<StreamEvent>of(
                new StreamEvent.MessageStart(ApiMessage.builder()
                    .id("msg_stream").model(request.model()).usage(new Usage(2, 0, 0, 0)).build()),
                new StreamEvent.ContentBlockStart(0, new TextBlock("")),
                new StreamEvent.ContentBlockDelta(0, new Delta.TextDelta("hello")),
                new StreamEvent.ContentBlockStop(0),
                new StreamEvent.ContentBlockStart(1, new ToolUseBlock(
                    "tool-1", "Lookup", JsonUtils.getMapper().createObjectNode())),
                new StreamEvent.ContentBlockDelta(1,
                    new Delta.InputJsonDelta("{\"query\":\"java\"}")),
                new StreamEvent.ContentBlockStop(1),
                new StreamEvent.MessageDelta(
                    new MessageDeltaData("tool_use", null),
                    new Usage(2, 3, 0, 0)),
                new StreamEvent.MessageStop()).iterator();
        }

        @Override public ApiMessage createMessage(CreateMessageRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override public String getModel() { return "claude-haiku-4-5"; }
    }

    private static final class EmptyStreamClient implements LlmClient {
        @Override public Iterator<StreamEvent> createMessageStream(CreateMessageRequest request) {
            return List.<StreamEvent>of(new StreamEvent.Ping()).iterator();
        }

        @Override public ApiMessage createMessage(CreateMessageRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override public String getModel() { return "claude-haiku-4-5"; }
    }

    private record ThrowingClient(RuntimeException failure) implements LlmClient {
        @Override public Iterator<StreamEvent> createMessageStream(CreateMessageRequest request) {
            throw failure;
        }

        @Override public ApiMessage createMessage(CreateMessageRequest request) {
            throw failure;
        }

        @Override public ApiMessage createMessage(CreateMessageRequest request, long timeoutMillis) {
            throw failure;
        }

        @Override public String getModel() { return "model"; }
    }

    private static final class TimedRetryClient implements LlmClient {
        @Override public Iterator<StreamEvent> createMessageStream(CreateMessageRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override public ApiMessage createMessage(CreateMessageRequest request) {
            sleep(55);
            long finalAttemptStartMs = System.currentTimeMillis();
            sleep(20);
            return ApiMessageTiming.attach(
                ApiMessage.stub(request.model(), "ok"), finalAttemptStartMs);
        }

        @Override public String getModel() { return "model"; }

        private static void sleep(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
        }
    }
}

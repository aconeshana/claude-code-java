package com.claudecode.services.compact;

import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.api.ApiException;
import com.claudecode.api.ApiMessage;
import com.claudecode.api.CreateMessageRequest;
import com.claudecode.api.LlmClient;
import com.claudecode.api.StreamEvent;
import com.claudecode.runtime.query.DefaultQuerySession;
import com.claudecode.runtime.query.QuerySessionSpec;
import com.claudecode.core.engine.SessionIdentity;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolExecutor;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for the P0 production gap this class fixes: before it
 * existed, {@code ClaudeCodeCli} wired {@code CompactService} with a
 * {@code null} summarizer, so every manual {@code /compact} threw
 * "No CompactSummarizer configured" — the only prior implementations of
 * {@link CompactSummarizer} were test doubles.
 */
class LlmCompactSummarizerTest {

    /** Captures the cache-sharing fork request and returns a streamed text response. */
    private static class FakeStreamingClient implements StreamingClient {
        StreamRequest lastRequest;
        Usage messageStartUsage = new Usage(500, 0, 50, 20);
        Usage messageDeltaUsage = new Usage(0, 100, 0, 0);

        @Override
        public Iterator<StreamingEvent> createStream(StreamRequest request) {
            lastRequest = request;
            return List.<StreamingEvent>of(
                new StreamingEvent.MessageStartEvent(
                    "msg-compact", request.model(), List.of(), messageStartUsage),
                new StreamingEvent.ContentBlockStartEvent(0, "text", null, null),
                new StreamingEvent.ContentBlockDeltaEvent(0, "text_delta", "the "),
                new StreamingEvent.ContentBlockDeltaEvent(0, "text_delta", "summary"),
                new StreamingEvent.ContentBlockStopEvent(0),
                new StreamingEvent.MessageDeltaEvent("end_turn", messageDeltaUsage),
                new StreamingEvent.MessageStopEvent()
            ).iterator();
        }

        @Override
        public String getModel() {
            return "fake";
        }
    }

    private static final class OneToolExecutor implements ToolExecutor {
        @Override
        public ToolResult execute(String toolName, JsonNode input,
                                  ToolExecutionContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<StreamingClient.StreamRequest.ToolDef> getToolDefinitions() {
            return List.of(new StreamingClient.StreamRequest.ToolDef(
                "Read", "Read a file", new ObjectMapper().createObjectNode()));
        }
    }

    /** Captures the last request sent and returns a canned response. */
    private static final class FakeLlmClient implements LlmClient {
        CreateMessageRequest lastRequest;
        ApiMessage response;
        String modelSeenAtCallTime;

        @Override
        public Iterator<StreamEvent> createMessageStream(CreateMessageRequest request) {
            throw new UnsupportedOperationException("LlmCompactSummarizer must use non-streaming createMessage");
        }

        @Override
        public ApiMessage createMessage(CreateMessageRequest request) {
            lastRequest = request;
            modelSeenAtCallTime = request.model();
            return response;
        }

        @Override
        public String getModel() { return "fake"; }
    }

    private static ApiMessage responseWithText(String text, Usage usage) {
        return ApiMessage.builder()
            .content(List.of(new TextBlock(text)))
            .usage(usage)
            .build();
    }

    @Test
    void cacheSharingForkReusesMainRequestContractAndStreamsSummary() {
        FakeStreamingClient client = new FakeStreamingClient();
        QuerySessionSpec config = QuerySessionSpec.builder()
            .llmClient(client)
            .model("glm-5.2")
            .systemPrompt("full-main-system-prompt")
            .maxTokens(32_000)
            .toolExecutor(new OneToolExecutor())
            .tools(List.of("Read"))
            .claudeMdContentSupplier(() -> "PROJECT MEMORY")
            .sessionIdentity(SessionIdentity.of("session-197"))
            .build();
        config.setThinkingEnabled(true);
        config.setEffortValue("xhigh");
        DefaultQuerySession engine = new DefaultQuerySession(config);
        LlmCompactSummarizer summarizer = new LlmCompactSummarizer(client, () -> engine);

        List<Message> messages = List.of(
            new UserMessage("u1", MessageContent.ofText("hello"))
        );
        CompactSummarizer.SummaryResult result =
            summarizer.summarizeWithUsage(messages, "COMPACT PROMPT");

        assertEquals("the summary", result.text());
        assertEquals(new Usage(500, 100, 50, 20), result.usage());
        StreamingClient.StreamRequest request = client.lastRequest;
        assertNotNull(request);
        assertTrue(request.stream(), "official cache-sharing compact is streamed");
        assertEquals(32_000, request.maxTokens(), "must keep the main-loop max_tokens");
        assertEquals(engine.fetchSystemPromptParts(), request.systemPrompt(),
            "compact must reuse the same fully assembled system prompt as the main loop");
        assertEquals(List.of("Read"), request.tools().stream().map(
            StreamingClient.StreamRequest.ToolDef::name).toList());
        assertTrue(request.thinkingEnabled(), "thinking config is part of the cache key");
        assertEquals("xhigh", request.effort());
        assertEquals("session-197", request.sessionId());
        assertTrue(request.skipCacheWrite(),
            "cache-sharing compact forks must move the cache marker to the shared prefix");
        assertEquals(1, request.messages().size(),
            "CLAUDE.md context, the real user turn, and compact prompt merge into one user turn");
        Object content = request.messages().getFirst().content();
        assertInstanceOf(List.class, content);
        String wire = content.toString();
        assertTrue(Strings.CS.contains(wire, "PROJECT MEMORY"));
        assertTrue(Strings.CS.contains(wire, "hello"));
        assertTrue(Strings.CS.contains(wire, "COMPACT PROMPT"));
    }

    @Test
    void cumulativeStreamUsageDoesNotDoubleCountRepeatedOutputSnapshot() {
        FakeStreamingClient client = new FakeStreamingClient();
        client.messageStartUsage = new Usage(500, 1, 50, 20);
        client.messageDeltaUsage = new Usage(0, 1, 0, 0);
        QuerySessionSpec config = QuerySessionSpec.builder()
            .llmClient(client)
            .model("glm-5.2")
            .maxTokens(32_000)
            .build();
        DefaultQuerySession engine = new DefaultQuerySession(config);

        CompactSummarizer.SummaryResult result =
            new LlmCompactSummarizer(client, () -> engine)
                .summarizeWithUsage(List.of(), "COMPACT PROMPT");

        assertEquals(new Usage(500, 1, 50, 20), result.usage());
    }

    @Test
    void cacheSharingStreamWithoutAnyContentBlockReportsReleased197NoAssistantDetail() {
        FakeStreamingClient client = new FakeStreamingClient() {
            @Override
            public Iterator<StreamingEvent> createStream(StreamRequest request) {
                lastRequest = request;
                return List.<StreamingEvent>of(
                    new StreamingEvent.MessageStartEvent(
                        "msg-empty", request.model(), List.of(), new Usage(1, 0, 0, 0)),
                    new StreamingEvent.MessageDeltaEvent("end_turn", Usage.EMPTY),
                    new StreamingEvent.MessageStopEvent()
                ).iterator();
            }
        };
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(client)
            .model("claude-sonnet-4-6")
            .maxTokens(32_000)
            .build());

        CompactException failure = assertThrows(CompactException.class,
            () -> new LlmCompactSummarizer(client, () -> engine)
                .summarizeWithUsage(List.of(), "COMPACT PROMPT"));

        assertEquals("no assistant message in summarization response", failure.getMessage());
        assertEquals(new Usage(1, 0, 0, 0), failure.compactionUsage());
    }

    @Test
    void cacheSharingApiErrorUsesReleased197CompactErrorText() {
        FakeStreamingClient client = new FakeStreamingClient() {
            @Override
            public Iterator<StreamingEvent> createStream(StreamRequest request) {
                lastRequest = request;
                return List.<StreamingEvent>of(new StreamingEvent.ErrorEvent(
                    new ApiException(
                        "API request failed: {\"type\":\"error\",\"error\":{"
                            + "\"type\":\"invalid_request_error\","
                            + "\"message\":\"Deterministic compact API error\"}}",
                        400)))
                    .iterator();
            }
        };
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(client)
            .model("claude-sonnet-4-6")
            .maxTokens(32_000)
            .build());

        ApiException failure = assertThrows(ApiException.class,
            () -> new LlmCompactSummarizer(client, () -> engine)
                .summarizeWithUsage(List.of(), "COMPACT PROMPT"));

        assertEquals("API Error: 400 Deterministic compact API error", failure.getMessage());
    }

    @Test
    void cacheSharingMediaSizeErrorPreservesRawBodyForReleasedRetryPlaceholder() {
        String rawMessage =
            "API request failed: {\"type\":\"error\",\"error\":{"
                + "\"type\":\"invalid_request_error\","
                + "\"message\":\"image exceeds 5 MB maximum\"}}";
        FakeStreamingClient client = new FakeStreamingClient() {
            @Override
            public Iterator<StreamingEvent> createStream(StreamRequest request) {
                lastRequest = request;
                return List.<StreamingEvent>of(new StreamingEvent.ErrorEvent(
                    new ApiException(rawMessage, 400)))
                    .iterator();
            }
        };
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(client)
            .model("claude-sonnet-4-6")
            .maxTokens(32_000)
            .build());

        ApiException failure = assertThrows(ApiException.class,
            () -> new LlmCompactSummarizer(client, () -> engine)
                .summarizeWithUsage(List.of(), "COMPACT PROMPT"));

        assertEquals(rawMessage, failure.getMessage(),
            "reactive media stripping needs the SDK-style body, not the generic compact error text");
    }

    @Test
    void sendsConversationHistoryPlusCompactPromptAsFinalUserTurn() {
        FakeLlmClient client = new FakeLlmClient();
        client.response = responseWithText("the summary", Usage.EMPTY);
        LlmCompactSummarizer summarizer = new LlmCompactSummarizer(client, () -> "claude-sonnet-5");

        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("hello")));

        summarizer.summarize(messages, "Please summarize.");

        List<CreateMessageRequest.RequestMessage> sent = client.lastRequest.messages();
        assertEquals(2, sent.size(), "history (1 turn) + trailing compact-prompt turn");
        assertEquals("user", sent.getFirst().role());
        assertEquals("hello", sent.getFirst().content());
        assertEquals("user", sent.get(1).role());
        assertEquals("Please summarize.", sent.get(1).content());
    }

    @Test
    void returnsRealApiUsage() {
        FakeLlmClient client = new FakeLlmClient();
        Usage usage = new Usage(500, 100, 50, 20);
        client.response = responseWithText("the summary", usage);
        LlmCompactSummarizer summarizer = new LlmCompactSummarizer(client, () -> "claude-sonnet-5");

        CompactSummarizer.SummaryResult result = summarizer.summarizeWithUsage(List.of(), "Please summarize.");

        assertEquals("the summary", result.text());
        assertEquals(usage, result.usage());
    }

    @Test
    void concatenatesMultipleTextBlocks() {
        FakeLlmClient client = new FakeLlmClient();
        client.response = ApiMessage.builder()
            .content(List.of(new TextBlock("part one "), new TextBlock("part two")))
            .usage(Usage.EMPTY)
            .build();
        LlmCompactSummarizer summarizer = new LlmCompactSummarizer(client, () -> "claude-sonnet-5");

        String text = summarizer.summarize(List.of(), "prompt");

        // MessageConstants.getAssistantMessageText joins with "\n" and trims,

        assertEquals("part one \npart two", text);
    }

    @Test
    void resolvesModelAtCallTimeNotConstructionTime() {
        FakeLlmClient client = new FakeLlmClient();
        client.response = responseWithText("summary", Usage.EMPTY);
        String[] currentModel = {"claude-sonnet-5"};
        LlmCompactSummarizer summarizer = new LlmCompactSummarizer(client, () -> currentModel[0]);

        currentModel[0] = "claude-opus-4-8"; // simulate a /model switch before /compact runs
        summarizer.summarize(List.of(), "prompt");

        assertEquals("claude-opus-4-8", client.modelSeenAtCallTime);
    }

    @Test
    void promptTooLongApiErrorBecomesPtlMarkerText() {

        // assistant message prefixed 'Prompt is too long' that the retry loop
        // matches on. The summarizer must translate the exception into that
        // marker or the PTL head-truncation retry can never fire.
        LlmClient client = new LlmClient() {
            @Override public Iterator<StreamEvent> createMessageStream(CreateMessageRequest r) {
                throw new UnsupportedOperationException();
            }
            @Override public ApiMessage createMessage(CreateMessageRequest r) {
                throw new ApiException(
                    "API request failed: {\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                    + "\"message\":\"prompt is too long: 210000 tokens > 200000 maximum\"}}", 400);
            }
            @Override public String getModel() { return "fake"; }
        };
        LlmCompactSummarizer summarizer = new LlmCompactSummarizer(client, () -> "claude-sonnet-5");

        CompactSummarizer.SummaryResult result = summarizer.summarizeWithUsage(List.of(), "prompt");

        assertTrue(Strings.CS.startsWith(result.text(), CompactService.PROMPT_TOO_LONG_MARKER),
            "PTL API error must surface as the marker text; got: " + result.text());
    }

    @Test
    void genericGatewayContextLengthOverflowBecomesPtlMarkerTextToo() {

        // "anthropic" protocol adapter) reject overflow with "...exceeds the
        // model's maximum context length..." rather than Anthropic's "prompt is
        // too long". Without this, /compact hard-fails instead of retrying.
        LlmClient client = new LlmClient() {
            @Override public Iterator<StreamEvent> createMessageStream(CreateMessageRequest r) {
                throw new UnsupportedOperationException();
            }
            @Override public ApiMessage createMessage(CreateMessageRequest r) {
                throw new ApiException(
                    "API request failed: {\"object\":\"error\",\"message\":\"Requested token count "
                    + "exceeds the model's maximum context length of 131072 tokens. You requested "
                    + "a total of 135143 tokens: 103143 tokens from the input messages and 32000 "
                    + "tokens for the completion.\",\"type\":\"BadRequestError\"}", 400);
            }
            @Override public String getModel() { return "fake"; }
        };
        LlmCompactSummarizer summarizer = new LlmCompactSummarizer(client, () -> "deepseek-v4-flash");

        CompactSummarizer.SummaryResult result = summarizer.summarizeWithUsage(List.of(), "prompt");

        assertTrue(Strings.CS.startsWith(result.text(), CompactService.PROMPT_TOO_LONG_MARKER),
            "gateway context-length overflow must surface as the marker text; got: " + result.text());
    }

    @Test
    void otherApiErrorsPropagate() {
        LlmClient client = new LlmClient() {
            @Override public Iterator<StreamEvent> createMessageStream(CreateMessageRequest r) {
                throw new UnsupportedOperationException();
            }
            @Override public ApiMessage createMessage(CreateMessageRequest r) {
                throw new ApiException("API request failed: authentication_error", 401);
            }
            @Override public String getModel() { return "fake"; }
        };
        LlmCompactSummarizer summarizer = new LlmCompactSummarizer(client, () -> "claude-sonnet-5");

        assertThrows(ApiException.class,
            () -> summarizer.summarizeWithUsage(List.of(), "prompt"));
    }

    @Test
    void nullResponseYieldsNullTextAndEmptyUsage() {
        FakeLlmClient client = new FakeLlmClient();
        client.response = null;
        LlmCompactSummarizer summarizer = new LlmCompactSummarizer(client, () -> "claude-sonnet-5");

        CompactSummarizer.SummaryResult result = summarizer.summarizeWithUsage(List.of(), "prompt");

        assertNull(result.text());
        assertEquals(Usage.EMPTY, result.usage());
    }
}

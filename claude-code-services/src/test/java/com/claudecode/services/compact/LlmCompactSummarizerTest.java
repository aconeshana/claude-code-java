package com.claudecode.services.compact;

import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.api.ApiException;
import com.claudecode.runtime.query.DefaultQuerySession;
import com.claudecode.runtime.query.QuerySessionSpec;
import com.claudecode.core.engine.SessionIdentity;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolExecutor;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Iterator;
import java.util.List;
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

    /**
     * The history/prompt/usage/text-join assertions that used to live here on a
     * non-streaming, tool-less path are covered by the fork tests above:
     * {@code cacheSharingForkReusesMainRequestContractAndStreamsSummary} (wire
     * shape and joined text) and
     * {@code cumulativeStreamUsageDoesNotDoubleCountRepeatedOutputSnapshot}
     * (usage). An empty response is not a "null summary" on the fork path — it
     * throws, see
     * {@code cacheSharingStreamWithoutAnyContentBlockReportsReleased197NoAssistantDetail}.
     */
    @Test
    void resolvesModelAtCallTimeNotConstructionTime() {
        FakeStreamingClient client = new FakeStreamingClient();
        QuerySessionSpec config = QuerySessionSpec.builder()
            .llmClient(client)
            .model("claude-sonnet-5")
            .maxTokens(32_000)
            .build();
        DefaultQuerySession engine = new DefaultQuerySession(config);
        LlmCompactSummarizer summarizer = new LlmCompactSummarizer(client, () -> engine);

        config.setUserSpecifiedModel("claude-opus-4-8"); // a /model switch before /compact runs
        summarizer.summarize(List.of(), "prompt");

        assertEquals("claude-opus-4-8", client.lastRequest.model());
    }

    @Test
    void promptTooLongApiErrorBecomesPtlMarkerText() {

        // assistant message prefixed 'Prompt is too long' that the retry loop
        // matches on. The summarizer must translate the exception into that
        // marker or the PTL head-truncation retry can never fire.
        LlmCompactSummarizer summarizer = forkSummarizerFailingWith(new ApiException(
            "API request failed: {\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
            + "\"message\":\"prompt is too long: 210000 tokens > 200000 maximum\"}}", 400));

        CompactSummarizer.SummaryResult result = summarizer.summarizeWithUsage(List.of(), "prompt");

        assertTrue(Strings.CS.startsWith(result.text(), CompactService.PROMPT_TOO_LONG_MARKER),
            "PTL API error must surface as the marker text; got: " + result.text());
    }

    @Test
    void genericGatewayContextLengthOverflowBecomesPtlMarkerTextToo() {

        // "anthropic" protocol adapter) reject overflow with "...exceeds the
        // model's maximum context length..." rather than Anthropic's "prompt is
        // too long". Without this, /compact hard-fails instead of retrying.
        LlmCompactSummarizer summarizer = forkSummarizerFailingWith(new ApiException(
            "API request failed: {\"object\":\"error\",\"message\":\"Requested token count "
            + "exceeds the model's maximum context length of 131072 tokens. You requested "
            + "a total of 135143 tokens: 103143 tokens from the input messages and 32000 "
            + "tokens for the completion.\",\"type\":\"BadRequestError\"}", 400));

        CompactSummarizer.SummaryResult result = summarizer.summarizeWithUsage(List.of(), "prompt");

        assertTrue(Strings.CS.startsWith(result.text(), CompactService.PROMPT_TOO_LONG_MARKER),
            "gateway context-length overflow must surface as the marker text; got: " + result.text());
    }

    @Test
    void otherApiErrorsPropagate() {
        LlmCompactSummarizer summarizer = forkSummarizerFailingWith(
            new ApiException("API request failed: authentication_error", 401));

        assertThrows(ApiException.class,
            () -> summarizer.summarizeWithUsage(List.of(), "prompt"));
    }

    /** A fork summarizer whose transport fails the request outright. */
    private static LlmCompactSummarizer forkSummarizerFailingWith(RuntimeException failure) {
        StreamingClient client = new StreamingClient() {
            @Override
            public Iterator<StreamingEvent> createStream(StreamRequest request) {
                throw failure;
            }

            @Override
            public String getModel() { return "fake"; }
        };
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(client)
            .model("claude-sonnet-5")
            .maxTokens(32_000)
            .build());
        return new LlmCompactSummarizer(client, () -> engine);
    }

    /**
     * The compact prompt threatens that tool calls "will be REJECTED and will waste your only
     * turn". The original makes that true with a deny-all {@code canUseTool}; this port never runs
     * an execution loop on the fork, so the threat holds by construction — but the wasted turn used
     * to arrive as an empty summary and be reported to the user as a network interruption.
     */
    @Test
    void aTurnSpentOnToolCallsFailsWithTheRealReasonInsteadOfLookingLikeAnEmptyResponse() {
        StreamingClient toolCalling = new StreamingClient() {
            @Override
            public Iterator<StreamingEvent> createStream(StreamRequest request) {
                return List.<StreamingEvent>of(
                    new StreamingEvent.MessageStartEvent(
                        "msg-compact", request.model(), List.of(), Usage.EMPTY),
                    new StreamingEvent.ContentBlockStartEvent(0, "tool_use", "tu-1", "Read"),
                    new StreamingEvent.ContentBlockStopEvent(0),
                    new StreamingEvent.MessageDeltaEvent("tool_use", Usage.EMPTY),
                    new StreamingEvent.MessageStopEvent()
                ).iterator();
            }

            @Override
            public String getModel() {
                return "fake";
            }
        };
        QuerySessionSpec config = QuerySessionSpec.builder()
            .llmClient(toolCalling)
            .model("claude-opus-5")
            .systemPrompt("sys")
            .maxTokens(32_000)
            .toolExecutor(new OneToolExecutor())
            .tools(List.of("Read"))
            .sessionIdentity(SessionIdentity.of("session-tooluse"))
            .build();
        DefaultQuerySession engine = new DefaultQuerySession(config);
        LlmCompactSummarizer summarizer = new LlmCompactSummarizer(toolCalling, () -> engine);

        CompactException failure = assertThrows(CompactException.class, () ->
            summarizer.summarizeWithUsage(
                List.of(new UserMessage("u1", MessageContent.ofText("hello"))), "COMPACT PROMPT"));

        assertTrue(Strings.CS.contains(failure.getMessage(), "calling tools"), failure.getMessage());
        assertTrue(Strings.CS.contains(failure.getMessage(), "Read"),
            "the offending tool must be named so the failure is diagnosable: " + failure.getMessage());
    }

    /** Text alongside a tool call is still a usable summary — only a text-free turn is a failure. */
    @Test
    void textIsStillAcceptedWhenTheModelAlsoRequestedATool() {
        StreamingClient mixed = new StreamingClient() {
            @Override
            public Iterator<StreamingEvent> createStream(StreamRequest request) {
                return List.<StreamingEvent>of(
                    new StreamingEvent.MessageStartEvent(
                        "msg-compact", request.model(), List.of(), Usage.EMPTY),
                    new StreamingEvent.ContentBlockStartEvent(0, "text", null, null),
                    new StreamingEvent.ContentBlockDeltaEvent(0, "text_delta", "<summary>x</summary>"),
                    new StreamingEvent.ContentBlockStopEvent(0),
                    new StreamingEvent.ContentBlockStartEvent(1, "tool_use", "tu-1", "Read"),
                    new StreamingEvent.ContentBlockStopEvent(1),
                    new StreamingEvent.MessageStopEvent()
                ).iterator();
            }

            @Override
            public String getModel() {
                return "fake";
            }
        };
        QuerySessionSpec config = QuerySessionSpec.builder()
            .llmClient(mixed)
            .model("claude-opus-5")
            .systemPrompt("sys")
            .maxTokens(32_000)
            .toolExecutor(new OneToolExecutor())
            .tools(List.of("Read"))
            .sessionIdentity(SessionIdentity.of("session-mixed"))
            .build();
        DefaultQuerySession engine = new DefaultQuerySession(config);
        LlmCompactSummarizer summarizer = new LlmCompactSummarizer(mixed, () -> engine);

        CompactSummarizer.SummaryResult result = summarizer.summarizeWithUsage(
            List.of(new UserMessage("u1", MessageContent.ofText("hello"))), "COMPACT PROMPT");

        assertEquals("<summary>x</summary>", result.text());
    }
}

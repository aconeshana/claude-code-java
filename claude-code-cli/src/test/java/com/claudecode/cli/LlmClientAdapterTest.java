package com.claudecode.cli;

import org.apache.commons.lang3.Strings;

import com.claudecode.api.*;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.StreamingClient.StreamingEvent;
import com.claudecode.core.engine.ThinkingClearLatch;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ServerToolUseBlock;
import com.claudecode.core.message.WebSearchToolResultBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.serialization.JsonUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the LlmClientAdapter that bridges api LlmClient to core StreamingClient.
 */
class LlmClientAdapterTest {

    @Test
    void transportAttemptTimingCrossesTheApiToCoreAdapterBoundary() {
        long finalAttemptStartMs = System.currentTimeMillis() - 25;
        var apiEvents = List.<StreamEvent>of(
            new StreamEvent.RequestTiming(finalAttemptStartMs),
            new StreamEvent.MessageStop());

        Iterator<StreamingEvent> events =
            new LlmClientAdapter.StreamingEventAdapter(apiEvents.iterator());
        events.forEachRemaining(_ -> {});

        StreamingClient.TimedStreamingIterator timed = assertInstanceOf(
            StreamingClient.TimedStreamingIterator.class, events);
        assertEquals(finalAttemptStartMs, timed.lastAttemptStartMs());
    }

    @Test
    void preservesProviderExecutedToolBlocksAcrossApiBoundary() {
        var result = new WebSearchToolResultBlock("srv_1",
            List.of(new WebSearchToolResultBlock.Hit("OpenCode", "https://opencode.ai")), null);
        var apiEvents = List.<StreamEvent>of(
            new StreamEvent.ContentBlockStart(0,
                new ServerToolUseBlock("srv_1", "web_search", JsonUtils.parseTree("{}"))),
            new StreamEvent.ContentBlockDelta(0,
                new Delta.InputJsonDelta("{\"query\":\"OpenCode\"}")),
            new StreamEvent.ContentBlockStop(0),
            new StreamEvent.ContentBlockStart(1, result),
            new StreamEvent.ContentBlockStop(1),
            new StreamEvent.MessageStop());

        var adapter = new LlmClientAdapter.StreamingEventAdapter(apiEvents.iterator());
        List<StreamingEvent> converted = new ArrayList<>();
        adapter.forEachRemaining(converted::add);

        var serverStart = assertInstanceOf(StreamingEvent.ContentBlockStartEvent.class, converted.getFirst());
        assertEquals("server_tool_use", serverStart.type());
        assertEquals("srv_1", serverStart.id());
        assertNull(serverStart.block());
        var resultStart = assertInstanceOf(StreamingEvent.ContentBlockStartEvent.class, converted.get(3));
        assertEquals("web_search_tool_result", resultStart.type());
        assertSame(result, resultStart.block());
    }

    @BeforeEach
    void resetThinkingClearState() {
        // ThinkingClearLatch/lastAssistantTurnMsBySource are static — reset between
        // tests so the escalation tests below don't leak into the "keep all" tests.
        ThinkingClearLatch.reset();
        LlmClientAdapter.lastAssistantTurnMsBySource.clear();
    }

    @AfterEach
    void clearRuntimeEnvOverrides() {
        // requestMetadata reads CLAUDE_CODE_EXTRA_METADATA via SubprocessEnvironment;
        // neutralize any test-injected value so it can't leak into sibling tests.
        // An empty string is treated as unset by the parser (blank), fully
        // disabling the escape hatch without needing core-internal clear APIs.
        SubprocessEnvironment.updateRuntime(Map.of("CLAUDE_CODE_EXTRA_METADATA", ""));
    }

    @Test
    void testAdapterConvertsStreamEvents() {
        // Create a mock LlmClient that returns known StreamEvents
        LlmClient mockLlm = new LlmClient() {
            @Override
            public Iterator<StreamEvent> createMessageStream(CreateMessageRequest request) {
                List<StreamEvent> events = List.of(
                    new StreamEvent.MessageStart(
                        ApiMessage.builder()
                            .id("msg_001")
                            .model("test-model")
                            .usage(new Usage(10, 0, 0, 0))
                            .build(),
                        null,
                        "req_001"
                    ),
                    new StreamEvent.ContentBlockStart(0, new TextBlock("")),
                    new StreamEvent.ContentBlockDelta(0, new Delta.TextDelta("Hello ")),
                    new StreamEvent.ContentBlockDelta(0, new Delta.TextDelta("world!")),
                    new StreamEvent.ContentBlockStop(0),
                    new StreamEvent.MessageDelta(
                        new MessageDeltaData("end_turn", "stop-here"),
                        new Usage(0, 15, 0, 0)
                    ),
                    new StreamEvent.MessageStop()
                );
                return events.iterator();
            }

            @Override
            public ApiMessage createMessage(CreateMessageRequest request) {
                return null;
            }

            @Override
            public String getModel() {
                return "test-model";
            }
        };

        LlmClientAdapter adapter = new LlmClientAdapter(mockLlm);
        assertEquals("test-model", adapter.getModel());

        StreamingClient.StreamRequest request = new StreamingClient.StreamRequest(
            "test-model", 100, "system", List.of(), true
        );

        Iterator<StreamingEvent> events = adapter.createStream(request);
        List<StreamingEvent> collected = new ArrayList<>();
        while (events.hasNext()) {
            collected.add(events.next());
        }

        // Should have: MessageStart, ContentBlockStart, 2x ContentBlockDelta, ContentBlockStop, MessageDelta, MessageStop
        // (ContentBlockStart and ContentBlockStop are now forwarded, only Ping is filtered)
        assertEquals(7, collected.size());

        assertInstanceOf(StreamingEvent.MessageStartEvent.class, collected.getFirst());
        StreamingEvent.MessageStartEvent start = (StreamingEvent.MessageStartEvent) collected.getFirst();
        assertEquals("msg_001", start.messageId());
        assertEquals("req_001", start.requestId());

        assertInstanceOf(StreamingEvent.ContentBlockStartEvent.class, collected.get(1));
        StreamingEvent.ContentBlockStartEvent cbStart = (StreamingEvent.ContentBlockStartEvent) collected.get(1);
        assertEquals(0, cbStart.index());
        assertEquals("text", cbStart.type());

        assertInstanceOf(StreamingEvent.ContentBlockDeltaEvent.class, collected.get(2));
        StreamingEvent.ContentBlockDeltaEvent delta1 = (StreamingEvent.ContentBlockDeltaEvent) collected.get(2);
        assertEquals("Hello ", delta1.deltaText());

        assertInstanceOf(StreamingEvent.ContentBlockDeltaEvent.class, collected.get(3));
        StreamingEvent.ContentBlockDeltaEvent delta2 = (StreamingEvent.ContentBlockDeltaEvent) collected.get(3);
        assertEquals("world!", delta2.deltaText());

        assertInstanceOf(StreamingEvent.ContentBlockStopEvent.class, collected.get(4));
        StreamingEvent.ContentBlockStopEvent cbStop = (StreamingEvent.ContentBlockStopEvent) collected.get(4);
        assertEquals(0, cbStop.index());

        assertInstanceOf(StreamingEvent.MessageDeltaEvent.class, collected.get(5));
        StreamingEvent.MessageDeltaEvent messageDelta =
            (StreamingEvent.MessageDeltaEvent) collected.get(5);
        assertEquals("end_turn", messageDelta.stopReason());
        assertEquals("stop-here", messageDelta.stopSequence());
        assertInstanceOf(StreamingEvent.MessageStopEvent.class, collected.get(6));
    }

    @Test
    void incompleteStreamFallsBackToNonStreamingRequest() {
        final CreateMessageRequest[] streamingRequest = {null};
        final CreateMessageRequest[] fallbackRequest = {null};
        final long[] fallbackTimeoutMs = {-1};
        AtomicBoolean fallbackNotified = new AtomicBoolean();

        LlmClient client = new LlmClient() {
            @Override
            public Iterator<StreamEvent> createMessageStream(CreateMessageRequest request) {
                streamingRequest[0] = request;
                return List.<StreamEvent>of(new StreamEvent.Error(
                    new ApiException("stream ended before message_stop", 0))).iterator();
            }

            @Override
            public ApiMessage createMessage(CreateMessageRequest request) {
                fallbackRequest[0] = request;
                return ApiMessage.stub("test-model", "recovered");
            }

            @Override
            public ApiMessage createMessage(CreateMessageRequest request, long timeoutMillis) {
                fallbackRequest[0] = request;
                fallbackTimeoutMs[0] = timeoutMillis;
                return ApiMessage.stub("test-model", "recovered");
            }

            @Override
            public String getModel() {
                return "test-model";
            }
        };

        StreamingClient.StreamRequest request = new StreamingClient.StreamRequest(
            "test-model", 100, "system", List.of(), true, List.of(), null, null,
            null, null, null, null, () -> fallbackNotified.set(true), true);

        List<StreamingEvent> events = new ArrayList<>();
        Iterator<StreamingEvent> iterator = new LlmClientAdapter(client).createStream(request);
        while (iterator.hasNext()) {
            events.add(iterator.next());
        }

        assertTrue(fallbackNotified.get());
        assertInstanceOf(StreamingEvent.FallbackBeganEvent.class, events.getFirst());
        assertTrue(streamingRequest[0].stream());
        assertNotNull(fallbackRequest[0]);
        assertFalse(fallbackRequest[0].stream(), "fallback must use non-streaming API semantics");
        assertEquals(300_000L, fallbackTimeoutMs[0],
            "the fallback needs its own bounded deadline, not the 10-minute streaming default");
        assertTrue(events.stream().anyMatch(event -> event instanceof StreamingEvent.ContentBlockDeltaEvent delta
            && Strings.CS.equals("recovered", delta.deltaText())));
        assertInstanceOf(StreamingEvent.MessageStopEvent.class, events.getLast());
    }

    @Test
    void streamCreation404EmitsFallbackMarkerBeforeSendingTheSyncRequest() {
        AtomicBoolean fallbackCalled = new AtomicBoolean();
        LlmClient client = new LlmClient() {
            @Override public Iterator<StreamEvent> createMessageStream(CreateMessageRequest request) {
                throw new ApiException("stream endpoint missing", 404);
            }
            @Override public ApiMessage createMessage(CreateMessageRequest request) {
                fallbackCalled.set(true);
                return ApiMessage.stub("test-model", "sync recovery");
            }
            @Override public ApiMessage createMessage(CreateMessageRequest request, long timeoutMillis) {
                return createMessage(request);
            }
            @Override public String getModel() { return "test-model"; }
        };

        var request = new StreamingClient.StreamRequest(
            "test-model", 100, "system", List.of(), true);
        List<StreamingEvent> events = new ArrayList<>();
        Iterator<StreamingEvent> iterator = new LlmClientAdapter(client).createStream(request);

        assertFalse(fallbackCalled.get());
        assertTrue(iterator.hasNext());
        events.add(iterator.next());
        assertInstanceOf(StreamingEvent.FallbackBeganEvent.class, events.getFirst());
        assertFalse(fallbackCalled.get(),
            "the public fallback marker must be observable before the sync request starts");
        iterator.forEachRemaining(events::add);

        assertTrue(fallbackCalled.get());
        assertTrue(events.stream().anyMatch(event -> event instanceof StreamingEvent.ContentBlockDeltaEvent delta
            && Strings.CS.equals("sync recovery", delta.deltaText())));
    }

    @Test
    void staleConnectionAfterVisibleOutputFinalizesPartialResponseWithoutSyncDuplicate() {
        AtomicBoolean fallbackCalled = new AtomicBoolean();
        LlmClient client = new LlmClient() {
            @Override public Iterator<StreamEvent> createMessageStream(CreateMessageRequest request) {
                return List.<StreamEvent>of(
                    new StreamEvent.MessageStart(ApiMessage.builder().id("msg-partial").model("test-model").build()),
                    new StreamEvent.ContentBlockStart(0, new TextBlock("")),
                    new StreamEvent.ContentBlockDelta(0, new Delta.TextDelta("partial")),
                    new StreamEvent.Error(new ApiStreamException("closed", 0,
                        ApiStreamException.Reason.STALE_CONNECTION))).iterator();
            }
            @Override public ApiMessage createMessage(CreateMessageRequest request) {
                fallbackCalled.set(true);
                return ApiMessage.stub("test-model", "duplicate");
            }
            @Override public String getModel() { return "test-model"; }
        };

        var request = new StreamingClient.StreamRequest(
            "test-model", 100, "system", List.of(), true);
        List<StreamingEvent> events = new ArrayList<>();
        new LlmClientAdapter(client).createStream(request).forEachRemaining(events::add);

        assertFalse(fallbackCalled.get());
        assertTrue(events.stream().anyMatch(StreamingEvent.MessageStopEvent.class::isInstance));
        assertTrue(events.stream().anyMatch(StreamingEvent.SystemApiErrorEvent.class::isInstance));
    }

    @Test
    void nonStreamingFailurePropagatesInsteadOfRestoringTheOriginalStreamError() {
        ApiException fallbackFailure = new ApiException("sync failed", 503);
        LlmClient client = new LlmClient() {
            @Override public Iterator<StreamEvent> createMessageStream(CreateMessageRequest request) {
                return List.<StreamEvent>of(new StreamEvent.Error(
                    new ApiException("stream failed", 0))).iterator();
            }
            @Override public ApiMessage createMessage(CreateMessageRequest request) {
                throw fallbackFailure;
            }
            @Override public ApiMessage createMessage(CreateMessageRequest request, long timeoutMillis) {
                throw fallbackFailure;
            }
            @Override public String getModel() { return "test-model"; }
        };

        var iterator = new LlmClientAdapter(client).createStream(new StreamingClient.StreamRequest(
            "test-model", 100, "system", List.of(), true));

        assertTrue(iterator.hasNext());
        assertInstanceOf(StreamingEvent.FallbackBeganEvent.class, iterator.next());
        assertSame(fallbackFailure, assertThrows(ApiException.class, iterator::hasNext));
    }

    @Test
    void firstMidStream529UsesTheSharedRetryBudgetBeforeSwitchingModels() {
        AtomicBoolean syncRecoveryCalled = new AtomicBoolean();
        LlmClient client = new LlmClient() {
            @Override public Iterator<StreamEvent> createMessageStream(CreateMessageRequest request) {
                return List.<StreamEvent>of(new StreamEvent.Error(
                    new ApiException("overloaded", 529, "overloaded_error", null))).iterator();
            }

            @Override public ApiMessage createMessage(CreateMessageRequest request, long timeoutMillis) {
                syncRecoveryCalled.set(true);
                return ApiMessage.stub(request.model(), "recovered before model fallback");
            }

            @Override public ApiMessage createMessage(CreateMessageRequest request) {
                return createMessage(request, 0);
            }

            @Override public String getModel() { return "primary"; }
        };
        var request = new StreamingClient.StreamRequest(
            "primary", 100, "system", List.of(), true, List.of(), null, null,
            "fallback", null, null, null, null, true);

        List<StreamingEvent> events = new ArrayList<>();
        new LlmClientAdapter(client).createStream(request).forEachRemaining(events::add);

        assertTrue(syncRecoveryCalled.get());
        assertInstanceOf(StreamingEvent.FallbackBeganEvent.class, events.getFirst());
        assertTrue(events.stream().anyMatch(event ->
            event instanceof StreamingEvent.ContentBlockDeltaEvent delta
                && Strings.CS.equals("recovered before model fallback", delta.deltaText())));
    }

    @Test
    void nonStreamingFallbackCapsMaxTokensAndThinkingBudget() {

        CreateMessageRequest escalated = CreateMessageRequest.builder()
            .model("claude-opus-4-6")
            .maxTokens(100_000)
            .thinking(CreateMessageRequest.ThinkingConfig.enabled(99_999))
            .promptCacheTtl(CreateMessageRequest.PromptCacheTtl.ONE_HOUR)
            .build();

        CreateMessageRequest fallback = LlmClientAdapter.nonStreamingRequest(escalated);

        assertFalse(fallback.stream());
        assertEquals(64_000, fallback.maxTokens());
        assertEquals(63_999, fallback.thinking().budgetTokens(),
            "the API requires max_tokens > thinking.budget_tokens");
        assertEquals(CreateMessageRequest.PromptCacheTtl.ONE_HOUR, fallback.promptCacheTtl(),
            "non-streaming recovery must retain the cache key's TTL");
    }

    @Test
    void nonStreamingFallbackLeavesRequestsUnderTheCapAlone() {
        CreateMessageRequest normal = CreateMessageRequest.builder()
            .model("claude-sonnet-4-5-20250929")
            .maxTokens(8_000)
            .thinking(CreateMessageRequest.ThinkingConfig.enabled(7_999))
            .build();

        CreateMessageRequest fallback = LlmClientAdapter.nonStreamingRequest(normal);

        assertEquals(8_000, fallback.maxTokens());
        assertEquals(7_999, fallback.thinking().budgetTokens());
    }

    @Test
    void nonStreamingFallbackKeepsBudgetlessThinkingShapes() {
        CreateMessageRequest adaptive = CreateMessageRequest.builder()
            .model("claude-opus-4-6")
            .maxTokens(100_000)
            .thinking(CreateMessageRequest.ThinkingConfig.adaptive())
            .build();

        CreateMessageRequest fallback = LlmClientAdapter.nonStreamingRequest(adaptive);

        assertEquals(64_000, fallback.maxTokens());
        assertEquals("adaptive", fallback.thinking().type());
        assertNull(fallback.thinking().budgetTokens());
    }

    @Test
    void testAdapterPassesRequestParameters() {
        // Verify that the adapter correctly converts StreamRequest to CreateMessageRequest
        final CreateMessageRequest[] capturedRequest = {null};

        LlmClient capturingClient = new LlmClient() {
            @Override
            public Iterator<StreamEvent> createMessageStream(CreateMessageRequest request) {
                capturedRequest[0] = request;
                return List.<StreamEvent>of(new StreamEvent.MessageStop()).iterator();
            }

            @Override
            public ApiMessage createMessage(CreateMessageRequest request) {
                return null;
            }

            @Override
            public String getModel() {
                return "test-model";
            }
        };

        LlmClientAdapter adapter = new LlmClientAdapter(capturingClient);

        StreamingClient.StreamRequest request = new StreamingClient.StreamRequest(
            "claude-sonnet-4-20250514",
            8192,
            "You are helpful",
            List.of(
                new StreamingClient.StreamRequest.RequestMessage("user", "Hello"),
                new StreamingClient.StreamRequest.RequestMessage("assistant", "Hi there")
            ),
            true
        );

        // Consume the iterator
        Iterator<StreamingEvent> events = adapter.createStream(request);
        while (events.hasNext()) events.next();

        assertNotNull(capturedRequest[0]);
        assertEquals("claude-sonnet-4-20250514", capturedRequest[0].model());
        assertEquals(8192, capturedRequest[0].maxTokens());
        assertEquals("You are helpful", capturedRequest[0].systemPrompt());
        assertEquals(2, capturedRequest[0].messages().size());
        assertEquals("user", capturedRequest[0].messages().getFirst().role());
        assertEquals("Hello", capturedRequest[0].messages().getFirst().content());
        assertEquals(List.of(), capturedRequest[0].tools(),
            "main-loop requests preserve an explicit empty tools array");
    }

    @Test
    void adapterMapsServerToolsThroughTheServerToolRequestShape() {
        var serverTool = StreamingClient.StreamRequest.ToolDef.serverTool(
            "web_search_20250305", "web_search", 8,
            List.of("docs.example.com"), List.of("blocked.example.com"));
        var request = new StreamingClient.StreamRequest(
            "claude-sonnet-4-20250514", 4096, "Search", List.of(), true,
            List.of(serverTool));

        CreateMessageRequest.ToolDefinition captured = capture(request).tools().getFirst();

        assertEquals("web_search_20250305", captured.type());
        assertEquals("web_search", captured.name());
        assertEquals(8, captured.maxUses());
        assertEquals(List.of("docs.example.com"), captured.allowedDomains());
        assertEquals(List.of("blocked.example.com"), captured.blockedDomains());
        assertNull(captured.inputSchema());
        assertNull(captured.description());
    }

    @Test
    void adapterUsesMaxOutputTokensOverrideForApiAndThinkingBudget() {
        StreamingClient.StreamRequest request = new StreamingClient.StreamRequest(
            "claude-sonnet-4-5-20250929",
            8_000,
            "sys",
            List.of(new StreamingClient.StreamRequest.RequestMessage("user", "hi")),
            true,
            List.of(), null, null, null, 64_000, null, null, null, true,
            null, null, false, null, null, null);

        CreateMessageRequest captured = capture(request);

        assertEquals(64_000, captured.maxTokens());
        assertNotNull(captured.thinking());
        assertEquals("enabled", captured.thinking().type());
        assertEquals(63_999, captured.thinking().budgetTokens(),
            "legacy thinking must use the effective escalated max_tokens");
    }

    @Test
    void adapterCarriesQuerySourceAndCancellationWithoutAddingWireFields() throws Exception {
        AbortController abortController = new AbortController();
        StreamingClient.StreamRequest request = new StreamingClient.StreamRequest(
            "test-model", 100, "system", List.of(), true, List.of(), null, null,
            null, null, null, null, null, true, "session", null, false,
            "generate_session_title", abortController);

        CreateMessageRequest captured = capture(request);
        AtomicBoolean cancelled = new AtomicBoolean();
        AutoCloseable registration = captured.cancellationRegistrar()
            .register(() -> cancelled.set(true));

        abortController.abort("test");

        assertEquals("generate_session_title", captured.querySource());
        assertTrue(cancelled.get());
        assertFalse(JsonUtils.getMapper().valueToTree(captured).has("querySource"));
        assertFalse(JsonUtils.getMapper().valueToTree(captured).has("cancellationRegistrar"));
        registration.close();
    }

    private static CreateMessageRequest capture(StreamingClient.StreamRequest request) {
        final CreateMessageRequest[] captured = {null};
        LlmClient capturingClient = new LlmClient() {
            @Override
            public Iterator<StreamEvent> createMessageStream(CreateMessageRequest r) {
                captured[0] = r;
                return List.<StreamEvent>of(new StreamEvent.MessageStop()).iterator();
            }
            @Override public ApiMessage createMessage(CreateMessageRequest r) { return null; }
            @Override public String getModel() { return "test-model"; }
        };
        Iterator<StreamingEvent> events = new LlmClientAdapter(capturingClient).createStream(request);
        while (events.hasNext()) events.next();
        return captured[0];
    }

    @Test
    void contextManagementSentWhenThinkingOnAndModelSupportsIt() {

        StreamingClient.StreamRequest request = new StreamingClient.StreamRequest(
            "claude-sonnet-4-20250514", 8192, "sys",
            List.of(new StreamingClient.StreamRequest.RequestMessage("user", "hi")), true);

        CreateMessageRequest captured = capture(request);

        assertNotNull(captured.contextManagement(), "context_management must be attached when thinking is on");
        assertEquals(1, captured.contextManagement().edits().size());
        var edit = captured.contextManagement().edits().getFirst();
        assertEquals("clear_thinking_20251015", edit.type());
        assertInstanceOf(CreateMessageRequest.ContextEditStrategy.Keep.KeepAll.class, edit.keep());
    }

    @Test
    void contextManagementOmittedWhenThinkingDisabled() {
        StreamingClient.StreamRequest base = new StreamingClient.StreamRequest(
            "claude-sonnet-4-20250514", 8192, "sys",
            List.of(new StreamingClient.StreamRequest.RequestMessage("user", "hi")), true);
        // Rebuild with thinkingEnabled=false via the full constructor (the
        // 5-arg convenience constructor always defaults thinkingEnabled=true).
        StreamingClient.StreamRequest request = new StreamingClient.StreamRequest(
            base.model(), base.maxTokens(), base.systemPrompt(), base.messages(),
            base.stream(), List.of(), null, null, null, null, null, null, null,
            false, null);

        CreateMessageRequest captured = capture(request);

        assertNull(captured.contextManagement(), "no thinking means no context_management edit to attach");
    }

    @Test
    void adaptiveModelCanBeExplicitlyDisabledLike197() {
        StreamingClient.StreamRequest request = new StreamingClient.StreamRequest(
            "claude-sonnet-4-6", 32_000, "sys",
            List.of(new StreamingClient.StreamRequest.RequestMessage("user", "hi")), true,
            List.of(), null, "high", null, null, null, null, null,
            false, null);

        CreateMessageRequest captured = capture(request);

        assertNotNull(captured.thinking());
        assertEquals("disabled", captured.thinking().type());
        assertNull(captured.thinking().budgetTokens());
        assertEquals(1.0, captured.temperature());
        assertNull(captured.contextManagement());
        assertNotNull(captured.outputConfig());
        assertEquals("high", captured.outputConfig().effort());
        assertNull(captured.effort());
    }

    @Test
    void legacyThinkingDefaultsToMaxOutputMinusOneLike197() {
        StreamingClient.StreamRequest request = new StreamingClient.StreamRequest(
            "claude-sonnet-4-5-20250929", 32_000, "sys",
            List.of(new StreamingClient.StreamRequest.RequestMessage("user", "hi")), true);

        CreateMessageRequest captured = capture(request);

        assertNotNull(captured.thinking());
        assertEquals("enabled", captured.thinking().type());
        assertEquals(31_999, captured.thinking().budgetTokens());
        assertNull(captured.temperature());
        assertNotNull(captured.contextManagement());
    }

    @Test
    void legacyThinkingUsesExplicitBudgetAndClampsToMaxOutputMinusOne() {
        StreamingClient.StreamRequest explicit = new StreamingClient.StreamRequest(
            "claude-sonnet-4-5-20250929", 32_000, "sys",
            List.of(new StreamingClient.StreamRequest.RequestMessage("user", "hi")), true,
            List.of(), null, null, null, null, null, null, null, true,
            null, null, false, null, null, 5_000);
        assertEquals(5_000, capture(explicit).thinking().budgetTokens());

        StreamingClient.StreamRequest oversized = new StreamingClient.StreamRequest(
            "claude-sonnet-4-5-20250929", 32_000, "sys",
            List.of(new StreamingClient.StreamRequest.RequestMessage("user", "hi")), true,
            List.of(), null, null, null, null, null, null, null, true,
            null, null, false, null, null, 40_000);
        assertEquals(31_999, capture(oversized).thinking().budgetTokens());
    }

    @Test
    void thinkingEnvironmentGatesMatch197PresenceRules() {
        StreamingClient.StreamRequest request = new StreamingClient.StreamRequest(
            "claude-sonnet-4-6", 32_000, "sys",
            List.of(new StreamingClient.StreamRequest.RequestMessage("user", "hi")), true);

        LlmClientAdapter.ThinkingSelection globallyDisabled =
            LlmClientAdapter.selectThinking(request, "1", null);
        assertNull(globallyDisabled.config());
        assertFalse(globallyDisabled.hasThinking());

        LlmClientAdapter.ThinkingSelection adaptiveDisabled =
            LlmClientAdapter.selectThinking(request, null, "true");
        assertEquals("enabled", adaptiveDisabled.config().type());
        assertEquals(31_999, adaptiveDisabled.config().budgetTokens());
        assertTrue(adaptiveDisabled.hasThinking());
    }

    @Test
    void contextManagementEscalatesToKeepLastTurnAfterOneHourIdle() {

        LlmClientAdapter.lastAssistantTurnMsBySource.put(
            "repl_main_thread", System.currentTimeMillis() - 61 * 60 * 1000);

        StreamingClient.StreamRequest request = new StreamingClient.StreamRequest(
            "claude-sonnet-4-20250514", 8192, "sys",
            List.of(new StreamingClient.StreamRequest.RequestMessage("user", "hi")), true);

        CreateMessageRequest captured = capture(request);

        var edit = captured.contextManagement().edits().getFirst();
        assertEquals("clear_thinking_20251015", edit.type());
        assertInstanceOf(CreateMessageRequest.ContextEditStrategy.Keep.ThinkingTurnsKeep.class, edit.keep());
        var keep = (CreateMessageRequest.ContextEditStrategy.Keep.ThinkingTurnsKeep) edit.keep();
        assertEquals("thinking_turns", keep.type());
        assertEquals(1, keep.value());
    }

    @Test
    void contextManagementLatchStaysTrippedOnceSet() {
        // The latch is one-way: once tripped, it stays tripped for the rest of the
        // process even if a later request no longer has a qualifying idle gap —

        LlmClientAdapter.lastAssistantTurnMsBySource.put(
            "repl_main_thread", System.currentTimeMillis() - 61 * 60 * 1000);
        StreamingClient.StreamRequest request = new StreamingClient.StreamRequest(
            "claude-sonnet-4-20250514", 8192, "sys",
            List.of(new StreamingClient.StreamRequest.RequestMessage("user", "hi")), true);
        capture(request); // trips the latch

        LlmClientAdapter.lastAssistantTurnMsBySource.put("repl_main_thread", System.currentTimeMillis());
        CreateMessageRequest captured = capture(request);

        assertInstanceOf(CreateMessageRequest.ContextEditStrategy.Keep.ThinkingTurnsKeep.class,
            captured.contextManagement().edits().getFirst().keep());
    }

    @Test
    void contextManagementRevertsToKeepAllAfterLatchReset() {
// ThinkingClearLatch.reset is called by SessionController.clearConversation (/clear) and
// CompactService.compactConversation (/compact, auto-compact).
        LlmClientAdapter.lastAssistantTurnMsBySource.put(
            "repl_main_thread", System.currentTimeMillis() - 61 * 60 * 1000);
        StreamingClient.StreamRequest request = new StreamingClient.StreamRequest(
            "claude-sonnet-4-20250514", 8192, "sys",
            List.of(new StreamingClient.StreamRequest.RequestMessage("user", "hi")), true);
        capture(request); // trips the latch
        assertTrue(ThinkingClearLatch.isLatched());

        ThinkingClearLatch.reset();
        CreateMessageRequest captured = capture(request);

        assertInstanceOf(CreateMessageRequest.ContextEditStrategy.Keep.KeepAll.class,
            captured.contextManagement().edits().getFirst().keep());
    }

    @Test
    void contextManagementOmittedForClaude3Models() {
        // supportsContextManagement excludes the Claude 3 generation even
        // when thinking happens to be requested on it.
        StreamingClient.StreamRequest request = new StreamingClient.StreamRequest(
            "claude-3-opus-20240229", 4096, "sys",
            List.of(new StreamingClient.StreamRequest.RequestMessage("user", "hi")), true);

        CreateMessageRequest captured = capture(request);

        assertNull(captured.contextManagement());
    }

    @Test
    void experimentalBetasGate_disabledWhenEnvTruthy() {

        assertFalse(LlmClientAdapter.experimentalBetasEnabledFor("1"));
        assertFalse(LlmClientAdapter.experimentalBetasEnabledFor("true"));
        assertFalse(LlmClientAdapter.experimentalBetasEnabledFor("YES"));
    }

    @Test
    void experimentalBetasGate_enabledByDefaultAndOnFalsyValues() {
        assertTrue(LlmClientAdapter.experimentalBetasEnabledFor(null));
        assertTrue(LlmClientAdapter.experimentalBetasEnabledFor(""));
        assertTrue(LlmClientAdapter.experimentalBetasEnabledFor("0"));
        assertTrue(LlmClientAdapter.experimentalBetasEnabledFor("false"));
    }

    @Test
    void contextManagementSuppressedWhenExperimentalBetasDisabled() {
        // When the gate is off, the whole context_management body field is omitted

        // We assert the gate wiring by proving the default (gate on) attaches it,
        // then that experimentalBetasEnabledFor drives the same attach site.
        StreamingClient.StreamRequest request = new StreamingClient.StreamRequest(
            "claude-sonnet-4-20250514", 8192, "sys",
            List.of(new StreamingClient.StreamRequest.RequestMessage("user", "hi")), true);
        // Default env (gate on) → attached, proving the attach site is otherwise reached.
        assertTrue(LlmClientAdapter.firstPartyExperimentalBetasEnabled(),
            "test env must not set CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS");
        assertNotNull(capture(request).contextManagement());
    }

    @Test
    void requestMetadataSpreadsExtraMetadataBeneathIdentityKeys() throws Exception {

        // account_uuid / session_id, so the three identity keys always win.
        SubprocessEnvironment.updateRuntime(
            Map.of("CLAUDE_CODE_EXTRA_METADATA", "{\"device_id\":\"EVIL\",\"team\":\"atlas\"}"));

        var metadata = LlmClientAdapter.requestMetadata("sess-1");
        assertNotNull(metadata, "non-blank session id yields metadata");
        var userId = JsonUtils.getMapper().readTree(metadata.get("user_id").asText());
        assertEquals("atlas", userId.get("team").asText(),
            "non-identity extra key flows through");
        assertNotEquals("EVIL", userId.get("device_id").asText(),
            "identity key must override a spoofed extra.device_id");
        assertEquals("sess-1", userId.get("session_id").asText());
        assertEquals("", userId.get("account_uuid").asText());
    }

    @Test
    void fastModeRateLimitEntersCooldownAndRetriesAtStandardSpeed() {
        List<String> speeds = new ArrayList<>();
        AtomicInteger cooldownStatus = new AtomicInteger();
        AtomicInteger attempts = new AtomicInteger();
        LlmClient client = new LlmClient() {
            @Override public Iterator<StreamEvent> createMessageStream(CreateMessageRequest request) {
                speeds.add(request.speed());
                if (attempts.getAndIncrement() == 0) throw new ApiException("rate limited", 429);
                return List.<StreamEvent>of().iterator();
            }
            @Override public ApiMessage createMessage(CreateMessageRequest request) {
                throw new UnsupportedOperationException();
            }
            @Override public String getModel() { return "claude-opus-4-8"; }
        };
        StreamingClient.StreamRequest request = new StreamingClient.StreamRequest(
            "claude-opus-4-8", 1024, null,
            List.of(new StreamingClient.StreamRequest.RequestMessage("user", "hello")),
            true, List.of(), null, null, null, null, null, null, null, false,
            null, null, false, "repl_main_thread", new AbortController(), null,
            true, (status, _) -> cooldownStatus.set(status));

        new LlmClientAdapter(client).createStream(request);

        assertEquals(2, speeds.size());
        assertEquals("fast", speeds.getFirst());
        assertNull(speeds.getLast());
        assertEquals(429, cooldownStatus.get());
    }

    @Test
    void requestMetadataIgnoresMalformedExtraMetadata() throws Exception {
        SubprocessEnvironment.updateRuntime(
            Map.of("CLAUDE_CODE_EXTRA_METADATA", "not json at all"));

        var metadata = LlmClientAdapter.requestMetadata("sess-2");
        var userId = JsonUtils.getMapper().readTree(metadata.get("user_id").asText());
        assertTrue(userId.has("device_id") && userId.has("session_id"),
            "malformed extra degrades to identity-only metadata");
    }
}

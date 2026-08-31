package com.claudecode.api;

import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.ServerToolUseBlock;
import com.claudecode.core.message.ServerToolResultBlock;
import com.claudecode.core.message.WebSearchToolResultBlock;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for stream event parsing — verifies that SSE {@code (type, data)}
 * pairs are correctly translated to our StreamEvent interface via
 * {@link AnthropicSdkClient#parseEvent} (CP-1: API protocol correctness).
 * Calls {@code parseEvent} directly rather than round-tripping through raw
 * SSE text — the SSE line-splitting itself is okhttp-sse's job now
 * ({@link EventSourceStreamBridge}), a well-tested third-party concern this
 * class doesn't need to re-verify.
 */
class StreamEventParsingTest {

    private static final AnthropicSdkClient CLIENT = new AnthropicSdkClient(
            new ApiConfig.AnthropicConfig("test-key", null, "claude-sonnet-4-6", null));

    @Test
    void parsesCompleteMessageStream() {
        StreamEvent start = CLIENT.parseEvent("message_start",
                "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_01\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[],\"model\":\"claude-sonnet-4-20250514\",\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":25,\"output_tokens\":1,\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":0}}}");
        assertInstanceOf(StreamEvent.MessageStart.class, start);
        StreamEvent.MessageStart messageStart = (StreamEvent.MessageStart) start;
        assertEquals("msg_01", messageStart.message().id());
        assertEquals("assistant", messageStart.message().role());
        assertEquals("message_start", messageStart.rawEvent().path("type").asText());
        assertTrue(messageStart.rawEvent().path("message").path("stop_reason").isNull());

        StreamEvent blockStartEvent = CLIENT.parseEvent("content_block_start",
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}");
        assertInstanceOf(StreamEvent.ContentBlockStart.class, blockStartEvent);
        StreamEvent.ContentBlockStart blockStart = (StreamEvent.ContentBlockStart) blockStartEvent;
        assertEquals(0, blockStart.index());
        assertInstanceOf(TextBlock.class, blockStart.contentBlock());
        assertEquals("", blockStart.rawEvent().path("content_block").path("text").asText());

        StreamEvent delta1Event = CLIENT.parseEvent("content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}");
        assertInstanceOf(StreamEvent.ContentBlockDelta.class, delta1Event);
        StreamEvent.ContentBlockDelta delta1 = (StreamEvent.ContentBlockDelta) delta1Event;
        assertEquals(0, delta1.index());
        assertInstanceOf(Delta.TextDelta.class, delta1.delta());
        assertEquals("Hello", ((Delta.TextDelta) delta1.delta()).text());
        assertEquals("text_delta", delta1.rawEvent().path("delta").path("type").asText());

        StreamEvent delta2Event = CLIENT.parseEvent("content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\" world\"}}");
        assertEquals(" world", ((Delta.TextDelta) ((StreamEvent.ContentBlockDelta) delta2Event).delta()).text());

        StreamEvent blockStopEvent = CLIENT.parseEvent("content_block_stop",
                "{\"type\":\"content_block_stop\",\"index\":0}");
        assertInstanceOf(StreamEvent.ContentBlockStop.class, blockStopEvent);
        assertEquals(0, ((StreamEvent.ContentBlockStop) blockStopEvent).index());

        StreamEvent messageDeltaEvent = CLIENT.parseEvent("message_delta",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":12,\"input_tokens\":0,\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":0}}");
        assertInstanceOf(StreamEvent.MessageDelta.class, messageDeltaEvent);
        assertEquals("end_turn", ((StreamEvent.MessageDelta) messageDeltaEvent).delta().stopReason());
        assertTrue(((StreamEvent.MessageDelta) messageDeltaEvent).rawEvent()
            .path("delta").path("stop_sequence").isNull());

        StreamEvent messageStopEvent = CLIENT.parseEvent("message_stop", "{\"type\":\"message_stop\"}");
        assertInstanceOf(StreamEvent.MessageStop.class, messageStopEvent);
        assertEquals("message_stop", ((StreamEvent.MessageStop) messageStopEvent)
            .rawEvent().path("type").asText());
    }

    @Test
    void parsesToolUseStream() {
        StreamEvent blockStartEvent = CLIENT.parseEvent("content_block_start",
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_01\",\"name\":\"bash\",\"input\":{}}}");
        assertInstanceOf(StreamEvent.ContentBlockStart.class, blockStartEvent);
        StreamEvent.ContentBlockStart blockStart = (StreamEvent.ContentBlockStart) blockStartEvent;
        assertInstanceOf(ToolUseBlock.class, blockStart.contentBlock());
        ToolUseBlock toolUse = (ToolUseBlock) blockStart.contentBlock();
        assertEquals("toolu_01", toolUse.id());
        assertEquals("bash", toolUse.name());

        StreamEvent deltaEvent = CLIENT.parseEvent("content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"command\\\": \\\"ls\\\"}\"}}");
        assertInstanceOf(StreamEvent.ContentBlockDelta.class, deltaEvent);
        assertInstanceOf(Delta.InputJsonDelta.class, ((StreamEvent.ContentBlockDelta) deltaEvent).delta());

        StreamEvent messageDeltaEvent = CLIENT.parseEvent("message_delta",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":30,\"input_tokens\":0,\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":0}}");
        assertEquals("tool_use", ((StreamEvent.MessageDelta) messageDeltaEvent).delta().stopReason());
    }

    @Test
    void parsesAnthropicServerToolUseAndCompleteWebSearchResult() {
        StreamEvent start = CLIENT.parseEvent("content_block_start",
            "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"server_tool_use\",\"id\":\"srv_1\",\"name\":\"web_search\",\"input\":{}}}");
        var serverTool = assertInstanceOf(ServerToolUseBlock.class,
            ((StreamEvent.ContentBlockStart) start).contentBlock());
        assertEquals("srv_1", serverTool.id());
        assertEquals("web_search", serverTool.name());

        StreamEvent result = CLIENT.parseEvent("content_block_start",
            "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"web_search_tool_result\",\"tool_use_id\":\"srv_1\",\"content\":[{\"title\":\"OpenCode\",\"url\":\"https://opencode.ai\"}]}}");
        var searchResult = assertInstanceOf(WebSearchToolResultBlock.class,
            ((StreamEvent.ContentBlockStart) result).contentBlock());
        assertEquals("srv_1", searchResult.toolUseId());
        assertEquals("OpenCode", searchResult.content().getFirst().title());
    }

    @Test
    void parsesOtherAnthropicServerToolResultsWithoutLosingPayload() {
        StreamEvent result = CLIENT.parseEvent("content_block_start",
            "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{"
                + "\"type\":\"code_execution_tool_result\",\"tool_use_id\":\"srv_2\","
                + "\"content\":{\"type\":\"code_execution_result\",\"stdout\":\"2\\n\"}}}");

        var block = assertInstanceOf(ServerToolResultBlock.class,
            ((StreamEvent.ContentBlockStart) result).contentBlock());
        assertEquals("srv_2", block.toolUseId());
        assertEquals("code_execution", block.name());
        assertEquals("code_execution_tool_result", block.providerType());
        assertEquals("2\n", block.content().path("stdout").asText());
        assertFalse(block.isError());
    }

    @Test
    void messageDeltaCarriesRefusalStopDetails() {
        StreamEvent event = CLIENT.parseEvent("message_delta",
            "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"refusal\",\"stop_sequence\":null,"
                + "\"stop_details\":{\"category\":\"bio\",\"explanation\":\"flagged\"}},"
                + "\"usage\":{\"output_tokens\":0,\"input_tokens\":9,\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":0}}");

        StreamEvent.MessageDelta delta = assertInstanceOf(StreamEvent.MessageDelta.class, event);
        assertEquals("refusal", delta.delta().stopReason());
        assertEquals("bio", delta.delta().stopDetails().category());
        assertEquals("flagged", delta.delta().stopDetails().explanation());
    }

    @Test
    void aNullStopDetailsStaysNullInsteadOfBecomingAnEmptyEnvelope() {
        StreamEvent event = CLIENT.parseEvent("message_delta",
            "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null,\"stop_details\":null},"
                + "\"usage\":{\"output_tokens\":3,\"input_tokens\":1,\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":0}}");

        assertNull(((StreamEvent.MessageDelta) event).delta().stopDetails());
    }

    @Test
    void parsesPingEvent() {        StreamEvent ping = CLIENT.parseEvent("ping", "{}");
        assertInstanceOf(StreamEvent.Ping.class, ping);
    }

    @Test
    void unknownEventType_returnsNull() {
        assertNull(CLIENT.parseEvent("some_future_event_type", "{}"));
    }

    @Test
    void malformedData_returnsErrorEvent() {
        StreamEvent event = CLIENT.parseEvent("message_start", "not valid json");
        assertInstanceOf(StreamEvent.Error.class, event);
    }
}

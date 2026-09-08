package com.claudecode.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ThinkingBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Round-trip tests for the outbound SSE encoder: every event
 * {@link StreamEventSseCodec#encode} produces must parse back through
 * {@link AnthropicSdkClient#parseEvent} into an equal event. This is the
 * "the gateway's protocol face is verified by the product's own client face"
 * contract — encode and parse are written in the same module on purpose.
 */
class StreamEventSseCodecTest {

    private static final AnthropicSdkClient CLIENT = new AnthropicSdkClient(
        new ApiConfig.AnthropicConfig("test-key", null, "claude-sonnet-4-6", null));

    @Test
    void messageStartRoundTrips() {
        StreamEvent original = new StreamEvent.MessageStart(new ApiMessage(
            "msg_01", "message", "assistant", List.of(),
            "claude-sonnet-4-6", null, null, new Usage(25, 1, 0, 0), null));
        StreamEventSseCodec.EncodedEvent encoded = StreamEventSseCodec.encode(original);
        assertEquals("message_start", encoded.type());

        StreamEvent parsed = CLIENT.parseEvent(encoded.type(), encoded.data());
        StreamEvent.MessageStart decoded = assertInstanceOf(
            StreamEvent.MessageStart.class, parsed);
        assertEquals("msg_01", decoded.message().id());
        assertEquals("assistant", decoded.message().role());
        assertEquals("claude-sonnet-4-6", decoded.message().model());
        assertEquals(25, decoded.message().usage().inputTokens());
    }

    @Test
    void textBlockStartAndDeltaRoundTrip() {
        StreamEvent blockStart = new StreamEvent.ContentBlockStart(0, new TextBlock(""));
        StreamEventSseCodec.EncodedEvent encodedStart = StreamEventSseCodec.encode(blockStart);
        assertEquals("content_block_start", encodedStart.type());

        StreamEvent parsedStart = CLIENT.parseEvent(encodedStart.type(), encodedStart.data());
        StreamEvent.ContentBlockStart decodedStart = assertInstanceOf(
            StreamEvent.ContentBlockStart.class, parsedStart);
        assertEquals(0, decodedStart.index());
        assertInstanceOf(TextBlock.class, decodedStart.contentBlock());

        StreamEvent delta = new StreamEvent.ContentBlockDelta(0, new Delta.TextDelta("Hello"));
        StreamEventSseCodec.EncodedEvent encodedDelta = StreamEventSseCodec.encode(delta);
        StreamEvent parsedDelta = CLIENT.parseEvent(encodedDelta.type(), encodedDelta.data());
        StreamEvent.ContentBlockDelta decodedDelta = assertInstanceOf(
            StreamEvent.ContentBlockDelta.class, parsedDelta);
        assertEquals("Hello", ((Delta.TextDelta) decodedDelta.delta()).text());
    }

    @Test
    void thinkingBlockRoundTrips() {
        StreamEvent blockStart = new StreamEvent.ContentBlockStart(
            0, new ThinkingBlock("", null));
        StreamEventSseCodec.EncodedEvent encoded = StreamEventSseCodec.encode(blockStart);
        StreamEvent parsed = CLIENT.parseEvent(encoded.type(), encoded.data());
        StreamEvent.ContentBlockStart decoded = assertInstanceOf(
            StreamEvent.ContentBlockStart.class, parsed);
        assertInstanceOf(ThinkingBlock.class, decoded.contentBlock());
    }

    @Test
    void toolUseBlockStartRoundTrips() {
        StreamEvent blockStart = new StreamEvent.ContentBlockStart(
            0, new ToolUseBlock("toolu_01", "bash", null));
        StreamEventSseCodec.EncodedEvent encoded = StreamEventSseCodec.encode(blockStart);
        StreamEvent parsed = CLIENT.parseEvent(encoded.type(), encoded.data());
        StreamEvent.ContentBlockStart decoded = assertInstanceOf(
            StreamEvent.ContentBlockStart.class, parsed);
        ToolUseBlock block = assertInstanceOf(ToolUseBlock.class, decoded.contentBlock());
        assertEquals("toolu_01", block.id());
        assertEquals("bash", block.name());
    }

    @Test
    void contentBlockStopRoundTrips() {
        StreamEvent blockStop = new StreamEvent.ContentBlockStop(0);
        StreamEventSseCodec.EncodedEvent encoded = StreamEventSseCodec.encode(blockStop);
        StreamEvent parsed = CLIENT.parseEvent(encoded.type(), encoded.data());
        assertEquals(0, ((StreamEvent.ContentBlockStop) parsed).index());
    }

    @Test
    void messageDeltaWithUsageRoundTrips() {
        StreamEvent delta = new StreamEvent.MessageDelta(
            new MessageDeltaData("end_turn", null), new Usage(0, 12, 0, 0));
        StreamEventSseCodec.EncodedEvent encoded = StreamEventSseCodec.encode(delta);
        StreamEvent parsed = CLIENT.parseEvent(encoded.type(), encoded.data());
        StreamEvent.MessageDelta decoded = assertInstanceOf(
            StreamEvent.MessageDelta.class, parsed);
        assertEquals("end_turn", decoded.delta().stopReason());
        assertEquals(12, decoded.usage().outputTokens());
    }

    @Test
    void messageStopRoundTrips() {
        StreamEventSseCodec.EncodedEvent encoded =
            StreamEventSseCodec.encode(new StreamEvent.MessageStop());
        assertEquals("message_stop", encoded.type());
        StreamEvent parsed = CLIENT.parseEvent(encoded.type(), encoded.data());
        assertInstanceOf(StreamEvent.MessageStop.class, parsed);
    }

    @Test
    void wireDataCarriesTypeField() {
        // The data body must be self-describing: real Anthropic SSE clients
        // read data[].type as the canonical discriminator.
        StreamEventSseCodec.EncodedEvent encoded = StreamEventSseCodec.encode(
            new StreamEvent.MessageStop());
        JsonNode data = JsonUtils.parseTree(encoded.data());
        assertEquals("message_stop", data.path("type").asText());
    }

    @Test
    void transportInternalEventsHaveNoWireShape() {
        assertNull(StreamEventSseCodec.encode(new StreamEvent.Ping()));
        assertNull(StreamEventSseCodec.encode(
            new StreamEvent.RequestTiming(0)));
    }
}

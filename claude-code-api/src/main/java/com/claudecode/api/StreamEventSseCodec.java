package com.claudecode.api;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Encodes {@link StreamEvent} into Anthropic SSE {@code (type, data)} pairs —
 * the outbound symmetric face of {@link AnthropicSdkClient#parseEvent}.
 *
 * <p>The gateway serves the Messages protocol from live session events, so a
 * turn's block-level projection is serialized back into the same wire shape
 * the product's own client parses. Encoding is a pure function of the event;
 * the {@code rawEvent} payloads carried by client-side events are ignored —
 * gateway events are synthesized without a raw wire original.
 *
 * <ul>
 *   <li>covers the outbound face of the same wire types
 *       {@code AnthropicSdkClient.parseEvent} parses (message_start,
 *       content_block_start/delta/stop, message_delta, message_stop).</li>
 * </ul>
 */
public final class StreamEventSseCodec {

    private StreamEventSseCodec() {}

    /** One encoded SSE event: the {@code event:} name and the {@code data:} JSON body. */
    public record EncodedEvent(String type, String data) {}

    /** Encodes one event; returns {@code null} for events that have no wire shape. */
    public static EncodedEvent encode(StreamEvent event) {
        return switch (event) {
            case StreamEvent.MessageStart messageStart -> new EncodedEvent(
                "message_start", encodeMessageStart(messageStart));
            case StreamEvent.ContentBlockStart blockStart -> new EncodedEvent(
                "content_block_start", encodeContentBlockStart(blockStart));
            case StreamEvent.ContentBlockDelta blockDelta -> new EncodedEvent(
                "content_block_delta", encodeContentBlockDelta(blockDelta));
            case StreamEvent.ContentBlockStop blockStop -> new EncodedEvent(
                "content_block_stop", encodeContentBlockStop(blockStop));
            case StreamEvent.MessageDelta messageDelta -> new EncodedEvent(
                "message_delta", encodeMessageDelta(messageDelta));
            case StreamEvent.MessageStop _ -> new EncodedEvent(
                "message_stop", "{\"type\":\"message_stop\"}");
            // Transport-internal events have no server wire shape; the gateway
            // emits its own ping frames and turns Error payloads into an
            // HTTP-level error event rather than replaying client framing.
            case StreamEvent.Ping _, StreamEvent.Error _, StreamEvent.RequestTiming _ -> null;
        };
    }

    private static String encodeMessageStart(StreamEvent.MessageStart event) {
        ObjectNode root = object();
        root.put("type", "message_start");
        root.set("message", JsonUtils.getMapper().valueToTree(event.message()));
        return root.toString();
    }

    private static String encodeContentBlockStart(StreamEvent.ContentBlockStart event) {
        ObjectNode root = object();
        root.put("type", "content_block_start");
        root.put("index", event.index());
        root.set("content_block", JsonUtils.getMapper().valueToTree(event.contentBlock()));
        return root.toString();
    }

    private static String encodeContentBlockDelta(StreamEvent.ContentBlockDelta event) {
        ObjectNode root = object();
        root.put("type", "content_block_delta");
        root.put("index", event.index());
        root.set("delta", JsonUtils.getMapper().valueToTree(event.delta()));
        return root.toString();
    }

    private static String encodeContentBlockStop(StreamEvent.ContentBlockStop event) {
        ObjectNode root = object();
        root.put("type", "content_block_stop");
        root.put("index", event.index());
        return root.toString();
    }

    private static String encodeMessageDelta(StreamEvent.MessageDelta event) {
        ObjectNode root = object();
        root.put("type", "message_delta");
        root.set("delta", JsonUtils.getMapper().valueToTree(event.delta()));
        JsonNode usage = JsonUtils.getMapper().valueToTree(event.usage());
        if (usage.isObject() && !usage.isEmpty()) {
            root.set("usage", usage);
        }
        return root.toString();
    }

    private static ObjectNode object() {
        return JsonUtils.getMapper().createObjectNode();
    }
}

package com.claudecode.api;

import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.Usage;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Unified stream event types (SDK-agnostic).
 * Maps to Anthropic SSE event types: message_start, content_block_start,
 * content_block_delta, content_block_stop, message_delta, message_stop.
 *
 * <ul>
 *   <li>retain each complete
 *       {@code BetaRawMessageStreamEvent} for SDK partial-message replay while
 *       also exposing typed fields and the HTTP request id to the Java query
 *       loop.</li>
 * </ul>
 */
public sealed interface StreamEvent permits
        StreamEvent.MessageStart,
        StreamEvent.ContentBlockStart,
        StreamEvent.ContentBlockDelta,
        StreamEvent.ContentBlockStop,
        StreamEvent.MessageDelta,
        StreamEvent.MessageStop,
        StreamEvent.Error,
        StreamEvent.Ping,
        StreamEvent.RequestTiming {

    /** Internal transport metadata; adapters consume it without publishing a core event. */
    record RequestTiming(long lastAttemptStartMs) implements StreamEvent {}

    /** Fired when a new message begins. Contains the initial ApiMessage shell. */
    record MessageStart(ApiMessage message, JsonNode rawEvent, String requestId)
            implements StreamEvent {
        public MessageStart(ApiMessage message) {
            this(message, null, null);
        }

        public MessageStart(ApiMessage message, JsonNode rawEvent) {
            this(message, rawEvent, null);
        }
    }

    /** Fired when a new content block begins within the message. */
    record ContentBlockStart(int index, ContentBlock contentBlock, JsonNode rawEvent)
            implements StreamEvent {
        public ContentBlockStart(int index, ContentBlock contentBlock) {
            this(index, contentBlock, null);
        }
    }

    /** Fired when a content block receives incremental data. */
    record ContentBlockDelta(int index, Delta delta, JsonNode rawEvent) implements StreamEvent {
        public ContentBlockDelta(int index, Delta delta) {
            this(index, delta, null);
        }
    }

    /** Fired when a content block is complete. */
    record ContentBlockStop(int index, JsonNode rawEvent) implements StreamEvent {
        public ContentBlockStop(int index) {
            this(index, null);
        }
    }

    /** Fired when the message-level metadata updates (stop reason, usage). */
    record MessageDelta(MessageDeltaData delta, Usage usage, JsonNode rawEvent)
            implements StreamEvent {
        public MessageDelta(MessageDeltaData delta, Usage usage) {
            this(delta, usage, null);
        }
    }

    /** Fired when the entire message is complete. */
    record MessageStop(JsonNode rawEvent) implements StreamEvent {
        public MessageStop() {
            this(null);
        }
    }

    /** Fired when an error occurs during streaming. */
    record Error(ApiException exception, JsonNode rawEvent) implements StreamEvent {
        public Error(ApiException exception) {
            this(exception, null);
        }
    }

    /** Keep-alive ping event. */
    record Ping() implements StreamEvent {}
}

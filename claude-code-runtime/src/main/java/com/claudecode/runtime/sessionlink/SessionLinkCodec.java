package com.claudecode.runtime.sessionlink;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.Set;

/**
 * Bounded, strict JSON codec for one Session Link frame.
 */
@Explanation("Strict local Session Link v1 wire codec")
public final class SessionLinkCodec {

    public static final int DEFAULT_MAX_FRAME_BYTES = 16 * 1024 * 1024;
    public static final int MAX_FRAME_BYTES = 64 * 1024 * 1024;

    private static final ObjectMapper MAPPER = JsonUtils.getMapper().copy()
        .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true);
    private static final ObjectReader READER = MAPPER.reader();
    private static final Set<String> FRAME_FIELDS = Set.of(
        "protocol", "version", "kind", "name", "id", "reply_to",
        "session_id", "payload", "error");
    private static final Set<String> ERROR_FIELDS = Set.of("code", "message");

    private final int maxFrameBytes;

    public SessionLinkCodec() {
        this(DEFAULT_MAX_FRAME_BYTES);
    }

    public SessionLinkCodec(int maxFrameBytes) {
        if (maxFrameBytes < 1 || maxFrameBytes > MAX_FRAME_BYTES) {
            throw new IllegalArgumentException(
                "maxFrameBytes must be between 1 and " + MAX_FRAME_BYTES);
        }
        this.maxFrameBytes = maxFrameBytes;
    }

    public byte[] encode(SessionLinkFrame frame) {
        if (frame == null) {
            throw new SessionLinkProtocolException("session-link: frame is required");
        }
        frame.validate();
        try {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("protocol", frame.protocol());
            node.put("version", frame.version());
            node.put("kind", frame.kind().wireName());
            node.put("name", frame.name());
            putText(node, "id", frame.id());
            putText(node, "reply_to", frame.replyTo());
            putText(node, "session_id", frame.sessionId());
            if (frame.payload() != null) node.set("payload", frame.payload());
            if (frame.error() != null) {
                ObjectNode error = node.putObject("error");
                putText(error, "code", frame.error().code());
                error.put("message", frame.error().message());
            }
            byte[] encoded = MAPPER.writeValueAsBytes(node);
            checkSize(encoded.length);
            return encoded;
        } catch (IOException e) {
            throw new SessionLinkProtocolException("session-link: encode frame", e);
        }
    }

    public SessionLinkFrame decode(byte[] encoded) {
        if (encoded == null) {
            throw new SessionLinkProtocolException("session-link: frame is required");
        }
        checkSize(encoded.length);
        try {
            JsonNode parsed = READER.readTree(encoded);
            if (!(parsed instanceof ObjectNode node)) {
                throw new SessionLinkProtocolException(
                    "session-link: frame must be a JSON object");
            }
            rejectUnknownFields(node, FRAME_FIELDS, "frame");
            String protocol = requiredText(node, "protocol");
            int version = requiredInt(node, "version");
            SessionLinkFrame.Kind kind = SessionLinkFrame.Kind.fromWireName(
                requiredText(node, "kind"));
            String name = requiredText(node, "name");
            String id = optionalText(node, "id");
            String replyTo = optionalText(node, "reply_to");
            String sessionId = optionalText(node, "session_id");
            JsonNode payload = node.get("payload");
            SessionLinkFrame.ErrorDetail error = decodeError(node.get("error"));
            SessionLinkFrame frame = new SessionLinkFrame(
                protocol, version, kind, name, id, replyTo, sessionId, payload, error);
            frame.validate();
            return frame;
        } catch (IOException e) {
            throw new SessionLinkProtocolException(
                "session-link: decode frame: " + e.getMessage(), e);
        }
    }

    private void checkSize(int size) {
        if (size > maxFrameBytes) {
            throw new SessionLinkProtocolException(
                "session-link: frame size " + size + " exceeds limit " + maxFrameBytes);
        }
    }

    private static SessionLinkFrame.ErrorDetail decodeError(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (!(value instanceof ObjectNode error)) {
            throw new SessionLinkProtocolException(
                "session-link: error must be a JSON object");
        }
        rejectUnknownFields(error, ERROR_FIELDS, "error");
        return new SessionLinkFrame.ErrorDetail(
            optionalText(error, "code"), requiredText(error, "message"));
    }

    private static void rejectUnknownFields(
            ObjectNode node, Set<String> allowed, String location) {
        node.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                throw new SessionLinkProtocolException(
                    "session-link: unrecognized field " + field + " in " + location);
            }
        });
    }

    private static String requiredText(ObjectNode node, String field) {
        String value = optionalText(node, field);
        if (value == null) {
            throw new SessionLinkProtocolException(
                "session-link: missing or non-text field " + field);
        }
        return value;
    }

    private static String optionalText(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) {
            throw new SessionLinkProtocolException(
                "session-link: field " + field + " must be text");
        }
        return value.textValue();
    }

    private static int requiredInt(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new SessionLinkProtocolException(
                "session-link: field " + field + " must be an integer");
        }
        return value.intValue();
    }

    private static void putText(ObjectNode node, String field, String value) {
        if (value != null) node.put(field, value);
    }
}

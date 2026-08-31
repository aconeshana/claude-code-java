package com.claudecode.runtime.sessionlink;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.annotation.Explanation;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;

/**
 * Strict v1 envelope for the local Session Link Protocol.
 */
@Explanation("Adds a local semantic multi-end transport for linked sessions")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionLinkFrame(
        String protocol,
        int version,
        Kind kind,
        String name,
        String id,
        @JsonProperty("reply_to") String replyTo,
        @JsonProperty("session_id") String sessionId,
        JsonNode payload,
        ErrorDetail error) {

    public static final String PROTOCOL = "session-link";
    public static final int VERSION = 1;

    /** Supported envelope kinds. */
    public enum Kind {
        REQUEST,
        RESPONSE,
        EVENT,
        ERROR;

        @JsonValue
        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }

        @JsonCreator
        static Kind fromWireName(String value) {
            try {
                return value == null ? null : valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new SessionLinkProtocolException(
                    "session-link: unsupported frame kind " + value, e);
            }
        }
    }

    /** Error payload carried only by error envelopes. */
    public record ErrorDetail(String code, String message) {}

    public static SessionLinkFrame request(
            String id, String name, String sessionId, JsonNode payload) {
        return new SessionLinkFrame(
            PROTOCOL, VERSION, Kind.REQUEST, name, id, null, sessionId, payload, null);
    }

    public static SessionLinkFrame response(
            String replyTo, String name, String sessionId, JsonNode payload) {
        return new SessionLinkFrame(
            PROTOCOL, VERSION, Kind.RESPONSE, name, null, replyTo, sessionId, payload, null);
    }

    public static SessionLinkFrame event(String name, String sessionId, JsonNode payload) {
        return new SessionLinkFrame(
            PROTOCOL, VERSION, Kind.EVENT, name, null, null, sessionId, payload, null);
    }

    public static SessionLinkFrame error(
            String replyTo, String name, String code, String message) {
        return new SessionLinkFrame(
            PROTOCOL, VERSION, Kind.ERROR, name, null, replyTo, null, null,
            new ErrorDetail(code, message));
    }

    void validate() {
        if (!PROTOCOL.equals(protocol)) {
            throw new SessionLinkProtocolException(
                "session-link: unsupported protocol " + protocol);
        }
        if (version != VERSION) {
            throw new SessionLinkProtocolException(
                "session-link: unsupported version " + version);
        }
        if (StringUtils.isBlank(name) || name.length() > 128) {
            throw new SessionLinkProtocolException("session-link: invalid message name");
        }
        if (length(id) > 128 || length(replyTo) > 128 || length(sessionId) > 1024) {
            throw new SessionLinkProtocolException(
                "session-link: frame identifier exceeds limit");
        }
        if (kind == null) {
            throw new SessionLinkProtocolException("session-link: missing frame kind");
        }
        switch (kind) {
            case REQUEST -> {
                if (empty(id) || !empty(replyTo) || error != null) {
                    throw new SessionLinkProtocolException(
                        "session-link: invalid request envelope");
                }
            }
            case RESPONSE -> {
                if (empty(replyTo) || error != null) {
                    throw new SessionLinkProtocolException(
                        "session-link: invalid response envelope");
                }
            }
            case EVENT -> {
                if (!empty(id) || !empty(replyTo) || error != null) {
                    throw new SessionLinkProtocolException(
                        "session-link: invalid event envelope");
                }
            }
            case ERROR -> {
                if (empty(replyTo) || error == null || empty(error.message())) {
                    throw new SessionLinkProtocolException(
                        "session-link: invalid error envelope");
                }
            }
        }
    }

    private static boolean empty(String value) {
        return StringUtils.isEmpty(value);
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }
}

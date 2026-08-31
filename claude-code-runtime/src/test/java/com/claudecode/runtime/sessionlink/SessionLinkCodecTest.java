package com.claudecode.runtime.sessionlink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

class SessionLinkCodecTest {

    @Test
    void roundTripsVersionedRequestEnvelope() {
        SessionLinkCodec codec = new SessionLinkCodec();
        SessionLinkFrame frame = SessionLinkFrame.request(
            "cc-7", "turn.submit", "session-42",
            JsonNodeFactory.instance.objectNode()
                .put("prompt", "inspect the failing test")
                .put("message_id", "om_123"));

        byte[] encoded = codec.encode(frame);
        SessionLinkFrame decoded = codec.decode(encoded);

        assertEquals("""
            {"protocol":"session-link","version":1,"kind":"request","name":"turn.submit","id":"cc-7","session_id":"session-42","payload":{"prompt":"inspect the failing test","message_id":"om_123"}}""",
            new String(encoded, StandardCharsets.UTF_8));
        assertEquals(SessionLinkFrame.PROTOCOL, decoded.protocol());
        assertEquals(SessionLinkFrame.VERSION, decoded.version());
        assertEquals(SessionLinkFrame.Kind.REQUEST, decoded.kind());
        assertEquals("turn.submit", decoded.name());
        assertEquals("cc-7", decoded.id());
        assertEquals("session-42", decoded.sessionId());
        assertEquals("inspect the failing test", decoded.payload().path("prompt").asText());
    }

    @Test
    void rejectsUnknownEnvelopeFields() {
        SessionLinkProtocolException error = assertThrows(SessionLinkProtocolException.class,
            () -> new SessionLinkCodec().decode(json("""
                {"protocol":"session-link","version":1,"kind":"event",
                 "name":"output.text","surprise":true}
                """)));

        assertTrue(Strings.CS.contains(
            error.getMessage().toLowerCase(Locale.ROOT), "unrecognized field"),
            error.getMessage());
    }

    @Test
    void rejectsUnsupportedVersion() {
        SessionLinkProtocolException error = assertThrows(SessionLinkProtocolException.class,
            () -> new SessionLinkCodec().decode(json("""
                {"protocol":"session-link","version":2,"kind":"event","name":"output.text"}
                """)));

        assertTrue(Strings.CS.contains(error.getMessage(), "unsupported version"));
    }

    @Test
    void rejectsOversizedFrame() {
        byte[] frame = json("""
            {"protocol":"session-link","version":1,"kind":"event","name":"output.text"}
            """);
        SessionLinkCodec codec = new SessionLinkCodec(frame.length - 1);

        SessionLinkProtocolException error = assertThrows(SessionLinkProtocolException.class,
            () -> codec.decode(frame));

        assertTrue(Strings.CS.contains(error.getMessage(), "exceeds limit"));
    }

    @Test
    void rejectsInvalidEnvelopeShape() {
        SessionLinkFrame invalid = new SessionLinkFrame(
            SessionLinkFrame.PROTOCOL, SessionLinkFrame.VERSION,
            SessionLinkFrame.Kind.REQUEST, "turn.submit", null, null,
            "session-42", JsonNodeFactory.instance.objectNode(), null);

        SessionLinkProtocolException error = assertThrows(SessionLinkProtocolException.class,
            () -> new SessionLinkCodec().encode(invalid));

        assertTrue(Strings.CS.contains(error.getMessage(), "request"));
    }

    private static byte[] json(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}

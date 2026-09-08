package com.claudecode.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SseFrameWriterTest {

    @Test
    void eventFrameCarriesEventIdAndDataLines() {
        String frame = new String(
            SseFrameWriter.event("message_start", "42", "{\"type\":\"message_start\"}"),
            StandardCharsets.UTF_8);
        assertEquals("event: message_start\nid: 42\ndata: {\"type\":\"message_start\"}\n\n",
            frame);
    }

    @Test
    void multiLineDataEmitsOneDataLinePerNewline() {
        String frame = new String(
            SseFrameWriter.event("output.text", null, "line one\nline two\n"),
            StandardCharsets.UTF_8);
        assertEquals("event: output.text\ndata: line one\ndata: line two\ndata: \n\n", frame);
    }

    @Test
    void nullEventAndIdAreOmitted() {
        String frame = new String(
            SseFrameWriter.event(null, null, "payload"), StandardCharsets.UTF_8);
        assertEquals("data: payload\n\n", frame);
    }

    @Test
    void heartbeatFrameIsIdOnlyCommentForm() {
        // An empty frame still ends with the blank line; with no event/data
        // lines it refreshes the Last-Event-ID cursor without client visibility.
        String frame = new String(
            SseFrameWriter.heartbeat("7"), StandardCharsets.UTF_8);
        assertEquals("id: 7\n\n", frame);
    }
}

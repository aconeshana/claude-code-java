package com.claudecode.gateway;

import java.nio.charset.StandardCharsets;
import org.apache.commons.lang3.StringUtils;

/**
 * Encodes one SSE event into the {@code text/event-stream} wire form.
 *
 * <p>SSE frames are {@code event}/{@code id}/{@code data} lines terminated by
 * one blank line; {@code data} spans one line per newline in the payload.
 * A comment frame ({@code : ping}) keeps idle connections alive through
 * proxies without surfacing as a client event.
 */
public final class SseFrameWriter {

    private SseFrameWriter() {}

    /** The full frame bytes, newline-terminated and ready to write and flush. */
    public static byte[] event(String event, String id, String data) {
        StringBuilder frame = new StringBuilder();
        if (StringUtils.isNotEmpty(event)) frame.append("event: ").append(event).append('\n');
        if (StringUtils.isNotEmpty(id)) frame.append("id: ").append(id).append('\n');
        if (data != null) {
            for (String line : data.split("\n", -1)) {
                frame.append("data: ").append(line).append('\n');
            }
        }
        frame.append('\n');
        return frame.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** An id-only frame that refreshes the client's {@code Last-Event-ID} cursor. */
    public static byte[] heartbeat(String id) {
        return event(null, id, null);
    }
}

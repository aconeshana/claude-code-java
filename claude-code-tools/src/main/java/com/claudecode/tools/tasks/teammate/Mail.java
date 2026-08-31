package com.claudecode.tools.tasks.teammate;

import java.util.UUID;

/**
 * A single mailbox message between a leader (the main coordinator) and an in-process teammate, or
 * between teammates.
 */
public record Mail(
    /** Message type, one of the protocol constants on {@link MailTypes}. */
    String type,
    /** Correlation id; matches a request to its response (empty for unsolicited). */
    String requestId,
    /** Sender agent name. */
    String from,
    /** Recipient agent name, or {@code "*"} for broadcast. */
    String to,
    /** Free-form payload (approval feedback, permission tool spec, user text, ...). */
    String payload
) {

    /** Convenience constructor for an unsolicited (no requestId) message. */
    public static Mail of(String type, String from, String to, String payload) {
        return new Mail(type, "", from, to, payload);
    }

    /** Builds the matching response to {@code request} (preserves requestId, swaps to/from). */
    public static Mail reply(Mail request, String type, String from, String payload) {
        return new Mail(type, request.requestId(), from, request.from(), payload);
    }

    public static String newRequestId() {
        return UUID.randomUUID().toString();
    }
}

package com.claudecode.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Handler invoked when the server sends the client a JSON-RPC <em>notification</em> — a message
 * with a {@code method} but no {@code id}, meaning the server expects no reply.
 */
@FunctionalInterface
public interface NotificationHandler {
    /**
     * @param params raw {@code params} object from the notification, or
     *               {@code null} if the notification had none.
     */
    void handle(JsonNode params);
}

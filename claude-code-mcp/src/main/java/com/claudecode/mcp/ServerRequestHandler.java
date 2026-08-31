package com.claudecode.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Handler invoked when the server sends the client a JSON-RPC <em>request</em> (a message with both
 * {@code id} and {@code method} fields).
 */
@FunctionalInterface
public interface ServerRequestHandler {
    /**
     * @param params raw {@code params} object from the JSON-RPC request, or
     *               {@code null} if the request had none. Handlers must
     *               tolerate both shapes.
     * @return the value to put under the reply's {@code result} field. Return
     *         a Jackson {@code ObjectNode} for standard object results, or
     *         {@code null} to reply with {@code result: null} (rarely useful).
     */
    JsonNode handle(JsonNode params);
}

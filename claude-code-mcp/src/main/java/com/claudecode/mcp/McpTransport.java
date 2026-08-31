package com.claudecode.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Transport layer for MCP server communication.
 * Implementations handle the actual wire protocol (stdio, SSE, etc.).
 */
public interface McpTransport extends AutoCloseable {

    /**
     * Sends a JSON-RPC request and returns the result.
     *
     * @param method the JSON-RPC method name
     * @param params the parameters as a JSON node
     * @return the result JSON node from the response
     * @throws McpException if the request fails or the server returns an error
     */
    JsonNode sendRequest(String method, JsonNode params);

    /**
     * Sends a JSON-RPC <em>notification</em> — a fire-and-forget message with
     * no {@code id} field and no reply expected. Used for
     * {@code notifications/initialized} to finalize the MCP handshake and
     * any future client-side notifications.
     *
     * <p>Default implementation throws — transports that support the wire
     * format (all three: stdio / sse / http) override it. Any caller that
     * hits the default has picked a transport that can't emit notifications,
     * which is a wiring bug rather than a runtime condition.
     */
    default void sendNotification(String method, JsonNode params) {
        throw new McpException(
            "Transport " + getClass().getSimpleName() + " does not implement sendNotification");
    }

    /**
     * Returns true if the transport is currently connected and usable.
     */
    boolean isConnected();

    /**
     * Registers a handler for a server-initiated JSON-RPC request. When the
     * server sends {@code {"id": N, "method": "foo", ...}}, the handler is
     * invoked with the {@code params} node and its return value becomes the
     * reply's {@code result}. Multiple calls with the same {@code method}
     * overwrite the previous handler.
     *
     * <p>Default implementation is a no-op — a transport without a persistent
     * inbound stream would ignore the registration and its server requests
     * would surface as "Method not found" from the dispatcher, matching the
     * spec. All three shipped transports (stdio / sse / http) override this.
     */
    default void onServerRequest(String method, ServerRequestHandler handler) {
        // no-op for transports without a persistent inbound stream
    }

    /**
     * Registers a handler for a server-initiated JSON-RPC notification (a
     * message with a {@code method} but no {@code id}). Notifications are
     * fire-and-forget — handler exceptions are swallowed by the dispatcher.
     */
    default void onNotification(String method, NotificationHandler handler) {
        // no-op for transports without a persistent inbound stream
    }
}

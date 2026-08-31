package com.claudecode.mcp;

import com.claudecode.core.engine.AbortController;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * Non-owning view of a connected MCP server.
 */
public interface McpConnectionView {

    /** Returns the configured MCP server identifier. */
    String getServerId();

    /** Returns the capabilities advertised during initialize. */
    JsonNode getServerCapabilities();

    /** Returns the serverInfo object advertised during initialize. */
    JsonNode getServerInfo();

    /** Returns whether the server declared the given top-level capability. */
    boolean hasCapability(String key);

    /** Sends a request through the manager-owned connection. */
    JsonNode sendRequest(String method, JsonNode params);

    /** Sends a tools/call request, including SDK abort propagation when supported. */
    JsonNode sendToolRequest(JsonNode params, AbortController abortController);

    /**
     * Adapts an existing manager-owned connection for tests and internal
     * bridges that already receive a connection from the lifecycle owner.
     */
    static McpConnectionView of(McpConnection connection) {
        Objects.requireNonNull(connection, "connection");
        return new McpConnectionView() {
            @Override public String getServerId() {
                return connection.getServerId();
            }

            @Override public JsonNode getServerCapabilities() {
                return connection.getServerCapabilities();
            }

            @Override public JsonNode getServerInfo() {
                return connection.getServerInfo();
            }

            @Override public boolean hasCapability(String key) {
                return connection.hasCapability(key);
            }

            @Override public JsonNode sendRequest(String method, JsonNode params) {
                return connection.sendRequest(method, params);
            }

            @Override public JsonNode sendToolRequest(JsonNode params, AbortController abortController) {
                return connection.sendToolRequest(params, abortController);
            }
        };
    }
}

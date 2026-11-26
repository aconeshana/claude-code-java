package com.claudecode.cli;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * MCP server status, dynamic configuration, transport, and OAuth operations
 * exposed through the SDK control protocol.
 */
interface SdkMcpControl {

    List<SdkControlRuntime.McpServerStatus> mcpStatus();

    default void configureSdkMcpServers(JsonNode serverNames) {
        throw new UnsupportedOperationException("SDK-hosted MCP servers are unavailable");
    }

    default JsonNode setMcpServers(JsonNode servers) {
        throw new UnsupportedOperationException("Dynamic MCP server updates are unavailable");
    }

    default void deliverMcpMessage(String serverName, JsonNode message) {
        throw new UnsupportedOperationException("SDK MCP message transport is unavailable");
    }

    default void reconnectMcp(String serverName) {
        throw new UnsupportedOperationException("MCP reconnect is unavailable");
    }

    default void toggleMcp(String serverName, boolean enabled) {
        throw new UnsupportedOperationException("MCP toggle is unavailable");
    }

    default void clearMcpAuth(String serverName) {
        throw new UnsupportedOperationException("MCP auth clearing is unavailable");
    }

    default JsonNode authenticateMcp(String serverName) {
        throw new UnsupportedOperationException("MCP authentication is unavailable");
    }

    default void submitMcpOAuthCallback(String serverName, String callbackUrl) {
        throw new UnsupportedOperationException("MCP OAuth callback is unavailable");
    }
}

package com.claudecode.mcp;

import com.claudecode.core.engine.AbortController;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * Represents an active connection to an MCP server.
 * Wraps the transport and caches discovered tools.
 */
public class McpConnection implements AutoCloseable {

    private final McpServerConfig config;
    private final McpTransport transport;
    /**
     * Lazily-populated tool list for this connection.
     */
    private volatile List<McpToolInfo> cachedTools;
    /**
     * Server-declared capabilities from the {@code initialize} handshake (the {@code
     * result.capabilities} object).
     */
    private volatile JsonNode serverCapabilities;
    /** Server-declared {@code serverInfo} from initialize (name / version / title). */
    private volatile JsonNode serverInfo;
    /**
     * Protocol version the server agreed on. Typically echoes the client's
     * requested version; may be downgraded if the server only supports an
     * older MCP spec (e.g. we send {@code "2024-11-05"} and the server
     * returns {@code "2024-11-05"}).
     */
    private volatile String protocolVersion;

    public McpConnection(McpServerConfig config, McpTransport transport) {
        this.config = config;
        this.transport = transport;
    }

    public McpServerConfig getConfig() {
        return config;
    }

    public McpTransport getTransport() {
        return transport;
    }

    /** Sends a request through this manager-owned connection. */
    public JsonNode sendRequest(String method, JsonNode params) {
        return transport.sendRequest(method, params);
    }

    /** Sends the manager-owned MCP tools/call request, including SDK abort propagation. */
    public JsonNode sendToolRequest(JsonNode params, AbortController abortController) {
        if (transport instanceof SdkControlTransport sdk) {
            return sdk.sendRequest("tools/call", params, abortController);
        }
        return transport.sendRequest("tools/call", params);
    }

    public String getServerId() {
        return config.name();
    }

    public List<McpToolInfo> getCachedTools() {
        return cachedTools;
    }

    public void setCachedTools(List<McpToolInfo> tools) {
        this.cachedTools = tools;
    }

    /** See {@link #serverCapabilities}. Returns {@code null} pre-handshake. */
    public JsonNode getServerCapabilities() {
        return serverCapabilities;
    }

    /** Populated once by {@link McpClientManager#connect} after initialize. */
    void setInitializeResult(JsonNode result) {
        if (result == null || !result.isObject()) return;
        this.serverCapabilities = result.get("capabilities");
        this.serverInfo = result.get("serverInfo");
        this.protocolVersion = result.path("protocolVersion").asText(null);
        this.instructions = McpUtils.truncateDescription(result.path("instructions").asText(null));
    }

/**
     * Server-declared usage instructions from initialize (optional per MCP spec).
     */
    private volatile String instructions;

    public String getInstructions() {
        return instructions;
    }

    /** {@code true} when the server declared support for a given capability key. */
    public boolean hasCapability(String key) {
        if (serverCapabilities == null || !serverCapabilities.isObject()) return false;
        return serverCapabilities.has(key);
    }

    /**
     * Returns true when {@code capabilities.experimental[key]} is truthy — matches the MCP
     * "presence-signal" idiom where {@code experimental['claude/channel']: {}} means "I support this".
     */
    public boolean hasExperimentalCapability(String key) {
        if (serverCapabilities == null || !serverCapabilities.isObject()) return false;
        JsonNode exp = serverCapabilities.get("experimental");
        if (exp == null || !exp.isObject()) return false;
        JsonNode val = exp.get(key);
        if (val == null) return false;
        // truthy: non-empty object, true boolean, non-zero number, non-empty string
        if (val.isBoolean()) return val.booleanValue();
        if (val.isNumber()) return val.numberValue().intValue() != 0;
        if (val.isTextual()) return !val.textValue().isEmpty();
        if (val.isObject()) return true;   // {} is truthy — the standard presence signal
        return false;
    }

    public JsonNode getServerInfo() {
        return serverInfo;
    }

    public String getProtocolVersion() {
        return protocolVersion;
    }

    public boolean isConnected() {
        return transport.isConnected();
    }

    @Override
    public void close() throws Exception {
        transport.close();
    }
}

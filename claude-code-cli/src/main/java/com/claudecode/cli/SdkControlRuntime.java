package com.claudecode.cli;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Session-scoped operations exposed through the SDK stdio control protocol.
 */
interface SdkControlRuntime {

    record McpServerStatus(
        String name,
        String status,
        JsonNode serverInfo,
        String error,
        JsonNode config,
        String scope,
        JsonNode tools,
        JsonNode capabilities
    ) {
        McpServerStatus(String name, String status) {
            this(name, status, null, null, null, null, null, null);
        }
    }

    record RewindFilesResult(boolean canRewind, String error,
                             List<String> filesChanged,
                             Integer insertions, Integer deletions) {
        public RewindFilesResult {
            filesChanged = filesChanged != null ? List.copyOf(filesChanged) : null;
        }
    }

    List<McpServerStatus> mcpStatus();

    JsonNode contextUsage();

    RewindFilesResult rewindFiles(String userMessageId, boolean dryRun);

    boolean cancelAsyncMessage(String messageUuid);

    void seedReadState(String path, long mtime);

    void stopTask(String taskId);

    default boolean backgroundTasks(String toolUseId) {
        throw new UnsupportedOperationException("Backgrounding tasks is unavailable");
    }

    JsonNode settings();

    default void configureHooks(JsonNode hooks) {
        throw new UnsupportedOperationException("SDK hook callbacks are unavailable");
    }

    default void configureSdkMcpServers(JsonNode serverNames) {
        throw new UnsupportedOperationException("SDK-hosted MCP servers are unavailable");
    }

    default void configureSupportedDialogKinds(JsonNode dialogKinds) {
        // Runtimes without an outbound dialog broker have nothing to configure.
    }

    /** Completes deferred initialize-time work that must run off the stdin reader. */
    default void prepareForTurn() {
        // Most runtimes have no deferred first-turn work.
    }

    default JsonNode setMcpServers(JsonNode servers) {
        throw new UnsupportedOperationException("Dynamic MCP server updates are unavailable");
    }

    default void deliverMcpMessage(String serverName, JsonNode message) {
        throw new UnsupportedOperationException("SDK MCP message transport is unavailable");
    }

    default JsonNode reloadPlugins() {
        throw new UnsupportedOperationException("Plugin reload is unavailable");
    }

    default JsonNode reloadSkills() {
        throw new UnsupportedOperationException("Skill reload is unavailable");
    }

    default JsonNode readFile(String path, Long maxBytes, String encoding) {
        throw new UnsupportedOperationException("SDK file reads are unavailable");
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

    default void applyFlagSettings(JsonNode settings) {
        throw new UnsupportedOperationException("Flag settings are unavailable");
    }

    default void updateEnvironmentVariables(JsonNode variables) {
        throw new UnsupportedOperationException("Environment updates are unavailable");
    }

    default CompletableFuture<String> generateSessionTitle(String description, boolean persist) {
        return CompletableFuture.failedFuture(
            new UnsupportedOperationException("SDK session title generation is unavailable"));
    }

    default CompletableFuture<String> sideQuestion(String question) {
        return CompletableFuture.failedFuture(
            new UnsupportedOperationException("SDK side questions are unavailable"));
    }

    static SdkControlRuntime unavailable() {
        return new SdkControlRuntime() {
            private UnsupportedOperationException unsupported() {
                return new UnsupportedOperationException("SDK control runtime is unavailable");
            }

            @Override public List<McpServerStatus> mcpStatus() { throw unsupported(); }
            @Override public JsonNode contextUsage() { throw unsupported(); }
            @Override public RewindFilesResult rewindFiles(String id, boolean dryRun) { throw unsupported(); }
            @Override public boolean cancelAsyncMessage(String uuid) { throw unsupported(); }
            @Override public void seedReadState(String path, long mtime) { throw unsupported(); }
            @Override public void stopTask(String taskId) { throw unsupported(); }
            @Override public JsonNode settings() { throw unsupported(); }
        };
    }
}

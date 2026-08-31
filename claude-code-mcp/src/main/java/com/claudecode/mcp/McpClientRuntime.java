package com.claudecode.mcp;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.queue.MessageQueueManager;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Non-owning operations view of an MCP client manager.
 */
public interface McpClientRuntime {

    void setSdkMessageExchange(SdkControlTransport.MessageExchange exchange);

    void deliverSdkMessage(String serverName, JsonNode message);

    void setElicitationHandler(McpClientManager.ElicitationHandler handler);

    void setToolsChangedListener(Consumer<String> listener);

    void setPromptsChangedListener(Consumer<String> listener);

    void setMessageQueue(MessageQueueManager queue);

    void connect(McpServerConfig config);

    void disconnect(String serverId);

    Optional<McpConnectionView> borrowConnection(String serverId);

    McpConnectionView ensureConnected(String serverId);

    Set<String> getKnownServerIds();

    List<JsonNode> listResourcesForServer(String serverId);

    JsonNode readResource(String serverId, String uri);

    JsonNode sendRequestWithRecovery(String serverId, String method, JsonNode params);

    List<McpToolInfo> listTools();

    List<McpToolInfo> listToolsForServer(String serverId);

    /** Returns the already-discovered immutable tool snapshot without issuing tools/list. */
    default List<McpToolInfo> cachedToolsForServer(String serverId) { return List.of(); }

    List<McpPromptInfo> listPromptsForServer(String serverId);

    McpPromptResult getPrompt(String serverId, String promptName, Map<String, String> arguments);

    JsonNode callTool(String serverId, String toolName, JsonNode args);

    JsonNode callTool(String serverId, String toolName, JsonNode args, String toolUseId);

    JsonNode callTool(
            String serverId, String toolName, JsonNode args, String toolUseId,
            AbortController abortController);

    JsonNode callTool(
            String serverId, String toolName, JsonNode args, String toolUseId,
            AbortController abortController, Consumer<JsonNode> progressListener);

    Set<String> getConnectedServerIds();

    Map<String, String> getServerInstructions();

    String connectionSummary();
}

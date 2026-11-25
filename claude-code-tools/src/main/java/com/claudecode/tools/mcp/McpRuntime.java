package com.claudecode.tools.mcp;

import com.claudecode.mcp.McpClientRuntime;
import com.claudecode.mcp.McpConfig;
import com.claudecode.mcp.McpPromptInfo;
import com.claudecode.mcp.McpServerConfig;
import com.claudecode.mcp.McpToolInfo;
import com.claudecode.tools.ToolRegistry;
import com.claudecode.tools.skills.SkillLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Non-owning session view of the configured MCP runtime.
 */
public interface McpRuntime {

    CompletableFuture<Void> whenReady();

    Map<String, String> snapshotServerStatuses();

    List<String> pendingServerNames();

    List<McpToolProvider.ServerStatusSnapshot> snapshotServerDetails();

    List<McpToolProvider.ToolDisplaySnapshot> snapshotToolDisplays();

    List<String> promptCommandNames();

    void refreshToolDisplays(String serverName, List<McpToolInfo> tools);

    void configureMcpSkills(boolean enabled, SkillLoader loader, Path claudeHome);

    void initialize(McpConfig config, Path projectDir, ToolRegistry registry);

    McpClientRuntime clientRuntime();

    void reconnectServer(String serverName);

    void toggleServer(String serverName, boolean enabled);

    /**
     * Connects a configured (possibly default-disabled) MCP server in-process without mutating
     * persisted {@code ~/.claude.json} defaults, so a single session can temporarily enable it.
     */
    default void ensureServerConnected(String serverName) {
        reconnectServer(serverName);
    }

    void clearServerAuth(String serverName);

    McpToolProvider.AuthStart authenticateServer(String serverName);

    void submitServerAuthCallback(String serverName, String callbackUrl);

    void setSdkServers(List<String> requestedNames);

    McpToolProvider.DynamicServerUpdate setDynamicServers(Map<String, McpServerConfig> requested);

    void syncPromptsToRegistry(Consumer<McpPromptInfo> sink);
}

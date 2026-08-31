package com.claudecode.sdk;

import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Java projection of the official Agent SDK {@code Query} contract.
 */
public interface SdkQuery extends Iterator<SDKMessage>, AutoCloseable {
    CompletableFuture<Void> interrupt();
    CompletableFuture<Void> setPermissionMode(PermissionMode mode);
    CompletableFuture<Void> setModel(String model);
    CompletableFuture<Void> setMaxThinkingTokens(Integer maxThinkingTokens);
    CompletableFuture<Void> applyFlagSettings(Settings settings);
    CompletableFuture<SDKControlInitializeResponse> initializationResult();

    default CompletableFuture<List<SlashCommand>> supportedCommands() {
        return initializationResult().thenApply(SDKControlInitializeResponse::commands);
    }

    default CompletableFuture<List<ModelInfo>> supportedModels() {
        return initializationResult().thenApply(SDKControlInitializeResponse::models);
    }

    default CompletableFuture<List<AgentInfo>> supportedAgents() {
        return initializationResult().thenApply(SDKControlInitializeResponse::agents);
    }

    CompletableFuture<List<McpServerStatus>> mcpServerStatus();
    CompletableFuture<SDKControlGetContextUsageResponse> getContextUsage();
    CompletableFuture<SDKControlReloadPluginsResponse> reloadPlugins();

    default CompletableFuture<AccountInfo> accountInfo() {
        return initializationResult().thenApply(SDKControlInitializeResponse::account);
    }

    default CompletableFuture<RewindFilesResult> rewindFiles(String userMessageId) {
        return rewindFiles(userMessageId, null);
    }

    CompletableFuture<RewindFilesResult> rewindFiles(String userMessageId,
                                                      RewindFilesOptions options);
    CompletableFuture<Void> seedReadState(Path path, long mtime);
    CompletableFuture<Void> reconnectMcpServer(String serverName);
    CompletableFuture<Void> toggleMcpServer(String serverName, boolean enabled);
    CompletableFuture<McpSetServersResult> setMcpServers(
        Map<String, ? extends McpServerConfig> servers);
    CompletableFuture<Void> streamInput(Iterable<SDKUserMessage> stream);
    CompletableFuture<Void> stopTask(String taskId);
    @Override void close();
}

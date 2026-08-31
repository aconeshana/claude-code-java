package com.claudecode.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** CLI control capabilities that are not part of the official public Query declaration. */
public interface SdkQueryExtensions {
    default CompletableFuture<Boolean> backgroundTasks() { return backgroundTasks(null); }
    CompletableFuture<Boolean> backgroundTasks(String toolUseId);
    CompletableFuture<JsonNode> getSettings();
    CompletableFuture<Boolean> cancelAsyncMessage(String messageUuid);
    CompletableFuture<String> generateSessionTitle(String description, boolean persist);
    CompletableFuture<JsonNode> askSideQuestion(String question);
    CompletableFuture<JsonNode> mcpAuthenticate(String serverName);
    CompletableFuture<JsonNode> mcpClearAuth(String serverName);
    CompletableFuture<JsonNode> mcpSubmitOAuthCallbackUrl(String serverName, String callbackUrl);
    CompletableFuture<JsonNode> reloadSkills();
    CompletableFuture<JsonNode> readFile(Path path, Long maxBytes, String encoding);
    CompletableFuture<Void> updateEnvironmentVariables(Map<String, String> variables);

    /** @deprecated Use {@link #mcpAuthenticate(String)}. */
    @Deprecated
    default CompletableFuture<JsonNode> authenticateMcp(String serverName) {
        return mcpAuthenticate(serverName);
    }

    /** @deprecated Use {@link #mcpClearAuth(String)}. */
    @Deprecated
    default CompletableFuture<JsonNode> clearMcpAuth(String serverName) {
        return mcpClearAuth(serverName);
    }

    /** @deprecated Use {@link #mcpSubmitOAuthCallbackUrl(String, String)}. */
    @Deprecated
    default CompletableFuture<JsonNode> submitMcpOAuthCallback(String serverName,
                                                               String callbackUrl) {
        return mcpSubmitOAuthCallbackUrl(serverName, callbackUrl);
    }
}

package com.claudecode.mcp.oauth;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Root record persisted by {@link SecureStorage} implementations.
 */
public record SecureStorageData(Map<String, McpOAuthEntry> mcpOAuth,
                                Map<String, McpOAuthClientConfigEntry> mcpOAuthClientConfig,
                                Map<String, Map<String, String>> pluginSecrets,
                                Map<String, Object> extras) {

    public SecureStorageData(
        Map<String, McpOAuthEntry> mcpOAuth,
        Map<String, McpOAuthClientConfigEntry> mcpOAuthClientConfig,
        Map<String, Map<String, String>> pluginSecrets,
        Map<String, Object> extras
    ) {
        this.mcpOAuth = mcpOAuth == null ? new LinkedHashMap<>() : new LinkedHashMap<>(mcpOAuth);
        this.mcpOAuthClientConfig = mcpOAuthClientConfig == null
            ? new LinkedHashMap<>() : new LinkedHashMap<>(mcpOAuthClientConfig);
        this.pluginSecrets = copyPluginSecrets(pluginSecrets);
        this.extras = extras == null ? new LinkedHashMap<>() : new LinkedHashMap<>(extras);
    }

    /** Compatibility constructor for existing OAuth callers. */
    public SecureStorageData(Map<String, McpOAuthEntry> mcpOAuth,
                             Map<String, McpOAuthClientConfigEntry> mcpOAuthClientConfig,
                             Map<String, Object> extras) {
        this(mcpOAuth, mcpOAuthClientConfig, null, extras);
    }

    public static SecureStorageData empty() {
        return new SecureStorageData(null, null, null, null);
    }

    private static Map<String, Map<String, String>> copyPluginSecrets(
        Map<String, Map<String, String>> values) {
        if (values == null) return new LinkedHashMap<>();
        Map<String, Map<String, String>> copy = new LinkedHashMap<>();
        values.forEach((pluginId, secrets) -> copy.put(pluginId,
            secrets == null ? new LinkedHashMap<>() : new LinkedHashMap<>(secrets)));
        return copy;
    }


    public record McpOAuthEntry(
        String serverName,
        String serverUrl,
        String clientId,
        String clientSecret,
        String accessToken,
        String refreshToken,
        long expiresAt,
        String tokenEndpoint,
        String scope
    ) {

    }

    /**
     * DCR result kept separately from token entry so it survives a token refresh cycle.
     */
    public record McpOAuthClientConfigEntry(
        String clientId,
        String clientSecret,
        String registrationEndpoint,
        long issuedAt
    ) {

    }
}

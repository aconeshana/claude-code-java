package com.claudecode.mcp;

import java.util.List;
import java.util.Map;

/**
 * Aggregated MCP configuration after merging all levels.
 */
public record McpConfig(
    Map<String, McpServerConfig> servers,
    Map<String, McpServerScope> scopes,
    List<String> warnings,
    List<McpConfigWarning> diagnostics
) {
    public McpConfig {
        if (servers     == null) servers     = Map.of();
        if (scopes      == null) scopes      = Map.of();
        if (warnings    == null) warnings    = List.of();
        if (diagnostics == null) diagnostics = List.of();
    }

    /** 3-arg ctor kept for callers that emit only flat warnings. */
    public McpConfig(Map<String, McpServerConfig> servers, Map<String, McpServerScope> scopes,
                     List<String> warnings) {
        this(servers, scopes, warnings, List.of());
    }

    /** 2-arg ctor kept for callers that don't emit warnings (dialog tests). */
    public McpConfig(Map<String, McpServerConfig> servers, Map<String, McpServerScope> scopes) {
        this(servers, scopes, List.of(), List.of());
    }

    /**
     * Backwards-compatible ctor for callers that don't track scope
     * (test fixtures, headless bridge). All servers get scope
     * {@link McpServerScope#DYNAMIC}.
     */
    public McpConfig(Map<String, McpServerConfig> servers) {
        this(servers, Map.of(), List.of(), List.of());
    }

    /**
     * Returns all enabled server configurations.
     */
    public List<McpServerConfig> enabledServers() {
        return servers.values().stream()
            .filter(s -> !s.disabled())
            .toList();
    }

    /**
     * Returns the scope of a server, or {@link McpServerScope#DYNAMIC} when
     * the server was not loaded from an on-disk config (e.g. registered by
     * an agent at runtime, or the loader didn't track scope for legacy
     * fixtures).
     */
    public McpServerScope scopeOf(String name) {
        McpServerScope s = scopes.get(name);
        return s != null ? s : McpServerScope.DYNAMIC;
    }
}

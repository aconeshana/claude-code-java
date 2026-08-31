package com.claudecode.sdk;

import java.util.Map;
import java.util.Objects;

/** Streamable HTTP MCP server configuration. */
public record McpHttpServerConfig(String url, Map<String, String> headers)
        implements McpServerConfig {
    public McpHttpServerConfig {
        Objects.requireNonNull(url, "url");
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}

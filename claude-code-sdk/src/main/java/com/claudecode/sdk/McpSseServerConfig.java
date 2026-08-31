package com.claudecode.sdk;

import java.util.Map;
import java.util.Objects;

/** SSE MCP server configuration. */
public record McpSseServerConfig(String url, Map<String, String> headers)
        implements McpServerConfig {
    public McpSseServerConfig {
        Objects.requireNonNull(url, "url");
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}

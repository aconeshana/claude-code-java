package com.claudecode.mcp;

import org.apache.commons.lang3.StringUtils;
import java.util.List;
import java.util.Map;

/**
 * Configuration for a single MCP server.
 */
public record McpServerConfig(
    String name,
    String command,
    List<String> args,
    Map<String, String> env,
    boolean disabled,
    String transportType,
    String url,
    Map<String, String> headers
) {
    public McpServerConfig {
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("Server name must not be blank");
        }
        if (args == null) args = List.of();
        if (env == null) env = Map.of();
        if (StringUtils.isBlank(transportType)) transportType = "stdio";
        if (headers == null) headers = Map.of();
        // url is allowed to be null (stdio/sdk); required for sse/http — enforced at connect-time
    }

    /**
     * Convenience constructor for stdio-only call sites that don't set
     * url/headers. Equivalent to passing {@code null} for url and an
     * empty map for headers.
     */
    public McpServerConfig(
        String name,
        String command,
        List<String> args,
        Map<String, String> env,
        boolean disabled,
        String transportType
    ) {
        this(name, command, args, env, disabled, transportType, null, null);
    }
}

package com.claudecode.sdk;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Process-backed stdio MCP server configuration. */
public record McpStdioServerConfig(String command, List<String> args, Map<String, String> env)
        implements McpServerConfig {
    public McpStdioServerConfig {
        Objects.requireNonNull(command, "command");
        args = args == null ? List.of() : List.copyOf(args);
        env = env == null ? Map.of() : Map.copyOf(env);
    }
}

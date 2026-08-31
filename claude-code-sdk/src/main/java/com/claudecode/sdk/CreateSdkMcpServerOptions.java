package com.claudecode.sdk;

import java.util.List;

/**
 * SDK-hosted MCP server construction options.
create-server option shape.</li></ul>
 */
public record CreateSdkMcpServerOptions(
    String name,
    String version,
    String instructions,
    List<SdkMcpToolDefinition> tools,
    boolean alwaysLoad
) {
    public CreateSdkMcpServerOptions {
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}

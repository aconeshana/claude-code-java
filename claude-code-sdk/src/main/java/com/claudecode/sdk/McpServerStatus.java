package com.claudecode.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;

/** Current status and advertised tools for an MCP server. */
public record McpServerStatus(String name, String status, ServerInfo serverInfo, String error,
                              JsonNode config, String scope, List<ToolInfo> tools) {
    public McpServerStatus {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(status, "status");
        config = config == null ? null : config.deepCopy();
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    public record ServerInfo(String name, String version) {}
    public record ToolInfo(String name, String description, ToolAnnotations annotations) {}
    public record ToolAnnotations(Boolean readOnly, Boolean destructive, Boolean openWorld) {}

    @Override public JsonNode config() { return config == null ? null : config.deepCopy(); }
}

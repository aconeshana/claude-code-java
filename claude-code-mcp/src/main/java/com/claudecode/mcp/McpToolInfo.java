package com.claudecode.mcp;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Describes a tool discovered from an MCP server.
 */
public record McpToolInfo(
    String serverId,
    String name,
    String description,
    JsonNode inputSchema,
    JsonNode annotations,
    JsonNode meta
) {
    public McpToolInfo {
        if (annotations == null || !annotations.isObject()) {
            annotations = JsonUtils.getMapper().createObjectNode();
        }
        if (meta == null || !meta.isObject()) {
            meta = JsonUtils.getMapper().createObjectNode();
        }
    }

    /** Backwards-compatible constructor retaining the pre-meta discovery shape. */
    public McpToolInfo(String serverId, String name, String description,
                       JsonNode inputSchema, JsonNode annotations) {
        this(serverId, name, description, inputSchema, annotations, null);
    }

    /** Backwards-compatible constructor for tools that do not declare annotations. */
    public McpToolInfo(String serverId, String name, String description, JsonNode inputSchema) {
        this(serverId, name, description, inputSchema, null, null);
    }
}

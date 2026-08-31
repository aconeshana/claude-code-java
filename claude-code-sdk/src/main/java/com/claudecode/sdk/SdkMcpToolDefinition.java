package com.claudecode.sdk;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringUtils;

import java.util.Objects;

/**
 * SDK MCP tool schema, metadata, and in-process handler.
{@code SdkMcpToolDefinition}.</li></ul>
 */
public record SdkMcpToolDefinition(
    String name,
    String description,
    JsonNode inputSchema,
    SdkMcpToolHandler handler,
    JsonNode annotations,
    JsonNode meta
) {
    public SdkMcpToolDefinition {
        if (StringUtils.isBlank(name)) throw new IllegalArgumentException("Tool name is required");
        description = Objects.toString(description, "");
        inputSchema = inputSchema == null
            ? JsonUtils.getMapper().createObjectNode().put("type", "object") : inputSchema.deepCopy();
        Objects.requireNonNull(handler, "handler");
        annotations = annotations == null ? null : annotations.deepCopy();
        meta = meta == null ? null : meta.deepCopy();
    }

    ObjectNode listing(boolean serverAlwaysLoad) {
        ObjectNode node = JsonUtils.getMapper().createObjectNode();
        node.put("name", name);
        node.put("description", description);
        node.set("inputSchema", inputSchema);
        if (annotations != null) node.set("annotations", annotations);
        ObjectNode combined = JsonUtils.getMapper().createObjectNode();
        if (serverAlwaysLoad) combined.put("anthropic/alwaysLoad", true);
        if (meta != null && meta.isObject()) combined.setAll((ObjectNode) meta);
        if (!combined.isEmpty()) node.set("_meta", combined);
        return node;
    }
}

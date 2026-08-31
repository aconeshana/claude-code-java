package com.claudecode.sdk;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;

/**
 * MCP-compatible content, error, and structured-result projection.
SDK MCP call result.</li></ul>
 */
public record SdkMcpToolResult(ArrayNode content, boolean isError, JsonNode structuredContent) {
    public SdkMcpToolResult {
        content = content == null ? JsonUtils.getMapper().createArrayNode() : content.deepCopy();
    }

    public static SdkMcpToolResult text(String text) {
        ArrayNode content = JsonUtils.getMapper().createArrayNode();
        content.addObject().put("type", "text").put("text", Objects.toString(text, ""));
        return new SdkMcpToolResult(content, false, null);
    }

    public static SdkMcpToolResult error(String text) {
        ArrayNode content = JsonUtils.getMapper().createArrayNode();
        content.addObject().put("type", "text").put("text", Objects.toString(text, ""));
        return new SdkMcpToolResult(content, true, null);
    }

    ObjectNode toJson() {
        ObjectNode node = JsonUtils.getMapper().createObjectNode();
        node.set("content", content);
        node.put("isError", isError);
        if (structuredContent != null) node.set("structuredContent", structuredContent);
        return node;
    }
}

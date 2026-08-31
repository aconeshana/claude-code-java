package com.claudecode.sdk;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Structured user input envelope for streaming queries.
{@code SDKUserMessage}.</li></ul>
 */
public record SDKUserMessage(JsonNode content, String uuid, Instant timestamp) {
    public SDKUserMessage {
        content = content == null ? JsonUtils.getMapper().getNodeFactory().textNode("") : content.deepCopy();
        uuid = uuid == null ? UUID.randomUUID().toString() : uuid;
    }

    ObjectNode toJson() {
        ObjectNode root = JsonUtils.getMapper().createObjectNode();
        root.put("type", "user");
        root.put("session_id", "");
        ObjectNode message = root.putObject("message");
        message.put("role", "user");
        message.set("content", content);
        root.putNull("parent_tool_use_id");
        root.put("uuid", uuid);
        if (timestamp != null) root.put("timestamp", timestamp.toString());
        return root;
    }
}

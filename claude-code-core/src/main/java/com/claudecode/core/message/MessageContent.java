package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.claudecode.core.serialization.JsonUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Content of a user message — either a simple string or a list of content blocks.
 */
public record MessageContent(
    @JsonProperty("text") String text,
    @JsonProperty("blocks") List<ContentBlock> blocks
) {

    /**
     * Polymorphic factory — chosen by Jackson via {@code @JsonCreator(mode = DELEGATING)}.
     */
    @JsonCreator
    public static MessageContent fromJson(JsonNode node) {
        if (node == null || node.isNull()) return new MessageContent(null, null);

// Java-native shape wins if either declared field is present — this preserves round-trip.
        if (node.has("text") || node.has("blocks")) {
            String text = node.hasNonNull("text") ? node.get("text").asText() : null;
            List<ContentBlock> blocks = null;
            if (node.hasNonNull("blocks")) {
                blocks = parseBlocks(node.get("blocks"));
            }
            return new MessageContent(text, blocks);
        }


        if (node.has("content")) {
            JsonNode content = node.get("content");
            if (content.isTextual()) {
                return new MessageContent(content.asText(), null);
            }
            if (content.isArray()) {
                return new MessageContent(null, parseBlocks(content));
            }
        }
        // Unknown shape — fall through with nulls so downstream isn't handed garbage.
        return new MessageContent(null, null);
    }

    private static List<ContentBlock> parseBlocks(JsonNode arrayNode) {
        if (arrayNode == null || !arrayNode.isArray() || arrayNode.isEmpty()) {
            return null;
        }
        List<ContentBlock> out = new ArrayList<>(arrayNode.size());
        for (JsonNode block : arrayNode) {
            try {
                out.add(JsonUtils.getMapper().treeToValue(block, ContentBlock.class));
            } catch (Exception _) {

                // rather than dropping every block; the sibling text/tool_use
                // blocks in the same message must still reach the UI.
            }
        }
        return out.isEmpty() ? null : List.copyOf(out);
    }

    /**
     * Creates a text-only message content.
     */
    public static MessageContent ofText(String text) {
        return new MessageContent(text, null);
    }

    /**
     * Creates a block-based message content.
     */
    public static MessageContent ofBlocks(List<ContentBlock> blocks) {
        return new MessageContent(null, blocks);
    }

    /**
     * Creates a tool result message content.
     */
    public static MessageContent ofToolResult(String toolUseId, List<ContentBlock> content, boolean isError) {
        ToolResultBlock resultBlock = new ToolResultBlock(toolUseId, content, isError);
        return new MessageContent(null, List.of(resultBlock));
    }

    /**
     * Returns true if this content is text-only.
     */
    @JsonIgnore
    public boolean isText() {
        return text != null;
    }
}

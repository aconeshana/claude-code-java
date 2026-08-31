package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.claudecode.core.serialization.JsonUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * A tool result content block — the result of executing a tool.
 */
public record ToolResultBlock(
    @JsonProperty("tool_use_id") String toolUseId,
    @JsonProperty("content") List<ContentBlock> content,
    @JsonIgnore boolean isError,
    @JsonIgnore boolean includeIsErrorField,
    /** Preserve Anthropic's array form even when every inner block is text. */
    @JsonIgnore boolean preserveContentBlocks
) implements ContentBlock {

    /** Generic successful results omit {@code is_error}; errors include it. */
    public ToolResultBlock(String toolUseId, List<ContentBlock> content, boolean isError) {
        this(toolUseId, content, isError, isError, false);
    }

    /** Backward-compatible constructor retaining explicit is_error presence. */
    public ToolResultBlock(String toolUseId, List<ContentBlock> content,
                           boolean isError, boolean includeIsErrorField) {
        this(toolUseId, content, isError, includeIsErrorField, false);
    }

    /** Constructor for tool implementations whose wire contract requires block arrays. */
    public ToolResultBlock(String toolUseId, List<ContentBlock> content,
                           boolean isError, boolean includeIsErrorField,
                           boolean preserveContentBlocks) {
        this.toolUseId = toolUseId;
        this.content = content;
        this.isError = isError;
        this.includeIsErrorField = includeIsErrorField;
        this.preserveContentBlocks = preserveContentBlocks;
    }

    @JsonCreator
    public static ToolResultBlock fromJson(
            @JsonProperty("tool_use_id") String toolUseId,
            @JsonProperty("content") JsonNode contentNode,
            @JsonProperty("is_error") Boolean isError) {
        List<ContentBlock> blocks = parseContent(contentNode);
        return new ToolResultBlock(toolUseId, blocks,
            Boolean.TRUE.equals(isError), isError != null, false);
    }

    /**
     * JSONL representation preserves the field's presence, not merely its boolean value.
     */
    @JsonProperty("is_error")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Boolean serializedIsError() {
        return includeIsErrorField ? isError : null;
    }

    private static List<ContentBlock> parseContent(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isTextual()) {
            return List.of(new TextBlock(node.asText()));
        }
        if (!node.isArray()) return null;
        if (node.isEmpty()) return List.of();
        List<ContentBlock> out = new ArrayList<>(node.size());
        for (JsonNode block : node) {
            try {
                out.add(JsonUtils.getMapper().treeToValue(block, ContentBlock.class));
            } catch (Exception _) {
                // Unknown block subtype — drop it so a mixed-shape tool_result
                // still yields something renderable rather than an all-or-nothing failure.
            }
        }
        return List.copyOf(out);
    }
}

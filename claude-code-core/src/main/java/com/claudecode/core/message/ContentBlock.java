package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Sealed interface for content blocks in messages.
 * Corresponds to Anthropic API's ContentBlock types.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = TextBlock.class, name = "text"),
    @JsonSubTypes.Type(value = ToolUseBlock.class, name = "tool_use"),
    @JsonSubTypes.Type(value = ToolResultBlock.class, name = "tool_result"),
    @JsonSubTypes.Type(value = ThinkingBlock.class, name = "thinking"),
    @JsonSubTypes.Type(value = RedactedThinkingBlock.class, name = "redacted_thinking"),
    @JsonSubTypes.Type(value = ImageBlock.class, name = "image"),
    @JsonSubTypes.Type(value = ServerToolUseBlock.class, name = "server_tool_use"),
    @JsonSubTypes.Type(value = ServerToolResultBlock.class, name = "server_tool_result"),
    @JsonSubTypes.Type(value = WebSearchToolResultBlock.class, name = "web_search_tool_result"),
    @JsonSubTypes.Type(value = ToolReferenceBlock.class, name = "tool_reference"),
    @JsonSubTypes.Type(value = DocumentBlock.class, name = "document")
})
public sealed interface ContentBlock permits
    TextBlock, ToolUseBlock, ToolResultBlock, ThinkingBlock, RedactedThinkingBlock, ImageBlock,
    ServerToolUseBlock, ServerToolResultBlock, WebSearchToolResultBlock, ToolReferenceBlock, DocumentBlock {
}

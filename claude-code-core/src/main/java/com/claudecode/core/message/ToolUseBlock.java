package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A tool use content block — represents the model requesting a tool call.
 */
public record ToolUseBlock(
    @JsonProperty("id") String id,
    @JsonProperty("name") String name,
    @JsonProperty("input") JsonNode input,
    /** Optional tool-search attribution retained when replaying beta sessions. */
    @JsonProperty("caller") @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode caller
) implements ContentBlock {

    /** Backward-compatible constructor for ordinary tool_use blocks. */
    public ToolUseBlock(String id, String name, JsonNode input) {
        this(id, name, input, null);
    }

    @JsonCreator
    public ToolUseBlock {
    }
}

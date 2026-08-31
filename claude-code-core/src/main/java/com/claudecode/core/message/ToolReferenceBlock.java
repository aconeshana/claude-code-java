package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A tool_reference content block — appears inside a {@code tool_result}'s content array to tell the
 * API "expand this deferred tool's schema so the model can see it".
 */
public record ToolReferenceBlock(
    @JsonProperty("tool_name") String toolName
) implements ContentBlock {

    @JsonCreator
    public ToolReferenceBlock {
    }
}

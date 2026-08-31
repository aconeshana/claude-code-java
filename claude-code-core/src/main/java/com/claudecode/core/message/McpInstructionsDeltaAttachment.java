package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Delta of MCP servers that have declared {@code instructions} since the last turn.
 */
public record McpInstructionsDeltaAttachment(
    @JsonProperty("addedNames") List<String> addedNames,
    @JsonProperty("addedBlocks") List<String> addedBlocks,
    @JsonProperty("removedNames") List<String> removedNames
) implements AttachmentPayload {

    @JsonCreator
    public McpInstructionsDeltaAttachment {
    }
}

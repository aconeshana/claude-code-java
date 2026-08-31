package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * An MCP resource pulled from an {@code @server:uri} mention in the user's input.
 */
public record McpResourceAttachment(
    @JsonProperty("server") String server,
    @JsonProperty("uri") String uri,
    @JsonProperty("name") String name,
    @JsonProperty("description") String description,
    @JsonProperty("content") String content
) implements AttachmentPayload {

    @JsonCreator
    public McpResourceAttachment {
    }
}

package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A document content block (e.g.
 */
public record DocumentBlock(
    @JsonProperty("source") JsonNode source
) implements ContentBlock {

    @JsonCreator
    public DocumentBlock {
    }
}

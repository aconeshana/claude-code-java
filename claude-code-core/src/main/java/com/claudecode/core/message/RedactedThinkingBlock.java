package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** API-supplied encrypted thinking block that must be replayed byte-for-byte. */
public record RedactedThinkingBlock(
    @JsonProperty("data") String data
) implements ContentBlock {
    @JsonCreator
    public RedactedThinkingBlock {
    }
}

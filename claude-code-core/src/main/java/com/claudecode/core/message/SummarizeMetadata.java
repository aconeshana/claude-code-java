package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** UI metadata attached to a message-selector partial compact summary. */
public record SummarizeMetadata(
    @JsonProperty("messagesSummarized") int messagesSummarized,
    @JsonProperty("userContext") @JsonInclude(JsonInclude.Include.NON_NULL) String userContext,
    @JsonProperty("direction") @JsonInclude(JsonInclude.Include.NON_NULL) String direction
) {}

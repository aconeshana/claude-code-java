package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Structured detail attached to an assistant message whose {@code stop_reason} is {@code
 * "refusal"}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StopDetails(
    @JsonProperty("category") String category,
    @JsonProperty("explanation") String explanation
) {

    @JsonCreator
    public StopDetails {
    }
}

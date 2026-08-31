package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The uuid range of the conversation segment preserved (kept intact) across a partial compact.
 */
public record PreservedSegment(
    @JsonProperty("headUuid") String headUuid,
    @JsonProperty("anchorUuid") String anchorUuid,
    @JsonProperty("tailUuid") String tailUuid
) {
}

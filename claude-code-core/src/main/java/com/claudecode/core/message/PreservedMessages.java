package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * UUID inventory for messages kept verbatim across compaction.
 */
public record PreservedMessages(
    @JsonProperty("anchorUuid") String anchorUuid,
    @JsonProperty("uuids") List<String> uuids,
    @JsonProperty("allUuids") List<String> allUuids
) {
    public PreservedMessages {
        uuids = uuids == null ? null : List.copyOf(uuids);
        allUuids = allUuids == null ? null : List.copyOf(allUuids);
    }
}

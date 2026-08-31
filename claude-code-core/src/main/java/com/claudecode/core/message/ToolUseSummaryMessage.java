package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * A Haiku-generated one-line summary of a just-completed tool batch.
 */
public record ToolUseSummaryMessage(
    @JsonProperty("uuid") String uuid,
    @JsonProperty("summary") String summary,
    @JsonProperty("precedingToolUseIds") List<String> precedingToolUseIds,
    @JsonProperty("parentUuid") String parentUuidValue,
    @JsonProperty("timestamp") Instant timestampValue
) implements Message {

    @JsonCreator
    public ToolUseSummaryMessage {
    }

    public ToolUseSummaryMessage(String uuid, String summary, List<String> precedingToolUseIds) {
        this(uuid, summary, precedingToolUseIds, null, Instant.now());
    }

    @Override
    public String type() {
        return "tool_use_summary";
    }

    @Override
    public Optional<String> parentUuid() {
        return Optional.ofNullable(parentUuidValue);
    }

    @Override
    public Optional<Instant> timestamp() {
        return Optional.ofNullable(timestampValue);
    }
}

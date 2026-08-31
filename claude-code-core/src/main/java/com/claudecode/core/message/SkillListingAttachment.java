package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Listing of available skills for the Skill tool, de-duplicated across turns.
 */
public record SkillListingAttachment(
    @JsonProperty("content") String content,
    @JsonProperty("skillCount") int skillCount,
    @JsonProperty("isInitial") boolean isInitial,
    @JsonProperty("names") List<String> names
) implements AttachmentPayload {

    @JsonCreator
    public SkillListingAttachment {
        names = names == null ? List.of() : List.copyOf(names);
    }

/**
     * Backward-compatible constructor for pre.
     */
    public SkillListingAttachment(String content, int skillCount, boolean isInitial) {
        this(content, skillCount, isInitial, List.of());
    }
}

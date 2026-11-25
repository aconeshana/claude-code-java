package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Listing of available skills for the Skill tool, de-duplicated and diffed across turns
 * (both newly enabled skills and newly disabled skills).
 */
public record SkillListingAttachment(
    @JsonProperty("content") String content,
    @JsonProperty("skillCount") int skillCount,
    @JsonProperty("isInitial") boolean isInitial,
    @JsonProperty("names") List<String> names,
    @JsonProperty("removedNames") List<String> removedNames
) implements AttachmentPayload {

    @JsonCreator
    public SkillListingAttachment {
        names = names == null ? List.of() : List.copyOf(names);
        removedNames = removedNames == null ? List.of() : List.copyOf(removedNames);
    }

    public SkillListingAttachment(
            String content, int skillCount, boolean isInitial, List<String> names) {
        this(content, skillCount, isInitial, names, List.of());
    }

    /**
     * Backward-compatible constructor for pre-names payloads.
     */
    public SkillListingAttachment(String content, int skillCount, boolean isInitial) {
        this(content, skillCount, isInitial, List.of(), List.of());
    }
}

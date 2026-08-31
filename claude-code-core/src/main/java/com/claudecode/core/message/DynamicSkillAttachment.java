package com.claudecode.core.message;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Newly-detected dynamic skills.
 */
public record DynamicSkillAttachment(
    @JsonProperty("skillDir") String skillDir,
    @JsonProperty("skillNames") List<String> skillNames,
    @JsonProperty("displayPath") String displayPath
) implements AttachmentPayload {

    @JsonCreator
    public DynamicSkillAttachment {
    }
}

package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Skills invoked earlier in the session, re-attached so the model keeps following their guidelines
 * after compaction.
 */
public record InvokedSkillsAttachment(
    @JsonProperty("skills") List<InvokedSkillsAttachment.InvokedSkillEntry> skills
) implements AttachmentPayload {

    @JsonCreator
    public InvokedSkillsAttachment {
    }

    public record InvokedSkillEntry(
        @JsonProperty("name") String name,
        @JsonProperty("path") String path,
        @JsonProperty("content") String content
    ) {

        @JsonCreator
        public InvokedSkillEntry {
        }
    }
}

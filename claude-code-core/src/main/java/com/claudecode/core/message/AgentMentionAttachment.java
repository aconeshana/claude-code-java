package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * An {@code @agent-...} mention parsed from the user's input.
 */
public record AgentMentionAttachment(
    @JsonProperty("agentType") String agentType
) implements AttachmentPayload {

    @JsonCreator
    public AgentMentionAttachment {
    }
}

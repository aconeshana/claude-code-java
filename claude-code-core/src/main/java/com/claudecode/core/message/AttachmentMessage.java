package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Optional;

/**
 * An attachment message carrying post-compact re-attached state (re-read files, the active plan
 * file, invoked skills, plan-mode reminders, or background agent task status).
 */
public record AttachmentMessage(
    @JsonProperty("uuid") String uuid,
    @JsonProperty("attachment") AttachmentPayload payload,
    @JsonProperty("parentUuid") String parentUuidValue,
    @JsonProperty("timestamp") Instant timestampValue
) implements Message {

    @JsonCreator
    public AttachmentMessage {
    }

    public AttachmentMessage(String uuid, AttachmentPayload payload) {
        this(uuid, payload, null, Instant.now());
    }

    @Override
    public String type() {
        return "attachment";
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

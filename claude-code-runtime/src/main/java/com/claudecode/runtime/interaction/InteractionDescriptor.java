package com.claudecode.runtime.interaction;

import java.time.Instant;
import java.util.Objects;

/** Secret-free lifecycle identity shared across interaction endpoints. */
public record InteractionDescriptor(
        String id,
        String sessionId,
        InteractionKind kind,
        InteractionResponsePolicy responsePolicy,
        InteractionSensitivity sensitivity,
        Instant requestedAt) {

    public InteractionDescriptor {
        Objects.requireNonNull(id, "id");
        sessionId = Objects.requireNonNullElse(sessionId, "");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(responsePolicy, "responsePolicy");
        Objects.requireNonNull(sensitivity, "sensitivity");
        Objects.requireNonNull(requestedAt, "requestedAt");
    }
}

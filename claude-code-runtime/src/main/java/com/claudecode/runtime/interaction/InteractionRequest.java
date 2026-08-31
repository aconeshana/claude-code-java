package com.claudecode.runtime.interaction;

import java.util.Objects;

/** Typed request delivered to one feature presenter. */
public record InteractionRequest<Q, R>(
        InteractionDescriptor descriptor,
        InteractionFeature<Q, R> feature,
        Q payload) {

    public InteractionRequest {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(feature, "feature");
        Objects.requireNonNull(payload, "payload");
    }

    @Override public String toString() {
        return "InteractionRequest[id=" + descriptor.id()
            + ", kind=" + descriptor.kind() + "]";
    }
}

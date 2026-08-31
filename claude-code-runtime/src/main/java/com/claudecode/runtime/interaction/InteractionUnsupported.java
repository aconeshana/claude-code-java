package com.claudecode.runtime.interaction;

import java.util.Objects;

/** Secret-free notification that an applicable endpoint lacks a feature. */
public record InteractionUnsupported(
        InteractionDescriptor descriptor,
        InteractionEndpoint endpoint,
        String action) {

    public InteractionUnsupported {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(action, "action");
    }
}

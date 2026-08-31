package com.claudecode.runtime.interaction;

import java.util.Objects;

/** Winning response delivered only to endpoints allowed by the feature policy. */
public record InteractionResolution<R>(
        InteractionDescriptor descriptor,
        R result,
        InteractionEndpoint origin) {

    public InteractionResolution {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(origin, "origin");
    }
}

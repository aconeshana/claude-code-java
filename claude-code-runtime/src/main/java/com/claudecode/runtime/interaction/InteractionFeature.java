package com.claudecode.runtime.interaction;

import java.util.Objects;
import java.util.function.Supplier;

/** Strongly typed feature definition plus host cancellation fallbacks. */
public record InteractionFeature<Q, R>(
        InteractionKind kind,
        InteractionResponsePolicy responsePolicy,
        InteractionSensitivity sensitivity,
        Class<Q> requestType,
        Class<R> resultType,
        Supplier<R> cancelledResult,
        Supplier<R> unavailableResult) {

    public InteractionFeature {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(responsePolicy, "responsePolicy");
        Objects.requireNonNull(sensitivity, "sensitivity");
        Objects.requireNonNull(requestType, "requestType");
        Objects.requireNonNull(resultType, "resultType");
        Objects.requireNonNull(cancelledResult, "cancelledResult");
        Objects.requireNonNull(unavailableResult, "unavailableResult");
    }
}

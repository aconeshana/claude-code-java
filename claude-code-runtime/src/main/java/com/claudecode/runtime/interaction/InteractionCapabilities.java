package com.claudecode.runtime.interaction;

import java.util.Map;

/** Exhaustive product capability declaration for local and remote endpoints. */
public final class InteractionCapabilities {
    private static final Map<InteractionKind, Map<InteractionEndpoint, InteractionSupport>> MATRIX =
        Map.of(
            InteractionKind.PERMISSION, Map.of(
                InteractionEndpoint.LOCAL, InteractionSupport.SUPPORTED,
                InteractionEndpoint.REMOTE, InteractionSupport.SUPPORTED),
            InteractionKind.USER_QUESTION, Map.of(
                InteractionEndpoint.LOCAL, InteractionSupport.SUPPORTED,
                InteractionEndpoint.REMOTE, InteractionSupport.SUPPORTED),
            InteractionKind.SUDO_PASSWORD, Map.of(
                InteractionEndpoint.LOCAL, InteractionSupport.SUPPORTED,
                InteractionEndpoint.REMOTE, InteractionSupport.UNIMPLEMENTED));

    private InteractionCapabilities() {}

    public static InteractionSupport support(
            InteractionKind kind, InteractionEndpoint endpoint) {
        Map<InteractionEndpoint, InteractionSupport> endpoints = MATRIX.get(kind);
        return endpoints == null ? null : endpoints.get(endpoint);
    }
}

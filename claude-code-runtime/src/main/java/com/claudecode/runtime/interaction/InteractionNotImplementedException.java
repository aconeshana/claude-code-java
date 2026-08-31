package com.claudecode.runtime.interaction;

import java.util.Objects;

/** Expected endpoint capability gap; caught at the coordinator boundary. */
public final class InteractionNotImplementedException extends RuntimeException {
    private final InteractionDescriptor descriptor;
    private final InteractionEndpoint endpoint;
    private final String action;

    public InteractionNotImplementedException(
            InteractionDescriptor descriptor,
            InteractionEndpoint endpoint,
            String action) {
        super("interaction " + descriptor.kind() + " is not implemented by " + endpoint);
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.action = Objects.requireNonNull(action, "action");
    }

    public InteractionDescriptor descriptor() { return descriptor; }
    public InteractionEndpoint endpoint() { return endpoint; }
    public String action() { return action; }
}

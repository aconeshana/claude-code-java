package com.claudecode.services.hooks;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;

/**
 * SDK-provided hook callback executed over the stdio control channel.
 */
public record CallbackHook(
    String callbackId,
    Callback callback,
    Optional<Integer> timeoutSeconds
) implements HookCommand {
    @FunctionalInterface
    public interface Callback {
        JsonNode invoke(HookInput input, String toolUseId);
    }
    @Override public Optional<String> ifCondition() { return Optional.empty(); }
    @Override public Optional<String> statusMessage() { return Optional.empty(); }
    @Override public boolean once() { return false; }
}

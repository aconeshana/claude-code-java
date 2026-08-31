package com.claudecode.runtime.session;

/**
 * Clears service-owned, conversation-scoped caches during {@code /clear}.
 */
@FunctionalInterface
public interface ConversationResetPort {

    void reset();

    static ConversationResetPort noop() {
        return () -> {};
    }
}

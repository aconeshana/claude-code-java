package com.claudecode.gateway;

/**
 * Consumer-owned boundary for the session row menu's mutating actions:
 * rename, fork, archive, and permanently delete. The CLI composition root
 * implements this port against {@code SessionOperationsService}, the same
 * instance backing the Agent SDK's local session facade.
 *
 * <p>Archive and delete diverge from the upstream deepseek-harness row menu:
 * archive is a one-way hide (no "unarchive" UI in this pass) and delete has
 * no upstream counterpart at all — it reuses the Java {@code /resume}
 * picker's permanent-deletion capability.
 */
public interface GatewaySessionActionsPort {

    /** The forked child's id, answered to the caller as {@code session_id}. */
    record ForkResult(String sessionId) {}

    /** Renames a session in place; an unchanged title is a legal no-op. */
    default void rename(String sessionId, String title) {
        throw new UnsupportedOperationException("session rename is not configured");
    }

    /** Forks a session at its last completed turn into a new child session. */
    default ForkResult fork(String sessionId, String title) {
        throw new UnsupportedOperationException("session fork is not configured");
    }

    /** Hides a session from the default catalog listing without touching its transcript. */
    default void archive(String sessionId) {
        throw new UnsupportedOperationException("session archive is not configured");
    }

    /** Permanently deletes a session's transcript and sidecar directory; false when absent. */
    default boolean delete(String sessionId) {
        throw new UnsupportedOperationException("session delete is not configured");
    }
}

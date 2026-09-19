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
 *
 * <p><b>Failure vocabulary</b>: an id no session answers to is
 * {@link UnknownSessionException} (404); anything else escaping an
 * implementation describes a session that exists and an operation that failed
 * on it (500). The handler cannot infer the difference from exception types —
 * a disk-full transcript append arrives as an unchecked I/O wrapper, which is
 * a {@code RuntimeException} just like a lookup miss — so implementations must
 * signal it explicitly.
 */
public interface GatewaySessionActionsPort {

    /** The forked child's id, answered to the caller as {@code session_id}. */
    record ForkResult(String sessionId) {}

    /** No session matches the id; the handler answers 404 rather than 500. */
    final class UnknownSessionException extends RuntimeException {
        public UnknownSessionException(String message) {
            super(message);
        }
    }

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

    /**
     * Permanently deletes a session's transcript and sidecar directory;
     * {@code false} when no session matches the id. Deleting an
     * already-deleted session is idempotent, not an error: two clients on the
     * same catalog listing race, and the loser got the outcome it asked for.
     */
    default boolean delete(String sessionId) {
        throw new UnsupportedOperationException("session delete is not configured");
    }
}

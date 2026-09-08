package com.claudecode.gateway;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-session in-flight turn guard, shared by every protocol face.
 *
 * <p>One turn at a time per session: a second submit against the same session
 * while its turn is running returns the protocol's busy error, while turns of
 * other sessions proceed concurrently. The acquire flag is removed on release
 * so an idle session never holds a stale entry.
 */
final class InFlightGuard {

    private final ConcurrentHashMap<String, AtomicBoolean> inFlight = new ConcurrentHashMap<>();

    /** Reserves the session's turn slot; false when a turn is already running. */
    boolean tryAcquire(String sessionId) {
        AtomicBoolean created = new AtomicBoolean(false);
        AtomicBoolean slot = inFlight.computeIfAbsent(sessionId, _ -> {
            created.set(true);
            return new AtomicBoolean(true);
        });
        return created.get() || slot.compareAndSet(false, true);
    }

    /** Releases the session's turn slot; safe to call more than once. */
    void release(String sessionId) {
        AtomicBoolean slot = inFlight.get(sessionId);
        if (slot != null) {
            slot.set(false);
            inFlight.remove(sessionId, slot);
        }
    }
}

package com.claudecode.gateway;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-session in-flight turn guard, shared by every protocol face.
 *
 * <p>One turn at a time per session: a second submit against the same session
 * while its turn is running returns the protocol's busy error, while turns of
 * other sessions proceed concurrently. Supports event-driven {@link #awaitAndAcquire}
 * for server-side steer (cancel + wait for release + acquire) without blind sleeps.
 */
final class InFlightGuard {

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition released = lock.newCondition();
    private final Set<String> inFlight = new HashSet<>();

    /** Reserves the session's turn slot; false when a turn is already running. */
    boolean tryAcquire(String sessionId) {
        lock.lock();
        try {
            return inFlight.add(sessionId);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Waits up to {@code timeout} for any in-flight turn on {@code sessionId} to
     * release, then atomically acquires the slot.
     */
    boolean awaitAndAcquire(String sessionId, Duration timeout) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + Math.max(0L, timeout.toNanos());
        lock.lock();
        try {
            while (!inFlight.add(sessionId)) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0L) return false;
                released.awaitNanos(remaining);
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** True when a turn is currently running for {@code sessionId}. */
    boolean isInFlight(String sessionId) {
        if (sessionId == null) return false;
        lock.lock();
        try {
            return inFlight.contains(sessionId);
        } finally {
            lock.unlock();
        }
    }

    /** Releases the session's turn slot; safe to call more than once. */
    void release(String sessionId) {
        lock.lock();
        try {
            if (inFlight.remove(sessionId)) {
                released.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }
}

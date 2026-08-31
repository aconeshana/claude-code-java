package com.claudecode.core.engine;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe abort signal controller.
 */
public class AbortController {

    /**
     * The abort reason a user who chose to rewrite a refused prompt is given.
     */
    public static final String REFUSAL_FALLBACK_EDIT = "refusal-fallback-edit";

    private static final State ACTIVE = new State(false, null);

    /**
     * Publish the aborted flag and its reason as one state transition. Keeping
     * them in separate atomics allowed the query loop to observe
     * {@code aborted=true} before the reason became visible and persist a
     * human interruption during a soft process-shutdown interrupt.
     */
    private final AtomicReference<State> state = new AtomicReference<>(ACTIVE);
    private final CopyOnWriteArrayList<AbortRegistration> callbacks = new CopyOnWriteArrayList<>();

    /**
     * Returns {@code true} if {@link #abort} has been called.
     */
    public boolean isAborted() {
        return state.get().aborted();
    }

    /**
     * Returns the abort reason, or null if not aborted / no reason given.
     */
    public String getReason() {
        return state.get().reason();
    }

    /**
     * Signals abort with an optional reason string.
     * All registered callbacks are invoked exactly once. Subsequent calls are no-ops.
     */
    public void abort(String abortReason) {
        while (true) {
            State current = state.get();
            if (current.aborted()) return;
            if (!state.compareAndSet(current, new State(true, abortReason))) continue;
            for (AbortRegistration callback : callbacks) {
                try {
                    callback.run();
                } catch (Exception _) {
                    // Abort callbacks must not throw
                }
            }
            callbacks.clear();
            return;
        }
    }

    /** Signals abort without a reason. */
    public void abort() {
        abort(null);
    }

    /**
     * Registers a callback to be invoked when {@link #abort} is called.
     * If already aborted, the callback is invoked immediately.
     */
    public void onAbort(Runnable callback) {
        install(new AbortRegistration(callback));
    }

    /**
     * Registers a removable abort callback. The returned handle is safe to
     * close more than once and prevents completed HTTP calls from accumulating
     * listeners for the rest of a long-running session.
     */
    public AutoCloseable registerOnAbort(Runnable callback) {
        AbortRegistration registration = new AbortRegistration(callback);
        install(registration);
        return registration;
    }

    private void install(AbortRegistration registration) {
        if (state.get().aborted()) {
            registration.run();
            return;
        }
        callbacks.add(registration);
        if (state.get().aborted() && callbacks.remove(registration)) {
            registration.run();
        }
    }

    /**
     * Throws {@link AbortException} if this controller has been aborted.
     */
    public void throwIfAborted() {
        if (state.get().aborted()) {
            throw new AbortException("Operation was aborted");
        }
    }

    /**
     * Resets for reuse on the next query.
     */
    public void reset() {
        state.set(ACTIVE);
        callbacks.clear();
    }

    private record State(boolean aborted, String reason) {}

    private final class AbortRegistration implements Runnable, AutoCloseable {
        private final Runnable callback;
        private final AtomicBoolean active = new AtomicBoolean(true);

        private AbortRegistration(Runnable callback) {
            this.callback = Objects.requireNonNull(callback, "callback");
        }

        @Override
        public void run() {
            if (active.compareAndSet(true, false)) callback.run();
        }

        @Override
        public void close() {
            active.set(false);
            callbacks.remove(this);
        }
    }
}

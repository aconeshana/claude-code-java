package com.claudecode.tools.tasks.teammate;

import java.util.concurrent.Executor;


public final class TeammateContextHolder {

    private static final InheritableThreadLocal<TeammateContext> CURRENT = new InheritableThreadLocal<>();

    private TeammateContextHolder() {}

    /** Sets the active context for the current (and child) thread(s). */
    public static void set(TeammateContext context) {
        CURRENT.set(context);
    }

    /** Returns the active context, or {@code null} if none is set. */
    public static TeammateContext get() {
        return CURRENT.get();
    }

    /** Clears the active context. Call in a {@code finally} block after a teammate run. */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * Runs {@code action} with {@code context} active, restoring whatever context was active before (or
     * clearing it, if none) afterwards — even if {@code action} throws.
     */
    public static void runWithContext(TeammateContext context, Runnable action) {
        TeammateContext prev = get();
        set(context);
        try {
            action.run();
        } finally {
            if (prev != null) {
                set(prev);
            } else {
                clear();
            }
        }
    }

    /**
     * Wraps an {@link Executor} so every task it runs executes with {@code context} active and cleared
     * afterwards.
     */
    public static Executor withContext(TeammateContext context, Executor delegate) {
        return command -> delegate.execute(() -> runWithContext(context, command));
    }
}

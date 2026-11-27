package com.claudecode.core.io;

import java.util.concurrent.Future;

/** Best-effort lifecycle teardown primitives shared across modules. */
public final class IoUtils {

    private IoUtils() {}

    /** Closes {@code closeable} if non-null, ignoring any thrown {@link Exception}. */
    public static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception _) {
            // Best-effort teardown must never mask the primary result or exception.
        }
    }

    /** Cancels {@code future} without interrupting if non-null. */
    public static void cancelQuietly(Future<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }
}

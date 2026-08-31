package com.claudecode.core.error;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.AbortException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;

public final class ErrorUtils {
    private ErrorUtils() {}

    public static boolean isAbort(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof AbortException
                    || current instanceof CancellationException
                    || current instanceof InterruptedException
                    || Strings.CS.equals("AbortError", current.getClass().getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasExactMessage(Throwable error, String message) {
        return error != null && Objects.equals(error.getMessage(), message);
    }

    public static String message(Throwable error) {
        if (error == null) return "null";
        return error.getMessage() != null ? error.getMessage() : error.toString();
    }

    public static String shortStack(Throwable error, int maxFrames) {
        if (error == null) return "null";
        StringBuilder out = new StringBuilder(error.toString());
        StackTraceElement[] frames = error.getStackTrace();
        int limit = Math.max(0, Math.min(maxFrames, frames.length));
        for (int i = 0; i < limit; i++) out.append("\n\tat ").append(frames[i]);
        return out.toString();
    }

    /**
     * Copies an exception's stack and cause structure for logs while replacing
     * every exception message with its class name. Exception messages may
     * contain prompts, tool inputs, provider payloads, or other user data.
     */
    public static Throwable redactedForLogging(Throwable error) {
        if (error == null) return null;
        return redact(error, new IdentityHashMap<>());
    }

    private static Throwable redact(
            Throwable source, Map<Throwable, RedactedThrowable> seen) {
        RedactedThrowable redacted = new RedactedThrowable(source);
        seen.put(source, redacted);

        Throwable cause = source.getCause();
        if (cause != null && cause != source && !seen.containsKey(cause)) {
            redacted.initCause(redact(cause, seen));
        }
        for (Throwable suppressed : source.getSuppressed()) {
            if (suppressed != null && suppressed != source && !seen.containsKey(suppressed)) {
                redacted.addSuppressed(redact(suppressed, seen));
            }
        }
        return redacted;
    }

    private static final class RedactedThrowable extends RuntimeException {
        private RedactedThrowable(Throwable source) {
            super(source.getClass().getName());
            setStackTrace(source.getStackTrace());
        }
    }

    public static boolean isFsInaccessible(Throwable error) {
        return error instanceof NoSuchFileException
            || error instanceof AccessDeniedException
            || error instanceof NotDirectoryException
            || error instanceof FileSystemLoopException
            || error instanceof SecurityException;
    }
}

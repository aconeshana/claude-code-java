package com.claudecode.core.error;

import org.apache.commons.lang3.Strings;
import static org.junit.jupiter.api.Assertions.*;

import com.claudecode.core.engine.AbortException;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class ErrorUtilsTest {
    @Test
    void recognizesAbortThroughWrapperAndFilesystemFamilies() {
        assertTrue(ErrorUtils.isAbort(new CompletionException(new AbortException("stop"))));
        assertTrue(ErrorUtils.isFsInaccessible(new NoSuchFileException("missing")));
        assertTrue(ErrorUtils.isFsInaccessible(new AccessDeniedException("private")));
        assertFalse(ErrorUtils.isFsInaccessible(new IllegalStateException()));
    }

    @Test
    void extractsMessageAndBoundsStackFrames() {
        RuntimeException error = new RuntimeException("boom");
        error.setStackTrace(new StackTraceElement[] {
            new StackTraceElement("A", "one", "A.java", 1),
            new StackTraceElement("B", "two", "B.java", 2)
        });
        assertEquals("boom", ErrorUtils.message(error));
        assertTrue(ErrorUtils.hasExactMessage(error, "boom"));
        String shortStack = ErrorUtils.shortStack(error, 1);
        assertTrue(Strings.CS.contains(shortStack, "A.one"));
        assertFalse(Strings.CS.contains(shortStack, "B.two"));
    }

    @Test
    void redactedLoggingThrowablePreservesStackAndCauseTypesWithoutMessages() {
        IOException cause = new IOException("SECRET_CAUSE");
        RuntimeException error = new RuntimeException("SECRET_FAILURE", cause);
        error.setStackTrace(new StackTraceElement[] {
            new StackTraceElement("A", "one", "A.java", 1)
        });

        Throwable redacted = ErrorUtils.redactedForLogging(error);

        assertEquals(RuntimeException.class.getName(), redacted.getMessage());
        assertEquals(error.getStackTrace()[0], redacted.getStackTrace()[0]);
        assertNotNull(redacted.getCause());
        assertEquals(IOException.class.getName(), redacted.getCause().getMessage());
        assertFalse(Strings.CS.contains(redacted.toString(), "SECRET_FAILURE"));
        assertFalse(Strings.CS.contains(redacted.getCause().toString(), "SECRET_CAUSE"));
    }
}

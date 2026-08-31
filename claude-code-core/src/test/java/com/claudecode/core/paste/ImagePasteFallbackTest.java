package com.claudecode.core.paste;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Shell clipboard fallback safety tests. */
class ImagePasteFallbackTest {

    @Test
    void screenshotPathsAreUniqueAndPrivateToEachInvocation() throws Exception {
        Path first = ImagePaste.createScreenshotPath();
        Path second = ImagePaste.createScreenshotPath();
        try {
            assertNotEquals(first, second);
            assertTrue(Files.notExists(first));
            assertTrue(Files.notExists(second));
        } finally {
            Files.deleteIfExists(first);
            Files.deleteIfExists(second);
        }
    }

    @Test
    void appleScriptStringEscapesQuotesBackslashesAndNewlines() {
        assertEquals("\"a\\\\b\\\"c\\nd\"",
            ImagePaste.quoteAppleScriptString("a\\b\"c\nd"));
    }

    @Test
    void shellArgumentQuotingTreatsMetacharactersAsLiteralText() {
        assertEquals("'/tmp/a'\"'\"'$(touch nope)'",
            ImagePaste.quoteShellArgument("/tmp/a'$(touch nope)"));
    }

    @Test
    void subprocessTimeoutTerminatesHungCommand() {
        long started = System.nanoTime();

        int result = ImagePaste.runWithTimeout(
            Duration.ofMillis(100), "sh", "-c", "sleep 5");

        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        assertNotEquals(0, result);
        assertTrue(elapsedMillis < 2_000, "hung clipboard process must be terminated promptly");
    }
}

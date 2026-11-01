package com.claudecode.core.paste;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.platform.Platform;
import java.nio.file.Files;
import java.nio.file.Path;
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
    void backslashEscapesCollapseToSingleLevelOnPosixPlatforms() {
        String input = "/Users/me/my\\ file.png";
        if (Platform.CURRENT == Platform.WIN32) {
            // Windows paths keep their backslashes; stripping is deliberately a no-op there.
            assertEquals(input, ImagePaste.stripBackslashEscapes(input));
            return;
        }
        // On POSIX the shell escapes a pasted path, so one escape level is stripped.
        assertEquals("/Users/me/my file.png", ImagePaste.stripBackslashEscapes(input));
        // An escaped backslash survives as a literal one.
        assertEquals("a\\b", ImagePaste.stripBackslashEscapes("a\\\\b"));
        // A trailing lone backslash has nothing to escape and is preserved.
        assertEquals("dir\\", ImagePaste.stripBackslashEscapes("dir\\"));
    }
}
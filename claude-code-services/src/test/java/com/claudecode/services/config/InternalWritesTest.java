package com.claudecode.services.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks the mark/consume/expiry contract for {@link InternalWrites}.
 */
class InternalWritesTest {

    @TempDir Path tmp;

    @BeforeEach
    void reset() {
        InternalWrites.clearInternalWrites();
    }

    @Test
    void unmarkedPath_consumesReturnsFalse() {
        Path settings = tmp.resolve("settings.json");
        assertFalse(InternalWrites.consumeInternalWrite(settings, 5000));
    }

    @Test
    void markedPath_consumedWithinWindow_returnsTrue() {
        Path settings = tmp.resolve("settings.json");
        InternalWrites.markInternalWrite(settings);
        assertTrue(InternalWrites.consumeInternalWrite(settings, 5000));
    }

    @Test
    void consumeIsSingleShot_secondCallReturnsFalse() {
        Path settings = tmp.resolve("settings.json");
        InternalWrites.markInternalWrite(settings);
        assertTrue(InternalWrites.consumeInternalWrite(settings, 5000));
        // Second consume of the same mark must not suppress a genuinely
        // subsequent external write.
        assertFalse(InternalWrites.consumeInternalWrite(settings, 5000));
    }

    @Test
    void markedPath_expiredWindow_returnsFalse() throws InterruptedException {
        Path settings = tmp.resolve("settings.json");
        InternalWrites.markInternalWrite(settings);
        Thread.sleep(50);
        assertFalse(InternalWrites.consumeInternalWrite(settings, 20));
    }

    @Test
    void pathNormalization_matchesAcrossRelativeAndAbsolute() {
        Path abs = tmp.resolve("settings.json").toAbsolutePath();
        InternalWrites.markInternalWrite(abs);
        // Same file reached via a different Path instance should still hit.
        Path viaResolve = tmp.toAbsolutePath().resolve("settings.json");
        assertTrue(InternalWrites.consumeInternalWrite(viaResolve, 5000));
    }

    @Test
    void nullPath_isSafe() {
        assertDoesNotThrow(() -> InternalWrites.markInternalWrite(null));
        assertFalse(InternalWrites.consumeInternalWrite(null, 5000));
    }

    @Test
    void clearInternalWrites_removesAllMarks() {
        Path a = tmp.resolve("settings.json");
        Path b = tmp.resolve("settings.local.json");
        InternalWrites.markInternalWrite(a);
        InternalWrites.markInternalWrite(b);
        InternalWrites.clearInternalWrites();
        assertFalse(InternalWrites.consumeInternalWrite(a, 5000));
        assertFalse(InternalWrites.consumeInternalWrite(b, 5000));
    }
}

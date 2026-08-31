package com.claudecode.core.io;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TempFilePathsTest {
    @Test
    void contentHashProducesStablePromptCacheSafePath() {
        var first = TempFilePaths.generate("claude-settings", ".json", "same");
        var second = TempFilePaths.generate("claude-settings", ".json", "same");
        assertEquals(first, second);
        assertTrue(first.getFileName().toString().matches("claude-settings-[0-9a-f]{16}\\.json"));
    }
}

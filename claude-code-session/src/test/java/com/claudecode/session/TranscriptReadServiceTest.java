package com.claudecode.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.commons.lang3.Strings;

class TranscriptReadServiceTest {
    @TempDir Path tempDir;

    @Test
    void largeTranscriptStartsAtLastNonPreservedCompactBoundary() throws Exception {
        Path file = tempDir.resolve("large.jsonl");
        String old = "{\"type\":\"user\",\"uuid\":\"old\",\"message\":{\"content\":\""
            + "x".repeat(TranscriptReadService.SKIP_PRECOMPACT_THRESHOLD) + "\"}}\n";
        Files.writeString(file, old
            + "{\"type\":\"system\",\"subtype\":\"compact_boundary\",\"uuid\":\"boundary\"}\n"
            + "{\"type\":\"user\",\"uuid\":\"new\",\"message\":{\"content\":\"after\"}}\n");

        String loaded = new String(TranscriptReadService.readTranscriptForLoad(file));

        assertFalse(Strings.CS.contains(loaded, "\"uuid\":\"old\""));
        assertTrue(Strings.CS.contains(loaded, "\"uuid\":\"boundary\""));
        assertTrue(Strings.CS.contains(loaded, "\"uuid\":\"new\""));
    }

    @Test
    void preservedBoundaryKeepsEarlierHistory() throws Exception {
        Path file = tempDir.resolve("preserved.jsonl");
        Files.writeString(file, "{\"type\":\"user\",\"uuid\":\"old\",\"padding\":\""
            + "x".repeat(TranscriptReadService.SKIP_PRECOMPACT_THRESHOLD) + "\"}\n"
            + "{\"type\":\"system\",\"subtype\":\"compact_boundary\","
            + "\"compactMetadata\":{\"preservedSegment\":{\"headUuid\":\"old\"}}}\n");

        String loaded = new String(TranscriptReadService.readTranscriptForLoad(file));

        assertTrue(Strings.CS.contains(loaded, "\"uuid\":\"old\""));
    }
}

package com.claudecode.services.dream;

import org.apache.commons.lang3.Strings;
import com.claudecode.core.memdir.AutoMemoryPrompt;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ConsolidationPromptGeneratorTest {

    private final Path memoryRoot = Path.of("/tmp/fake-memory");
    private final Path transcriptDir = Path.of("/tmp/fake-project/sessions");

    @Test
    void buildsFourPhasePrompt() {
        String p = ConsolidationPromptGenerator.buildConsolidationPrompt(memoryRoot, transcriptDir, null);
        assertTrue(Strings.CS.contains(p, "# Dream: Memory Consolidation"), "title");
        assertTrue(Strings.CS.contains(p, "## Phase 1 — Orient"), "phase 1");
        assertTrue(Strings.CS.contains(p, "## Phase 2 — Gather recent signal"), "phase 2");
        assertTrue(Strings.CS.contains(p, "## Phase 3 — Consolidate"), "phase 3");
        assertTrue(Strings.CS.contains(p, "## Phase 4 — Prune and index"), "phase 4");
    }

    @Test
    void injectsMemoryRootAndTranscriptDir() {
        String p = ConsolidationPromptGenerator.buildConsolidationPrompt(memoryRoot, transcriptDir, null);
        assertTrue(Strings.CS.contains(p, "Memory directory: `" + memoryRoot + "/`"), "memory dir slot");
        assertTrue(Strings.CS.contains(p, "Session transcripts: `" + transcriptDir + "`"), "transcript dir slot");
    }

    @Test
    void reusesMemDirConstants() {
        String p = ConsolidationPromptGenerator.buildConsolidationPrompt(memoryRoot, transcriptDir, null);
        assertTrue(Strings.CS.contains(p, "`" + AutoMemoryPrompt.ENTRYPOINT_NAME + "`"), "entrypoint name referenced");
        assertTrue(Strings.CS.contains(p, String.valueOf(AutoMemoryPrompt.MAX_ENTRYPOINT_LINES)), "max entrypoint lines referenced");
        assertTrue(Strings.CS.contains(p, AutoMemoryPrompt.DIR_EXISTS_GUIDANCE), "dir-exists guidance referenced");
    }

    @Test
    void omitsAdditionalContextWhenExtraIsNull() {
        String p = ConsolidationPromptGenerator.buildConsolidationPrompt(memoryRoot, transcriptDir, null);
        assertFalse(Strings.CS.contains(p, "## Additional context"), "no extra section when null");
    }

    @Test
    void omitsAdditionalContextWhenExtraIsBlank() {
        String p = ConsolidationPromptGenerator.buildConsolidationPrompt(memoryRoot, transcriptDir, "   ");
        assertFalse(Strings.CS.contains(p, "## Additional context"), "no extra section when blank");
    }

    @Test
    void includesAdditionalContextWhenExtraProvided() {
        String p = ConsolidationPromptGenerator.buildConsolidationPrompt(memoryRoot, transcriptDir, "EXTRA NOTE");
        assertTrue(Strings.CS.contains(p, "## Additional context"), "extra section present");
        assertTrue(Strings.CS.contains(p, "EXTRA NOTE"), "extra body present");
    }
}

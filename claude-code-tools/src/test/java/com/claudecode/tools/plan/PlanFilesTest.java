package com.claudecode.tools.plan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.*;





class PlanFilesTest {

    @TempDir Path tempDir;

    private final List<Path> cleanup = new ArrayList<>();

    @BeforeEach
    void configurePlansDirectory() {
        PlanFiles.configurePlansDirectory(tempDir.resolve("plans"));
        PlanFiles.configureMultiPlan(false);
    }

    @AfterEach
    void deleteWrittenFiles() throws Exception {
        for (Path p : cleanup) {
            Files.deleteIfExists(p);
        }
        PlanFiles.resetPlansDirectory();
    }

    @Test
    void getPlanFilePathWithoutAgentIdUsesStableWordSlug() {
        String sessionId = "test-session-" + UUID.randomUUID();
        Path path = PlanFiles.getPlanFilePath(sessionId, null);
        assertTrue(path.getFileName().toString()
            .matches("[a-z]+-[a-z]+-[a-z]+\\.md"));
        assertEquals(path, PlanFiles.getPlanFilePath(sessionId, null));
    }

    @Test
    void getPlanFilePathWithAgentIdSuffixesFileName() {
        String sessionId = "test-session-" + UUID.randomUUID();
        Path path = PlanFiles.getPlanFilePath(sessionId, "a1");
        assertTrue(path.getFileName().toString()
            .matches("[a-z]+-[a-z]+-[a-z]+-agent-a1\\.md"));
    }

    @Test
    void getPlanFilePathCreatesPlansDirectory() {
        String sessionId = "test-session-" + UUID.randomUUID();
        Path path = PlanFiles.getPlanFilePath(sessionId, null);
        assertTrue(Files.isDirectory(path.getParent()));
    }

    @Test
    void configuredDirectoryIsUsedForEveryPlanPath() {
        String sessionId = "test-session-" + UUID.randomUUID();

        Path path = PlanFiles.getPlanFilePath(sessionId, null);

        assertEquals(tempDir.resolve("plans").toAbsolutePath().normalize(), path.getParent());
    }

    @Test
    void getPlanReturnsNullWhenFileDoesNotExist() {
        String sessionId = "test-session-" + UUID.randomUUID();
        assertNull(PlanFiles.getPlan(sessionId, null));
    }

    @Test
    void getPlanReturnsFileContentWhenPresent() throws Exception {
        String sessionId = "test-session-" + UUID.randomUUID();
        Path path = PlanFiles.getPlanFilePath(sessionId, null);
        cleanup.add(path);
        Files.writeString(path, "# Plan\nstep 1");

        assertEquals("# Plan\nstep 1", PlanFiles.getPlan(sessionId, null));
    }

    @Test
    void getPlanNeverThrowsOnMissingFile() {
        String sessionId = "test-session-" + UUID.randomUUID();
        assertDoesNotThrow(() -> PlanFiles.getPlan(sessionId, "nonexistent-agent"));
    }

    @Test
    void disabledFeatureKeepsReleasedSingleSlotAndCreatesNoManifest() throws Exception {
        String sessionId = "test-session-" + UUID.randomUUID();
        var first = PlanFiles.activatePlan(sessionId, null);
        Files.writeString(Path.of(first.planFilePath()), "# First\n");

        PlanFiles.completePlan(sessionId, null, "# First\n", null);
        var reentered = PlanFiles.activatePlan(sessionId, null);

        assertNull(first.planId());
        assertEquals(first.planFilePath(), reentered.planFilePath());
        assertTrue(reentered.recentPlans().isEmpty());
        try (var files = Files.list(PlanFiles.getPlansDirectory())) {
            assertFalse(files.anyMatch(path -> Strings.CS.endsWith(
                path.getFileName().toString(), ".plans.json")));
        }
    }
}

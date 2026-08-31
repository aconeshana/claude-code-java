package com.claudecode.services.compact;

import com.claudecode.tools.plan.PlanFiles;
import com.claudecode.core.plan.PlanCatalogContext;
import com.claudecode.tools.skills.InvokedSkillRegistry;
import com.claudecode.tools.tasks.TaskOutputPaths;
import com.claudecode.tools.tasks.TaskState;
import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.tools.tasks.TaskStore;
import com.claudecode.tools.tasks.TaskType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies the one adapter allowed to translate concrete tool state into compact snapshots. */
class ToolCompactAttachmentStateProviderTest {

    @TempDir Path tempDir;

    private Path previousPlansDirectory;

    @BeforeEach
    void configurePlansDirectory() {
        previousPlansDirectory = PlanFiles.getPlansDirectory();
        PlanFiles.configurePlansDirectory(tempDir.resolve("plans"));
        PlanFiles.configureMultiPlan(false);
    }

    @AfterEach
    void restorePlansDirectory() {
        PlanFiles.configureMultiPlan(false);
        PlanFiles.configurePlansDirectory(previousPlansDirectory);
    }

    @Test
    void freezesTasksPlanModePlanFileAndAgentScopedSkills() throws Exception {
        TaskStore tasks = TaskStore.inMemory();
        TaskState task = tasks.create(TaskType.LOCAL_AGENT, "inspect auth");
        tasks.updateStatus(task.id(), TaskStatus.RUNNING);
        tasks.updateProgressSummary(task.id(), "reading callers");

        InvokedSkillRegistry skills = new InvokedSkillRegistry();
        skills.record(null, "root-skill", "userSettings:root-skill", "root guidance");
        skills.record("agent-7", "agent-skill", "projectSettings:agent-skill", "agent guidance");

        Path planPath = PlanFiles.getPlanFilePath("session-1", "agent-7");
        Files.writeString(planPath, "# Agent plan");

        ToolCompactAttachmentStateProvider provider =
            new ToolCompactAttachmentStateProvider(tasks, skills, () -> true);
        CompactAttachmentStateProvider.Snapshot snapshot =
            provider.snapshot("session-1", "agent-7", true);

        assertEquals("agent-7", snapshot.agentId());
        assertTrue(snapshot.subAgent());
        assertTrue(snapshot.planModeActive());
        assertEquals(planPath, snapshot.planFile().path());
        assertEquals("# Agent plan", snapshot.planFile().content());
        assertEquals(1, snapshot.tasks().size());
        assertEquals("local_agent", snapshot.tasks().getFirst().type());
        assertEquals("running", snapshot.tasks().getFirst().status());
        assertEquals("reading callers", snapshot.tasks().getFirst().deltaSummary());
        assertEquals(TaskOutputPaths.outputPath(task.id()).toString(),
            snapshot.tasks().getFirst().outputFilePath());
        assertEquals(1, snapshot.invokedSkills().size());
        assertEquals("agent-skill", snapshot.invokedSkills().getFirst().name());
    }

    @Test
    void missingSessionHasNoPlanSnapshot() {
        ToolCompactAttachmentStateProvider provider =
            new ToolCompactAttachmentStateProvider(TaskStore.inMemory(),
                new InvokedSkillRegistry(), () -> false);

        CompactAttachmentStateProvider.Snapshot snapshot = provider.snapshot(null, null, false);

        assertFalse(snapshot.planModeActive());
        assertNull(snapshot.planFile());
    }

    @Test
    void multiPlanSnapshotIncludesCurrentCatalogAndRecentHistory() throws Exception {
        PlanFiles.configureMultiPlan(true);
        String sessionId = "compact-multi-plan";
        PlanCatalogContext first = PlanFiles.activatePlan(sessionId, null);
        String firstContent = "# First plan\n\n## Context\nOriginal scope.\n";
        Files.writeString(Path.of(first.planFilePath()), firstContent);
        PlanFiles.completePlan(sessionId, null, firstContent, null);
        PlanCatalogContext second = PlanFiles.activatePlan(sessionId, null);
        Files.writeString(Path.of(second.planFilePath()), "# Draft plan\n");

        ToolCompactAttachmentStateProvider provider =
            new ToolCompactAttachmentStateProvider(TaskStore.inMemory(),
                new InvokedSkillRegistry(), () -> true);
        CompactAttachmentStateProvider.Snapshot snapshot =
            provider.snapshot(sessionId, null, false);

        assertEquals("P002", snapshot.planCatalog().planId());
        assertEquals("P001", snapshot.planCatalog().recentPlans().getFirst().planId());
        assertEquals(Path.of(second.planFilePath()), snapshot.planFile().path());
        assertEquals("# Draft plan\n", snapshot.planFile().content());
    }
}

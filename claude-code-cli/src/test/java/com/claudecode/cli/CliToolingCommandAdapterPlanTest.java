package com.claudecode.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.plan.PlanSlugRegistry;
import com.claudecode.tools.plan.PlanFiles;
import com.claudecode.tools.skills.InvokedSkillRegistry;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskStore;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliToolingCommandAdapterPlanTest {

    @AfterEach
    void resetPlanState() {
        PlanFiles.resetPlansDirectory();
    }

    @Test
    void planPortUsesSessionSlugAndCopiesForkToAnIndependentSlug(@TempDir Path plansDir)
            throws Exception {
        PlanFiles.configurePlansDirectory(plansDir);
        PlanSlugRegistry.set("source-session", "calm-building-harbor");
        PlanSlugRegistry.set("fork-session", "bright-testing-lantern");
        Path source = PlanFiles.getPlanFilePath("source-session", null);
        Files.writeString(source, "# Source plan\n");

        var ports = CliToolingCommandAdapter.create(
            new TaskRegistry(TaskStore.inMemory()), new InvokedSkillRegistry());

        assertEquals(source, ports.plans().planFile("source-session"));

        ports.plans().copy("source-session", "fork-session");

        Path fork = PlanFiles.getPlanFilePath("fork-session", null);
        assertNotEquals(source, fork);
        assertEquals("# Source plan\n", Files.readString(fork));
    }

    @Test
    void multiPlanForkCopiesMainCatalogAndRewritesEveryPlanFile(@TempDir Path plansDir)
            throws Exception {
        PlanFiles.configurePlansDirectory(plansDir);
        PlanFiles.configureMultiPlan(true);
        PlanSlugRegistry.set("source-session", "calm-building-harbor");
        PlanSlugRegistry.set("fork-session", "bright-testing-lantern");

        var first = PlanFiles.activatePlan("source-session", null);
        String firstContent = "# First\n\n## Context\nOriginal scope.\n";
        Files.writeString(Path.of(first.planFilePath()), firstContent);
        PlanFiles.completePlan("source-session", null, firstContent, null);
        var second = PlanFiles.activatePlan("source-session", null);
        Files.writeString(Path.of(second.planFilePath()), "# Current draft\n");

        var agent = PlanFiles.activatePlan("source-session", "agent-7");
        Files.writeString(Path.of(agent.planFilePath()), "# Private agent plan\n");

        var ports = CliToolingCommandAdapter.create(
            new TaskRegistry(TaskStore.inMemory()), new InvokedSkillRegistry());
        ports.plans().copy("source-session", "fork-session");

        var fork = PlanFiles.currentPlanContext("fork-session", null);
        assertEquals("P002", fork.planId());
        assertEquals("bright-testing-lantern-p002.md",
            Path.of(fork.planFilePath()).getFileName().toString());
        assertEquals("# Current draft\n", Files.readString(Path.of(fork.planFilePath())));
        assertEquals("P001", fork.recentPlans().getFirst().planId());
        assertEquals("bright-testing-lantern.md", Path.of(
            fork.recentPlans().getFirst().planFilePath()).getFileName().toString());
        assertFalse(Files.exists(plansDir.resolve(
            "bright-testing-lantern-agent-agent-7.plans.json")));

        Files.writeString(Path.of(fork.recentPlans().getFirst().planFilePath()), "# Fork only\n");
        assertEquals(firstContent, Files.readString(Path.of(first.planFilePath())));

        PlanFiles.completePlan(
            "fork-session", null, Files.readString(Path.of(fork.planFilePath())), null);
        assertEquals("P003", PlanFiles.activatePlan("fork-session", null).planId());
        assertTrue(Files.isRegularFile(
            plansDir.resolve("bright-testing-lantern.plans.json")));
    }
}

package com.claudecode.tools.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.plan.PlanCatalogContext;
import com.claudecode.core.plan.PlanHistoryEntry;
import com.claudecode.core.plan.PlanSlugRegistry;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MultiPlanFilesTest {

    private static final String SESSION_ID = "multi-plan-session";
    private static final String SLUG = "calm-building-harbor";

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        PlanFiles.configurePlansDirectory(tempDir.resolve("plans"));
        PlanFiles.configureMultiPlan(true);
        PlanSlugRegistry.set(SESSION_ID, SLUG);
    }

    @AfterEach
    void tearDown() {
        PlanFiles.resetPlansDirectory();
    }

    @Test
    void approvedPlanRotatesToANewFileWithoutOverwritingHistory() throws Exception {
        PlanCatalogContext first = PlanFiles.activatePlan(SESSION_ID, null);
        assertEquals("P001", first.planId());
        assertEquals("DRAFT", first.planStatus());
        assertEquals(tempDir.resolve("plans").resolve(SLUG + ".md").toString(),
            first.planFilePath());
        assertFalse(first.planExists());
        assertTrue(first.recentPlans().isEmpty());

        Files.writeString(Path.of(first.planFilePath()),
            "# First plan\n\n## Context\nPreserve the original plan forever.\n");
        PlanFiles.PlanCompletion completed = PlanFiles.completePlan(
            SESSION_ID, null, Files.readString(Path.of(first.planFilePath())), null);
        assertEquals("P001", completed.planId());
        assertEquals("First plan", completed.title());

        PlanCatalogContext second = PlanFiles.activatePlan(SESSION_ID, null);
        assertEquals("P002", second.planId());
        assertEquals(tempDir.resolve("plans").resolve(SLUG + "-p002.md").toString(),
            second.planFilePath());
        assertFalse(second.planExists());
        assertEquals("# First plan\n\n## Context\nPreserve the original plan forever.\n",
            Files.readString(Path.of(first.planFilePath())));
        assertEquals(1, second.recentPlans().size());
        assertEquals(new PlanHistoryEntry(
            "P001", "APPROVED", "First plan", "Preserve the original plan forever.",
            first.planFilePath()), second.recentPlans().getFirst());
    }

    @Test
    void unfinishedDraftReusesTheSamePlan() throws Exception {
        PlanCatalogContext first = PlanFiles.activatePlan(SESSION_ID, null);
        Files.writeString(Path.of(first.planFilePath()), "# Draft plan\n");

        PlanCatalogContext resumed = PlanFiles.activatePlan(SESSION_ID, null);

        assertEquals("P001", resumed.planId());
        assertEquals(first.planFilePath(), resumed.planFilePath());
        assertTrue(resumed.planExists());
        assertTrue(resumed.resumedDraft());
    }

    @Test
    void existingLegacyPlanIsImportedAndTheCurrentEntryUsesP002() throws Exception {
        Path legacy = PlanFiles.getPlansDirectory().resolve(SLUG + ".md");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy,
            "# Legacy plan\n\n## Context\nCreated before multi-plan was enabled.\n");

        PlanCatalogContext current = PlanFiles.activatePlan(SESSION_ID, null);

        assertEquals("P002", current.planId());
        assertEquals(1, current.recentPlans().size());
        assertEquals("P001", current.recentPlans().getFirst().planId());
        assertEquals("IMPORTED", current.recentPlans().getFirst().planStatus());
        assertEquals(legacy.toString(), current.recentPlans().getFirst().planFilePath());
        assertTrue(Files.isRegularFile(PlanFiles.getPlansDirectory().resolve(SLUG + ".plans.json")));
    }

    @Test
    void disablingAfterUseKeepsTheManifestActiveFileAsTheLegacySingleSlot() throws Exception {
        PlanCatalogContext first = PlanFiles.activatePlan(SESSION_ID, null);
        Files.writeString(Path.of(first.planFilePath()), "# First\n\n## Context\nOne.\n");
        PlanFiles.completePlan(SESSION_ID, null, Files.readString(Path.of(first.planFilePath())), null);
        PlanCatalogContext second = PlanFiles.activatePlan(SESSION_ID, null);
        Files.writeString(Path.of(second.planFilePath()), "# Second\n\n## Context\nTwo.\n");
        PlanFiles.completePlan(SESSION_ID, null, Files.readString(Path.of(second.planFilePath())), null);

        PlanFiles.configureMultiPlan(false);
        assertEquals(Path.of(second.planFilePath()), PlanFiles.getPlanFilePath(SESSION_ID, null));
        PlanCatalogContext dormant = PlanFiles.activatePlan(SESSION_ID, null);
        assertNull(dormant.planId());
        assertEquals(second.planFilePath(), dormant.planFilePath());
        assertTrue(dormant.recentPlans().isEmpty());

        Files.writeString(Path.of(second.planFilePath()), "# Updated second\n\n## Context\nUpdated.\n");
        PlanFiles.completePlan(
            SESSION_ID, null, Files.readString(Path.of(second.planFilePath())), null);

        PlanFiles.configureMultiPlan(true);
        PlanCatalogContext third = PlanFiles.activatePlan(SESSION_ID, null);
        assertEquals("P003", third.planId());
        assertEquals("Updated second", third.recentPlans().getFirst().title());
    }

    @Test
    void explicitRevisionSupersedesOnlyTheSelectedOlderPlan() throws Exception {
        PlanCatalogContext first = approveActive("# Base\n\n## Context\nBase behavior.\n", null);
        PlanCatalogContext second = PlanFiles.activatePlan(SESSION_ID, null);
        Files.writeString(Path.of(second.planFilePath()),
            "# Revision\n\n## Context\nRefine the base behavior.\n");

        assertNull(PlanFiles.validateRevisionTarget(SESSION_ID, null, "P001"));
        assertTrue(Strings.CS.contains(
            PlanFiles.validateRevisionTarget(SESSION_ID, null, "P002"),
            "current plan"));
        PlanFiles.completePlan(
            SESSION_ID, null, Files.readString(Path.of(second.planFilePath())), "P001");

        PlanCatalogContext third = PlanFiles.activatePlan(SESSION_ID, null);
        assertEquals("P003", third.planId());
        assertEquals("P002", third.recentPlans().getFirst().planId());
        assertEquals("APPROVED", third.recentPlans().getFirst().planStatus());
        assertEquals("P001", third.recentPlans().get(1).planId());
        assertEquals("SUPERSEDED", third.recentPlans().get(1).planStatus());
        assertEquals(first.planFilePath(), third.recentPlans().get(1).planFilePath());
    }

    @Test
    void recentHistoryIsNewestFirstAndLimitedToFivePlans() throws Exception {
        for (int ordinal = 1; ordinal <= 7; ordinal++) {
            approveActive("# Plan " + ordinal + "\n\n## Context\nScope " + ordinal + ".\n", null);
        }

        PlanCatalogContext eighth = PlanFiles.activatePlan(SESSION_ID, null);

        assertEquals("P008", eighth.planId());
        assertEquals(List.of("P007", "P006", "P005", "P004", "P003"),
            eighth.recentPlans().stream().map(PlanHistoryEntry::planId).toList());
    }

    @Test
    void abandonedEmptyPlanIsNotExposedAsHistory() {
        PlanCatalogContext first = PlanFiles.activatePlan(SESSION_ID, null);

        PlanFiles.PlanCompletion abandoned =
            PlanFiles.completePlan(SESSION_ID, null, "   ", null);
        PlanCatalogContext second = PlanFiles.activatePlan(SESSION_ID, null);

        assertEquals(first.planId(), abandoned.planId());
        assertEquals("ABANDONED", abandoned.planStatus());
        assertEquals("P002", second.planId());
        assertTrue(second.recentPlans().isEmpty());
    }

    @Test
    void titleAndSummaryAreNormalizedAndTruncated() throws Exception {
        String title = "T".repeat(100);
        String summary = ("word   ".repeat(50)).trim();
        approveActive("# " + title + "\n\n## Context\n" + summary + "\n", null);

        PlanHistoryEntry history = PlanFiles.activatePlan(SESSION_ID, null)
            .recentPlans().getFirst();

        assertEquals(80, history.title().length());
        assertEquals(200, history.summary().length());
        assertFalse(Strings.CS.contains(history.summary(), "  "));
    }

    @Test
    void corruptManifestIsQuarantinedAndRebuiltWithoutDeletingPlans() throws Exception {
        PlanCatalogContext first = approveActive(
            "# First\n\n## Context\nRecover this file.\n", null);
        PlanCatalogContext second = PlanFiles.activatePlan(SESSION_ID, null);
        Files.writeString(Path.of(second.planFilePath()), "# Draft to recover\n");
        Path manifest = PlanFiles.getPlansDirectory().resolve(SLUG + ".plans.json");
        Files.writeString(manifest, "{not valid json");

        PlanCatalogContext recovered = PlanFiles.activatePlan(SESSION_ID, null);

        assertEquals("P003", recovered.planId());
        assertTrue(Files.isRegularFile(Path.of(first.planFilePath())));
        assertTrue(Files.isRegularFile(Path.of(second.planFilePath())));
        try (var files = Files.list(PlanFiles.getPlansDirectory())) {
            assertTrue(files.anyMatch(path -> Strings.CS.startsWith(
                path.getFileName().toString(), SLUG + ".plans.json.corrupt-")));
        }
    }

    @Test
    void manifestRejectsPathSeparatorsAndRecoversInsideThePlanDirectory() throws Exception {
        PlanCatalogContext first = approveActive(
            "# Safe plan\n\n## Context\nStay inside the plan directory.\n", null);
        Path manifest = PlanFiles.getPlansDirectory().resolve(SLUG + ".plans.json");
        ObjectNode catalog = (ObjectNode) JsonUtils.getMapper()
            .readTree(Files.readString(manifest));
        ((ObjectNode) catalog.path("plans").get(0)).put("fileName", "../escape.md");
        Files.writeString(manifest, JsonUtils.toPrettyJson(catalog));
        Path escape = PlanFiles.getPlansDirectory().getParent().resolve("escape.md");
        Files.writeString(escape, "outside");

        PlanCatalogContext recovered = PlanFiles.activatePlan(SESSION_ID, null);

        assertEquals("P002", recovered.planId());
        assertEquals("outside", Files.readString(escape));
        assertTrue(Files.isRegularFile(Path.of(first.planFilePath())));
    }

    @Test
    void concurrentActivationAllocatesOnlyOneDraftOrdinal() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<String> ids = executor.invokeAll(IntStream.range(0, 12)
                    .mapToObj(_ -> (Callable<String>) () ->
                        PlanFiles.activatePlan(SESSION_ID, null).planId())
                    .toList())
                .stream().map(future -> {
                    try {
                        return future.get();
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                }).toList();

            assertEquals(List.of("P001"), ids.stream().distinct().toList());
        }
    }

    @Test
    void agentAndMainScopesUseIndependentCatalogs() {
        PlanCatalogContext main = PlanFiles.activatePlan(SESSION_ID, null);
        PlanCatalogContext agent = PlanFiles.activatePlan(SESSION_ID, "agent-7");

        assertEquals("P001", main.planId());
        assertEquals("P001", agent.planId());
        assertFalse(Strings.CS.equals(main.planFilePath(), agent.planFilePath()));
        assertTrue(Strings.CS.endsWith(
            agent.planFilePath(), SLUG + "-agent-agent-7.md"));
        assertTrue(Files.isRegularFile(PlanFiles.getPlansDirectory()
            .resolve(SLUG + "-agent-agent-7.plans.json")));
    }

    private PlanCatalogContext approveActive(String content, String revisesPlanId) throws Exception {
        PlanCatalogContext context = PlanFiles.activatePlan(SESSION_ID, null);
        Files.writeString(Path.of(context.planFilePath()), content);
        PlanFiles.completePlan(SESSION_ID, null, content, revisesPlanId);
        return context;
    }
}

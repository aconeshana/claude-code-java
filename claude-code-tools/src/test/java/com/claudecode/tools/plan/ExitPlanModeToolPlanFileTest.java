package com.claudecode.tools.plan;

import org.apache.commons.lang3.Strings;
import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.FileStateCache;
import com.claudecode.core.engine.StructuredToolOutput;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.attachment.AttachmentContext;
import com.claudecode.core.attachment.PlanModeReminderAttachmentProvider;
import com.claudecode.core.message.PlanModeReminderAttachment;
import com.claudecode.core.plan.PlanCatalogContext;
import com.claudecode.permissions.PermissionGate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Disk-backed plan-file wiring for {@link EnterPlanModeTool}/{@link ExitPlanModeTool}
 * (item 7 of the post-compact re-attachment plan). Every test configures an
 * isolated plan directory so it never touches real user data.
 */
class ExitPlanModeToolPlanFileTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir Path tempDir;

    private final String sessionId = "test-session-" + UUID.randomUUID();
    private Path previousPlansDirectory;

    @BeforeEach
    void resetStaticState() {
        previousPlansDirectory = PlanFiles.getPlansDirectory();
        PlanFiles.configurePlansDirectory(tempDir.resolve("plans"));
        PlanFiles.configureMultiPlan(false);
        EnterPlanModeTool.resetPlanMode();
    }

    @AfterEach
    void deletePlanFile() throws Exception {
        try {
            Files.deleteIfExists(PlanFiles.getPlanFilePath(sessionId, null));
        } finally {
            PlanFiles.configureMultiPlan(false);
            PlanFiles.configurePlansDirectory(previousPlansDirectory);
        }
    }

    @Test
    void enterPlanMode_returnsCurrentTsConfirmation() {
        EnterPlanModeTool tool = new EnterPlanModeTool();
        ToolExecutionContext ctx = ToolExecutionContext.of(new AbortController(), sessionId);

        String result = tool.call(MAPPER.createObjectNode(), ctx);

        assertTrue(Strings.CS.contains(result, "Entered plan mode."), result);
        assertTrue(Strings.CS.contains(result, "DO NOT write or edit any files yet"), result);
        assertFalse(Strings.CS.contains(result,
            "approval\n\nRemember:"), "2.1.197 has no blank line before Remember");
    }

    @Test
    void enterPlanMode_doesNotReadOrRewriteExistingPlanFile() throws Exception {
        Path planFile = PlanFiles.getPlanFilePath(sessionId, null);
        Files.writeString(planFile, "# Existing plan");

        EnterPlanModeTool tool = new EnterPlanModeTool();
        ToolExecutionContext ctx = ToolExecutionContext.of(new AbortController(), sessionId);
        String result = tool.call(MAPPER.createObjectNode(), ctx);

        assertTrue(Strings.CS.contains(result, "Entered plan mode."), result);
        assertEquals("# Existing plan", Files.readString(planFile));
    }

    @Test
    void exitPlanMode_withNoPlanFileWritten_returnsGenericApproval() {
        ExitPlanModeTool tool = new ExitPlanModeTool();
        ToolExecutionContext ctx = ToolExecutionContext.of(new AbortController(), sessionId);

        StructuredToolOutput result = tool.call(MAPPER.createObjectNode(), ctx);

        assertEquals("User has approved exiting plan mode. You can now proceed.", result.text());
        assertInstanceOf(JsonNode.class, result.toolUseResult());
        JsonNode data = (JsonNode) result.toolUseResult();
        assertTrue(data.has("plan"), "released JSONL preserves the explicit plan:null field");
        assertTrue(data.get("plan").isNull());
        assertFalse(data.path("isAgent").asBoolean());
        assertEquals(PlanFiles.getPlanFilePath(sessionId, null).toString(), data.path("filePath").asText());
        assertFalse(data.has("planId"));
        assertFalse(data.has("title"));
        assertFalse(data.has("revisesPlanId"));
    }

    @Test
    void exitPlanMode_readsPlanWrittenToDiskAndEchoesItBack() throws Exception {
        Path planFile = PlanFiles.getPlanFilePath(sessionId, null);
        Files.writeString(planFile, "# The Plan\n1. Do the thing");

        ExitPlanModeTool tool = new ExitPlanModeTool();
        ToolExecutionContext ctx = ToolExecutionContext.of(new AbortController(), sessionId);
        StructuredToolOutput result = tool.call(MAPPER.createObjectNode(), ctx);

        assertEquals("User has approved your plan. You can now start coding. Start with updating "
            + "your todo list if applicable\n"
            + "Your plan has been saved to: " + planFile + "\n"
            + "You can refer back to it if needed during implementation.\n"
            + "## Approved Plan:\n"
            + "# The Plan\n1. Do the thing", result.text());
        JsonNode data = (JsonNode) result.toolUseResult();
        assertEquals("# The Plan\n1. Do the thing", data.path("plan").asText());
        assertFalse(data.path("isAgent").asBoolean());
        assertEquals(planFile.toString(), data.path("filePath").asText());
    }

    @Test
    void exitPlanMode_marksUserEditedPlanInReleasedHeading() {
        ExitPlanModeTool tool = new ExitPlanModeTool();
        ToolExecutionContext ctx = ToolExecutionContext.of(new AbortController(), sessionId);
        JsonNode input = MAPPER.createObjectNode().put("plan", "# Edited plan");

        StructuredToolOutput result = tool.call(input, ctx);

        assertTrue(Strings.CS.contains(result.text(), "## Approved Plan (edited by user):"));
        JsonNode data = (JsonNode) result.toolUseResult();
        assertTrue(data.path("planWasEdited").asBoolean());
    }

    @Test
    void multiPlanSchemaAndPromptAreEnabledOnlyByTheFeatureGate() {
        ExitPlanModeTool tool = new ExitPlanModeTool();
        assertFalse(tool.inputSchema().path("properties").has("revisesPlanId"));
        assertFalse(Strings.CS.contains(tool.prompt(null), "revisesPlanId"));

        PlanFiles.configureMultiPlan(true);

        assertEquals("string", tool.inputSchema().path("properties")
            .path("revisesPlanId").path("type").asText());
        assertTrue(Strings.CS.contains(tool.prompt(null), "revisesPlanId"));
    }

    @Test
    void multiPlanExitApprovesCatalogAndReturnsCurrentMetadata() throws Exception {
        PlanFiles.configureMultiPlan(true);
        PlanCatalogContext active = PlanFiles.activatePlan(sessionId, null);
        String plan = "# Indexed plan\n\n## Context\nKeep prior plans available.\n";
        Files.writeString(Path.of(active.planFilePath()), plan);

        StructuredToolOutput result = new ExitPlanModeTool().call(
            MAPPER.createObjectNode(), ToolExecutionContext.of(new AbortController(), sessionId));

        JsonNode data = (JsonNode) result.toolUseResult();
        assertEquals("P001", data.path("planId").asText());
        assertEquals("Indexed plan", data.path("title").asText());
        assertEquals("APPROVED", data.path("planStatus").asText());
        assertFalse(data.has("revisesPlanId"));
        PlanCatalogContext persisted = PlanFiles.currentPlanContext(sessionId, null);
        assertEquals("APPROVED", persisted.planStatus());
    }

    @Test
    void multiPlanExitRejectsCurrentPlanAsRevisionTarget() throws Exception {
        PlanFiles.configureMultiPlan(true);
        PermissionGate gate = new PermissionGate();
        ToolExecutionContext context = ToolExecutionContext.of(new AbortController(), sessionId);
        new EnterPlanModeTool(gate).call(MAPPER.createObjectNode(), context);
        PlanCatalogContext active = PlanFiles.activatePlan(sessionId, null);
        Files.writeString(Path.of(active.planFilePath()), "# Draft\n");
        JsonNode input = MAPPER.createObjectNode().put("revisesPlanId", "P001");

        var validation = new ExitPlanModeTool(gate).validateInput(input, context);

        assertInstanceOf(com.claudecode.tools.ValidationResult.Invalid.class, validation);
        assertTrue(Strings.CS.contains(
            ((com.claudecode.tools.ValidationResult.Invalid) validation).message(),
            "current plan"));
    }

    @Test
    void approvedPlanReentryRotatesAndDoesNotEmitReleasedOverwriteGuidance() throws Exception {
        PlanFiles.configureMultiPlan(true);
        PermissionGate gate = new PermissionGate();
        ToolExecutionContext context = ToolExecutionContext.of(new AbortController(), sessionId);
        EnterPlanModeTool enter = new EnterPlanModeTool(gate);
        enter.call(MAPPER.createObjectNode(), context);
        PlanCatalogContext first = PlanFiles.activatePlan(sessionId, null);
        Files.writeString(Path.of(first.planFilePath()), "# First plan\n");
        new ExitPlanModeTool(gate).call(MAPPER.createObjectNode(), context);
        enter.call(MAPPER.createObjectNode(), context);
        var provider = new PlanModeReminderAttachmentProvider(
            () -> gate.currentMode().kind(),
            () -> PlanFiles.activatePlan(sessionId, null),
            () -> gate.consumePlanModeReentry(true));

        var attachments = provider.collect(AttachmentContext.builder(tempDir.toString())
            .messages(java.util.List.of())
            .input("")
            .fileStateCache(new FileStateCache())
            .querySource("main")
            .build());

        assertEquals(1, attachments.size());
        PlanModeReminderAttachment reminder =
            (PlanModeReminderAttachment) attachments.getFirst();
        assertEquals("P002", reminder.planId());
        assertTrue(Strings.CS.endsWith(reminder.planFilePath(), "-p002.md"));
    }

    @Test
    void explicitRevisionIsReturnedAndSupersedesTheSelectedPlan() throws Exception {
        PlanFiles.configureMultiPlan(true);
        String original = "# Original\n\n## Context\nOriginal scope.\n";
        PlanCatalogContext first = PlanFiles.activatePlan(sessionId, null);
        Files.writeString(Path.of(first.planFilePath()), original);
        PlanFiles.completePlan(sessionId, null, original, null);
        PlanCatalogContext second = PlanFiles.activatePlan(sessionId, null);
        Files.writeString(Path.of(second.planFilePath()),
            "# Revised\n\n## Context\nReplace the original scope.\n");
        JsonNode input = MAPPER.createObjectNode().put("revisesPlanId", "P001");

        StructuredToolOutput result = new ExitPlanModeTool().call(
            input, ToolExecutionContext.of(new AbortController(), sessionId));

        JsonNode data = (JsonNode) result.toolUseResult();
        assertEquals("P002", data.path("planId").asText());
        assertEquals("P001", data.path("revisesPlanId").asText());
        PlanCatalogContext next = PlanFiles.activatePlan(sessionId, null);
        assertEquals("SUPERSEDED", next.recentPlans().stream()
            .filter(plan -> Strings.CS.equals("P001", plan.planId()))
            .findFirst().orElseThrow().planStatus());
    }
}

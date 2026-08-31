package com.claudecode.tools.plan;

import org.apache.commons.lang3.Strings;
import static org.junit.jupiter.api.Assertions.*;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.TextBlock;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.PermissionMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.claudecode.tools.files.FileWriteTool;
import com.claudecode.tools.ToolRegistry;
import com.claudecode.tools.ValidationResult;

class PlanModeToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void resetStaticState() {
        // PLAN_MODE_ACTIVE is static — clear between tests.
        EnterPlanModeTool.resetPlanMode();
        PlanFiles.configureMultiPlan(false);
    }

    @Test
    void enterPlanMode_flipsGateToPlanMode() {
        PermissionGate gate = new PermissionGate();
        gate.setMode(PermissionMode.BYPASS_PERMISSIONS);
        EnterPlanModeTool tool = new EnterPlanModeTool(gate);
        ToolExecutionContext ctx = ToolExecutionContext.of(new AbortController(), "test");

        String result = tool.call(MAPPER.createObjectNode(), ctx);

        assertEquals(PermissionMode.PLAN, gate.currentMode());
        assertTrue(EnterPlanModeTool.isPlanModeActive());
        assertTrue(Strings.CI.contains(result, "plan mode"),
            "tool result should mention plan mode; got: " + result);
    }

    @Test
    void enterPlanMode_isReadOnlyAndDoesNotRequestInteractivePermission() {
        PermissionGate gate = new PermissionGate();
        EnterPlanModeTool tool = new EnterPlanModeTool(gate);

        assertInstanceOf(PermissionDecision.Allow.class,
            tool.checkPermissions(MAPPER.createObjectNode(), null));
    }

    @Test
    void exitPlanMode_restoresPreviousMode() {
        PermissionGate gate = new PermissionGate();
        gate.setMode(PermissionMode.ACCEPT_EDITS);
        EnterPlanModeTool enter = new EnterPlanModeTool(gate);
        ExitPlanModeTool exit = new ExitPlanModeTool(gate);
        ToolExecutionContext ctx = ToolExecutionContext.of(new AbortController(), "test");

        enter.call(MAPPER.createObjectNode(), ctx);
        assertEquals(PermissionMode.PLAN, gate.currentMode());

        exit.call(MAPPER.createObjectNode(), ctx);
        assertEquals(PermissionMode.ACCEPT_EDITS, gate.currentMode(),
            "exit should restore the user's prior mode");
        assertFalse(EnterPlanModeTool.isPlanModeActive());
    }

    @Test
    void exitPlanMode_preservesModeChosenByApprovalUi() {
        PermissionGate gate = new PermissionGate();
        gate.setMode(PermissionMode.DEFAULT);
        EnterPlanModeTool enter = new EnterPlanModeTool(gate);
        ExitPlanModeTool exit = new ExitPlanModeTool(gate);
        ToolExecutionContext ctx = ToolExecutionContext.of(new AbortController(), "test");

        enter.call(MAPPER.createObjectNode(), ctx);
        assertEquals(PermissionMode.PLAN, gate.currentMode());

        // ExitPlanModePermissionRequest applies the selected session mode before
        // allowing the tool. The tool must not overwrite that choice afterward.
        gate.setMode(PermissionMode.ACCEPT_EDITS);
        exit.call(MAPPER.createObjectNode(), ctx);

        assertEquals(PermissionMode.ACCEPT_EDITS, gate.currentMode());
        assertFalse(EnterPlanModeTool.isPlanModeActive());
    }

    @Test
    void enterPlanModeHeadlessWriteUsesNormalAutoRejectPath() {
        PermissionGate gate = new PermissionGate();
        ToolRegistry registry = new ToolRegistry();
        registry.setPermissionGate(gate);
        registry.register(new FileWriteTool());
        registry.register(new EnterPlanModeTool(gate));

        ToolExecutionContext ctx = ToolExecutionContext.of(new AbortController(), "test");

        ObjectNode writeInput = MAPPER.createObjectNode();
        writeInput.put("file_path", "/tmp/__plan_mode_test.txt");
        writeInput.put("content", "x");

        // Enter plan mode through the tool.
        registry.execute("EnterPlanMode", MAPPER.createObjectNode(), ctx);


        var blockedResult = registry.execute("Write", writeInput, ctx);
        assertTrue(blockedResult.isError(), "headless Write should be rejected after entering plan mode");
        assertEquals(MessageConstants.autoRejectMessage("Write"),
            ((TextBlock) blockedResult.content().getFirst()).text());
    }

    @Test
    void exitPlanMode_withoutGate_doesNotCrash() {
        // Legacy code path: no gate wired (tests, headless).
        ExitPlanModeTool exit = new ExitPlanModeTool();
        ToolExecutionContext ctx = ToolExecutionContext.of(new AbortController(), "test");
        // Just confirm it returns a non-null string.
        assertNotNull(exit.call(MAPPER.createObjectNode(), ctx));
    }

    @Test
    void exitPlanMode_withoutSessionContextFailsClearlyInsteadOfNpe() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> new ExitPlanModeTool().call(MAPPER.createObjectNode(), null));
        assertEquals("ExitPlanMode requires a non-blank session context", error.getMessage());
    }

    @Test
    void exitPlanMode_rejectsWhenNotInPlanMode() {

        ExitPlanModeTool exit = new ExitPlanModeTool();
        ToolExecutionContext ctx = ToolExecutionContext.of(new AbortController(), "test");

        ValidationResult vr = exit.validateInput(MAPPER.createObjectNode(), ctx);
        assertInstanceOf(ValidationResult.Invalid.class, vr, "ExitPlanMode must reject when not in plan mode");
        assertEquals(
            "You are not in plan mode. To enter plan mode, call the EnterPlanMode tool first. "
                + "If your plan was already approved, continue with implementation.",
            ((ValidationResult.Invalid) vr).message());
    }

    @Test
    void exitPlanMode_allowsWhenInPlanMode() {
        EnterPlanModeTool enter = new EnterPlanModeTool();
        ExitPlanModeTool exit = new ExitPlanModeTool();
        ToolExecutionContext ctx = ToolExecutionContext.of(new AbortController(), "test");

        enter.call(MAPPER.createObjectNode(), ctx);
        assertTrue(EnterPlanModeTool.isPlanModeActive());

        ValidationResult vr = exit.validateInput(MAPPER.createObjectNode(), ctx);
        assertInstanceOf(ValidationResult.Valid.class, vr, "ExitPlanMode must be allowed once in plan mode");
    }

    @Test
    void exitPlanMode_allowsWhenCliStartsDirectlyInPlanMode() {
        PermissionGate gate = new PermissionGate();
        gate.setMode(PermissionMode.PLAN);
        ExitPlanModeTool exit = new ExitPlanModeTool(gate);
        ToolExecutionContext ctx = ToolExecutionContext.of(new AbortController(), "test");

        ValidationResult vr = exit.validateInput(MAPPER.createObjectNode(), ctx);

        assertInstanceOf(ValidationResult.Valid.class, vr, "--permission-mode plan is authoritative even without EnterPlanMode tool state");
    }

    @Test
    void exitPlanMode_requiresUserApprovalWithReleasedPrompt() {
        ExitPlanModeTool exit = new ExitPlanModeTool();
        ObjectNode input = MAPPER.createObjectNode();

        assertTrue(exit.requiresUserInteraction());
        PermissionDecision decision = exit.checkPermissions(input, null);
        assertInstanceOf(PermissionDecision.Ask.class, decision);
        PermissionDecision.Ask ask = (PermissionDecision.Ask) decision;
        assertEquals("Exit plan mode?", ask.message());
        assertEquals(input, ask.updatedInput());
        assertEquals(100_000, exit.maxResultSizeChars());
    }

    @Test
    void exitPlanMode_registryCarriesStructuredToolUseResult() {
        PermissionGate gate = new PermissionGate();
        gate.setMode(PermissionMode.PLAN);
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ExitPlanModeTool(gate));
        ToolExecutionContext ctx = ToolExecutionContext.of(new AbortController(), "test");

        var result = registry.execute("ExitPlanMode", MAPPER.createObjectNode(), ctx);

        assertFalse(result.isError());
        assertInstanceOf(JsonNode.class, result.toolUseResult());
        JsonNode data = (JsonNode) result.toolUseResult();
        assertTrue(data.has("plan"));
        assertTrue(data.get("plan").isNull());
        assertFalse(data.path("isAgent").asBoolean());
        assertEquals(PlanFiles.getPlanFilePath("test", null).toString(), data.path("filePath").asText());
    }

    // ── Prompt ports ────────────────────────────────────────────────────────

    @Test
    void enterPlanMode_descriptionPorted() {
        EnterPlanModeTool tool = new EnterPlanModeTool(null);
        String d = tool.description();
        String prompt = tool.prompt(null);
        assertEquals(
            "Requests permission to enter plan mode for complex tasks requiring exploration and design",
            d);
        assertTrue(Strings.CS.contains(prompt, "Getting user sign-off on your approach"), prompt);
        assertTrue(Strings.CS.contains(prompt, "## When to Use This Tool"), prompt);
        assertTrue(Strings.CS.contains(prompt, "## When NOT to Use This Tool"), prompt);
        assertTrue(Strings.CS.contains(prompt, "## What Happens in Plan Mode"), prompt);
        assertTrue(Strings.CS.contains(prompt, "### GOOD - Use EnterPlanMode"), prompt);
        assertTrue(Strings.CS.contains(prompt, "### BAD - Don't use EnterPlanMode"), prompt);
        assertTrue(Strings.CS.contains(prompt, "REQUIRES user approval"), prompt);
    }

    @Test
    void exitPlanMode_descriptionPorted() {
        String d = new ExitPlanModeTool(null).description();
        assertEquals("Prompts the user to exit plan mode and start coding", d);
        String prompt = new ExitPlanModeTool(null).prompt(null);
        assertTrue(Strings.CS.contains(prompt, "when you are in plan mode and have finished writing your plan"));
        assertTrue(Strings.CS.contains(prompt, "This tool does NOT take the plan content as a parameter"));
        assertTrue(Strings.CS.contains(prompt, "AskUserQuestion"));
        assertTrue(Strings.CS.contains(prompt, "ExitPlanMode inherently requests user approval"));
    }

    @Test
    void exitPlanMode_additionalPropertiesIsPermissiveSubSchema() {

        // empty sub-schema `{}`, not the boolean `true` — both mean
        // "permissive" to SchemaValidator (only literal false rejects extra
        // keys), so match the wire shape byte-for-byte.
        var ap = new ExitPlanModeTool(null).inputSchema().get("additionalProperties");
        assertTrue(ap.isObject(), "additionalProperties must be an object, not a boolean");
        assertEquals(0, ap.size(), "additionalProperties must be an empty sub-schema");
    }
}

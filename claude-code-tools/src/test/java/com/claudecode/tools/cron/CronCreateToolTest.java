package com.claudecode.tools.cron;

import org.apache.commons.lang3.Strings;
import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.tools.tasks.teammate.TeammateContext;
import com.claudecode.tools.tasks.teammate.TeammateContextHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import com.claudecode.tools.ToolTexts;
import com.claudecode.tools.ValidationResult;


class CronCreateToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolExecutionContext ctx() {
        return ToolExecutionContext.of(new AbortController(), "test");
    }

    private JsonNode input(String cron, String prompt) {
        var node = MAPPER.createObjectNode();
        node.put("cron", cron);
        node.put("prompt", prompt);
        return node;
    }

    @AfterEach
    void resetState() {
        TeammateContextHolder.clear();
        CronStore.resetForTest();
    }

    @Test
    void malformedCronExpression_rejectedAsError() {
        CronCreateTool tool = new CronCreateTool();
        ValidationResult vr = tool.validateInput(input("not-a-cron", "do something"), ctx());
        assertInstanceOf(ValidationResult.Invalid.class, vr, "malformed cron must be rejected");
        assertTrue(Strings.CS.contains(((ValidationResult.Invalid) vr).message(), "Invalid cron expression"),
            ((ValidationResult.Invalid) vr).message());
    }

    @Test
    void impossibleCronExpression_rejectedAsError() {
        CronCreateTool tool = new CronCreateTool();
        // Feb 30 never occurs. With dow unconstrained (so dayMatches collapses to
        // dom-only), nextRunMs is null. Note: cron uses OR semantics for dom+dow, so
        // a constraint like "0 0 30 2 1" is NOT impossible (Mondays in Feb still match).
        ValidationResult vr = tool.validateInput(input("0 0 30 2 *", "x"), ctx());
        assertInstanceOf(ValidationResult.Invalid.class, vr, "impossible cron must be rejected");
        assertTrue(Strings.CS.contains(((ValidationResult.Invalid) vr).message(), "does not match any calendar date"),
            ((ValidationResult.Invalid) vr).message());
    }

    @Test
    void validCronExpression_passesValidation() {
        CronCreateTool tool = new CronCreateTool();
        ValidationResult vr = tool.validateInput(input("*/5 * * * *", "check deploy"), ctx());
        assertInstanceOf(ValidationResult.Valid.class, vr, "valid cron must pass validation: " + vr);
    }

    @Test
    void schemaIsStrict() {
        JsonNode ap = new CronCreateTool().inputSchema().get("additionalProperties");
        assertNotNull(ap);
        assertFalse(ap.asBoolean(), "input schema must reject unknown keys (z.strictObject)");
    }

    @Test
    void emptyPromptIsAllowed_matchingTs() {

        // created. The Java tool must not reject on empty prompt.
        CronCreateTool tool = new CronCreateTool();
        ValidationResult vr = tool.validateInput(input("0 9 * * *", ""), ctx());
        assertInstanceOf(ValidationResult.Valid.class, vr, "empty prompt must not be rejected: " + vr);
    }

    @Test
    void durableCronIsRejectedForTeammates() {
        var node = (ObjectNode) input("0 9 * * *", "daily");
        node.put("durable", true);
        TeammateContextHolder.set(TeammateContext.builder().agentId("agent-a").build());

        ValidationResult result = new CronCreateTool().validateInput(node, ctx());

        assertInstanceOf(ValidationResult.Invalid.class, result);
        assertEquals(
            "durable crons are not supported for teammates (teammates do not persist across sessions)",
            ((ValidationResult.Invalid) result).message());
    }

    @Test
    void sessionCronRecordsOwningTeammate() {
        var node = input("*/5 * * * *", "check deploy");
        TeammateContextHolder.set(TeammateContext.builder().agentId("agent-a").build());

        new CronCreateTool().call(node, ctx());

        assertEquals("agent-a", CronStore.list().getFirst().agentId());
    }

    @Test
    void durableGateChangesPromptButKeepsTheStableInputSchema() {
        CronCreateTool tool = new CronCreateTool(() -> true, () -> false);

        assertEquals(ToolTexts.description("CronCreate", "session-only"), tool.description());
        assertEquals(ToolTexts.prompt("CronCreate", "session-only"), tool.prompt(ctx()));
        assertTrue(Strings.CS.contains(tool.prompt(ctx()), "## Session-only"));
        assertFalse(Strings.CS.contains(tool.prompt(ctx()), "## Durability"));
        assertTrue(Strings.CS.startsWith(tool.description(), "Schedule a prompt"));
        assertTrue(tool.inputSchema().path("properties").has("durable"));
    }

    @Test
    void durablePromptComesFromTheFrozen197Resource() {
        CronCreateTool tool = new CronCreateTool(() -> true, () -> true);

        assertEquals(ToolTexts.description("CronCreate", "durable"), tool.description());
        assertEquals(ToolTexts.prompt("CronCreate", "durable"), tool.prompt(ctx()));
    }

    @Test
    void durableGateForcesRequestedDurableCronToSessionOnly() {
        var node = (ObjectNode)
            input("0 9 * * *", "daily");
        node.put("durable", true);

        String result = new CronCreateTool(() -> true, () -> false).call(node, ctx());

        assertFalse(CronStore.list().getFirst().durable());
        assertTrue(Strings.CS.contains(result, "Session-only"), result);
    }

    @Test
    void durableCronRecordsTheCreatingSessionForReleasedOwnership(@TempDir Path projectRoot) {
        CronStore.configureProjectRootForTest(projectRoot);
        var node = (ObjectNode) input("0 9 * * *", "daily");
        node.put("durable", true);

        new CronCreateTool(() -> true, () -> true).call(node, ctx());

        CronStore.CronJob job = CronStore.list().getFirst();
        assertEquals("test", job.createdBySessionId());
        assertEquals(ProcessHandle.current().pid(), job.createdByPid());
        assertNotNull(job.createdByProcStart());
    }

    @Test
    void cronKillSwitchControlsToolVisibility() {
        assertFalse(new CronCreateTool(() -> false, () -> true).isEnabled());
    }

}

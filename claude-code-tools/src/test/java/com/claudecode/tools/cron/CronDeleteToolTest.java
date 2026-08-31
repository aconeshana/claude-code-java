package com.claudecode.tools.cron;

import org.apache.commons.lang3.Strings;
import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.tools.tasks.teammate.TeammateContext;
import com.claudecode.tools.tasks.teammate.TeammateContextHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import com.claudecode.tools.ToolTexts;
import com.claudecode.tools.ValidationResult;


class CronDeleteToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolExecutionContext ctx() {
        return ToolExecutionContext.of(new AbortController(), "test");
    }

    @AfterEach
    void resetState() {
        TeammateContextHolder.clear();
        CronStore.resetForTest();
    }

    @Test
    void nonExistentId_rejectedAsError() {
        CronDeleteTool tool = new CronDeleteTool();
        var input = MAPPER.createObjectNode();
        input.put("id", "does-not-exist");
        ValidationResult vr = tool.validateInput(input, ctx());
        assertInstanceOf(ValidationResult.Invalid.class, vr, "missing job must be rejected");
        assertTrue(Strings.CS.contains(((ValidationResult.Invalid) vr).message(), "No scheduled job with id"),
            ((ValidationResult.Invalid) vr).message());
    }

    @Test
    void emptyId_rejectedAsError() {
        CronDeleteTool tool = new CronDeleteTool();
        ValidationResult vr = tool.validateInput(MAPPER.createObjectNode(), ctx());
        assertInstanceOf(ValidationResult.Invalid.class, vr, "empty id must be rejected");
        assertTrue(Strings.CS.contains(((ValidationResult.Invalid) vr).message(), "id is required"),
            ((ValidationResult.Invalid) vr).message());
    }

    @Test
    void existingId_passesValidationAndDeletes() {
        String id = CronStore.add("*/5 * * * *", "x", true, false);
        try {
            CronDeleteTool tool = new CronDeleteTool();
            var input = MAPPER.createObjectNode();
            input.put("id", id);
            ValidationResult vr = tool.validateInput(input, ctx());
            assertInstanceOf(ValidationResult.Valid.class, vr, "existing job must pass validation: " + vr);
            String result = tool.call(input, ctx());
            assertTrue(Strings.CS.contains(result, "Cancelled job " + id), result);
        } finally {
            CronStore.remove(id);
        }
    }

    @Test
    void schemaIsStrict() {
        JsonNode ap = new CronDeleteTool().inputSchema().get("additionalProperties");
        assertNotNull(ap);
        assertFalse(ap.asBoolean(), "input schema must reject unknown keys (z.strictObject)");
    }

    @Test
    void teammateCannotDeleteAnotherAgentsCron() {
        String id = CronStore.add("*/5 * * * *", "x", true, false, "agent-a");
        TeammateContextHolder.set(TeammateContext.builder().agentId("agent-b").build());
        var input = MAPPER.createObjectNode().put("id", id);

        ValidationResult result = new CronDeleteTool().validateInput(input, ctx());

        assertInstanceOf(ValidationResult.Invalid.class, result);
        assertEquals("Cannot delete cron job '" + id + "': owned by another agent",
            ((ValidationResult.Invalid) result).message());
    }

    @Test
    void teammateCanDeleteOwnCron() {
        String id = CronStore.add("*/5 * * * *", "x", true, false, "agent-a");
        TeammateContextHolder.set(TeammateContext.builder().agentId("agent-a").build());
        var input = MAPPER.createObjectNode().put("id", id);

        ValidationResult result = new CronDeleteTool().validateInput(input, ctx());

        assertInstanceOf(ValidationResult.Valid.class, result);
    }

    @Test
    void promptTracksTheDurableGate() {
        CronDeleteTool tool = new CronDeleteTool(() -> true, () -> true);
        assertEquals(ToolTexts.description("CronDelete"), tool.description());
        assertEquals("Cancel a scheduled cron job by ID", tool.description());
        String prompt = tool.prompt(null);

        assertEquals(ToolTexts.prompt("CronDelete", "durable"), prompt);
        assertEquals("Cancel a cron job previously scheduled with CronCreate. "
            + "Removes it from .claude/scheduled_tasks.json (durable jobs) or the "
            + "in-memory session store (session-only jobs).", prompt);

        CronDeleteTool sessionOnly = new CronDeleteTool(() -> true, () -> false);
        assertEquals(ToolTexts.prompt("CronDelete", "session-only"), sessionOnly.prompt(null));
        assertEquals("Cancel a cron job previously scheduled with CronCreate. "
            + "Removes it from the in-memory session store.", sessionOnly.prompt(null));
    }
}

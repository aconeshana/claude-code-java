package com.claudecode.core.message;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.claudecode.core.plan.PlanHistoryEntry;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip and wire-shape tests for {@link AttachmentPayload}'s 6 sealed variants, and the {@link
 * AttachmentMessage} envelope that carries them.
 */
class AttachmentPayloadSerializationTest {

    private final ObjectMapper mapper = JsonUtils.getMapper();

    private <T extends AttachmentPayload> T roundtrip(T payload) throws Exception {
        String json = mapper.writeValueAsString(payload);
        @SuppressWarnings("unchecked")
        T back = (T) mapper.readValue(json, AttachmentPayload.class);
        return back;
    }

    @Test
    void compactFileReferenceRoundtripsAndTagsType() throws Exception {
        CompactFileReferenceAttachment payload = new CompactFileReferenceAttachment("/tmp/big.txt");
        JsonNode node = mapper.valueToTree(payload);
        assertEquals("compact_file_reference", node.get("type").asText());
        assertEquals("/tmp/big.txt", node.get("filename").asText());
        assertEquals(payload, roundtrip(payload));
    }

    @Test
    void fileContentRoundtripsAndTagsType() throws Exception {
        FileContentAttachment payload = new FileContentAttachment("/tmp/a.txt", "hello world");
        JsonNode node = mapper.valueToTree(payload);
        assertEquals("file", node.get("type").asText());
        assertEquals("hello world", node.get("content").asText());
        assertEquals(payload, roundtrip(payload));
    }

    @Test
    void planFileReferenceRoundtripsAndTagsType() throws Exception {
        PlanFileReferenceAttachment payload =
            new PlanFileReferenceAttachment("/plans/s1.md", "# Plan\ndo the thing");
        JsonNode node = mapper.valueToTree(payload);
        assertEquals("plan_file_reference", node.get("type").asText());
        assertEquals("/plans/s1.md", node.get("planFilePath").asText());
        assertEquals(payload, roundtrip(payload));
    }

    @Test
    void planModeReminderRoundtripsAndTagsType() throws Exception {
        PlanModeReminderAttachment payload =
            new PlanModeReminderAttachment("sparse", true, "/plans/s1-agent-a1.md", false);
        JsonNode node = mapper.valueToTree(payload);
        assertEquals("plan_mode", node.get("type").asText());
        assertEquals("sparse", node.get("reminderType").asText());
        assertTrue(node.get("isSubAgent").asBoolean());
        assertFalse(node.get("planExists").asBoolean());
        assertEquals(payload, roundtrip(payload));
    }

    @Test
    void legacyPlanModeReminderOmitsMultiPlanFields() {
        JsonNode node = mapper.valueToTree(
            new PlanModeReminderAttachment(false, "/plans/s1.md", false));

        assertFalse(node.has("planId"));
        assertFalse(node.has("planStatus"));
        assertFalse(node.has("resumedDraft"));
        assertFalse(node.has("recentPlans"));
    }

    @Test
    void multiPlanReminderRoundtripsOptionalCatalogFields() throws Exception {
        PlanModeReminderAttachment payload = new PlanModeReminderAttachment(
            "full", false, "/plans/s1-p002.md", true,
            "P002", "DRAFT", true, List.of(new PlanHistoryEntry(
                "P001", "APPROVED", "Initial plan", "Original scope.",
                "/plans/s1.md")));

        JsonNode node = mapper.valueToTree(payload);

        assertEquals("P002", node.path("planId").asText());
        assertTrue(node.path("resumedDraft").asBoolean());
        assertEquals("P001", node.path("recentPlans").get(0).path("planId").asText());
        assertEquals(payload, roundtrip(payload));
    }

    @Test
    void autoModeReminderRoundtripsAndTagsType() throws Exception {
        AutoModeReminderAttachment payload = new AutoModeReminderAttachment("full");
        JsonNode node = mapper.valueToTree(payload);
        assertEquals("auto_mode", node.get("type").asText());
        assertEquals("full", node.get("reminderType").asText());
        assertEquals(payload, roundtrip(payload));
    }

    @Test
    void invokedSkillsRoundtripsAndTagsType() throws Exception {
        InvokedSkillsAttachment payload = new InvokedSkillsAttachment(List.of(
            new InvokedSkillsAttachment.InvokedSkillEntry("deploy", "/skills/deploy.md", "steps..."),
            new InvokedSkillsAttachment.InvokedSkillEntry("review", "/skills/review.md", "checklist...")
        ));
        JsonNode node = mapper.valueToTree(payload);
        assertEquals("invoked_skills", node.get("type").asText());
        assertEquals(2, node.get("skills").size());
        assertEquals("deploy", node.get("skills").get(0).get("name").asText());
        assertEquals(payload, roundtrip(payload));
    }

    @Test
    void invokedSkillsRoundtripsWithEmptyList() throws Exception {
        InvokedSkillsAttachment payload = new InvokedSkillsAttachment(List.of());
        assertEquals(payload, roundtrip(payload));
    }

    @Test
    void taskStatusRoundtripsAndTagsType() throws Exception {
        TaskStatusAttachment payload = new TaskStatusAttachment(
            "task-1", "local_agent", "running", "Refactor auth", "50% done", "/out/task-1.log");
        JsonNode node = mapper.valueToTree(payload);
        assertEquals("task_status", node.get("type").asText());
        assertEquals("task-1", node.get("taskId").asText());
        assertEquals(payload, roundtrip(payload));
    }

    @Test
    void taskStatusRoundtripsWithNullableFieldsAbsent() throws Exception {
        TaskStatusAttachment payload = new TaskStatusAttachment(
            "task-2", "local_agent", "failed", "Run tests", null, null);
        assertEquals(payload, roundtrip(payload));
    }

    @Test
    void attachmentMessageEnvelopeRoundtrips() throws Exception {
        AttachmentMessage msg = new AttachmentMessage(
            "uuid-1", new CompactFileReferenceAttachment("/tmp/big.txt"));
        JsonNode node = mapper.valueToTree(msg);
        assertEquals("attachment", node.get("type").asText(),
            "Message.type() must independently report 'attachment' for polymorphic dispatch");
        assertEquals("compact_file_reference", node.get("attachment").get("type").asText());

        AttachmentMessage back = (AttachmentMessage) mapper.readValue(mapper.writeValueAsString(msg), Message.class);
        assertEquals(msg.uuid(), back.uuid());
        assertEquals(msg.payload(), back.payload());
    }

    @Test
    void goalStatusRoundtripsWithReleasedFieldNames() throws Exception {
        GoalStatusAttachment p = GoalStatusAttachment.achieved(
            "all tests pass", "suite is green", 3, 1_500L, 2_000L);
        JsonNode node = mapper.valueToTree(p);
        assertEquals("goal_status", node.get("type").asText());
        assertTrue(node.get("met").asBoolean());
        assertEquals("all tests pass", node.get("condition").asText());
        assertEquals("suite is green", node.get("reason").asText());
        assertEquals(3, node.get("iterations").asInt());
        assertEquals(1_500L, node.get("durationMs").asLong());
        assertEquals(2_000L, node.get("tokens").asLong());
        assertEquals(p, roundtrip(p));
        assertTrue(AttachmentRenderer.render(p).isEmpty(),
            "goal status is transcript/UI state and must never enter the model request");
    }

    @Test
    void goalStatusSentinelAndFailureMarkersAreSerialized() throws Exception {
        JsonNode sentinel = mapper.valueToTree(
            GoalStatusAttachment.sentinel(false, "ship it"));
        assertTrue(sentinel.get("sentinel").asBoolean());

        JsonNode failed = mapper.valueToTree(
            GoalStatusAttachment.failed("ship it", "impossible", 2, 50L, 100L));
        assertTrue(failed.get("failed").asBoolean());
    }

    @Test
    void hookNonBlockingErrorRoundtripsAndStaysModelInvisible() throws Exception {
        HookNonBlockingErrorAttachment p = new HookNonBlockingErrorAttachment(
            "Stop", "Hook evaluator API error: Prompt is too long", "", 1,
            "hook-123", "Stop", "all tests pass", 25L);
        JsonNode node = mapper.valueToTree(p);
        assertEquals("hook_non_blocking_error", node.get("type").asText());
        assertEquals("hook-123", node.get("toolUseID").asText());
        assertEquals("Stop", node.get("hookEvent").asText());
        assertEquals(p, roundtrip(p));
        assertTrue(AttachmentRenderer.render(p).isEmpty());
    }

    @Test
    void hookSystemMessageRoundtripsAndStaysModelInvisible() throws Exception {
        HookSystemMessageAttachment payload = new HookSystemMessageAttachment(
            "configuration refreshed", "reload-hook", "hook-123", "SessionStart");
        JsonNode node = mapper.valueToTree(payload);
        assertEquals("hook_system_message", node.get("type").asText());
        assertEquals(payload, roundtrip(payload));
        assertTrue(AttachmentRenderer.render(payload).isEmpty());
    }

    @Test
    void hookSuccessRoundtripsWithReleasedDiagnosticFields() throws Exception {
        HookSuccessAttachment payload = new HookSuccessAttachment(
            "loaded context", "SessionStart", "hook-123", "SessionStart",
            "stdout", "stderr", 0, "echo ok", 17L);

        JsonNode node = mapper.valueToTree(payload);

        assertEquals("hook_success", node.get("type").asText());
        assertEquals("hook-123", node.get("toolUseID").asText());
        assertEquals("SessionStart", node.get("hookEvent").asText());
        assertEquals(17L, node.get("durationMs").asLong());
        assertEquals(payload, roundtrip(payload));
    }

    @Test
    void commandPermissionsRoundtripsAndStaysModelInvisible() throws Exception {
        CommandPermissionsAttachment p = new CommandPermissionsAttachment(List.of(), null);
        JsonNode node = mapper.valueToTree(p);
        assertEquals("command_permissions", node.get("type").asText());
        assertTrue(node.get("allowedTools").isArray());
        assertEquals(0, node.get("allowedTools").size());
        assertFalse(node.has("model"));
        assertEquals(p, roundtrip(p));
        assertTrue(AttachmentRenderer.render(p).isEmpty(),
            "command permissions are transcript/runtime state, never model input");
    }

    // ── Tier-1 payload round-trips (type tag + fields) ─────────────────────

    @Test
    void outputStyleRoundtrips() throws Exception {
        OutputStyleAttachment p = new OutputStyleAttachment("concise");
        JsonNode node = mapper.valueToTree(p);
        assertEquals("output_style", node.get("type").asText());
        assertEquals("concise", node.get("style").asText());
        assertEquals(p, roundtrip(p));
    }

    @Test
    void agentMentionRoundtrips() throws Exception {
        AgentMentionAttachment p = new AgentMentionAttachment("research");
        JsonNode node = mapper.valueToTree(p);
        assertEquals("agent_mention", node.get("type").asText());
        assertEquals("research", node.get("agentType").asText());
        assertEquals(p, roundtrip(p));
    }

    @Test
    void mcpResourceRoundtrips() throws Exception {
        McpResourceAttachment p = new McpResourceAttachment("srv", "file:///x", "srv", null, "body");
        JsonNode node = mapper.valueToTree(p);
        assertEquals("mcp_resource", node.get("type").asText());
        assertEquals("srv", node.get("server").asText());
        assertEquals(p, roundtrip(p));
    }

    @Test
    void todoReminderRoundtrips() throws Exception {
        TodoReminderAttachment p = new TodoReminderAttachment(
            List.of(new TodoItem("pending", "write code")), 1);
        JsonNode node = mapper.valueToTree(p);
        assertEquals("todo_reminder", node.get("type").asText());
        assertEquals(1, node.get("itemCount").asInt());
        assertEquals(p, roundtrip(p));
    }

    @Test
    void taskReminderRoundtripsReleasedPersistentTaskShape() throws Exception {
        TaskReminderAttachment p = new TaskReminderAttachment(List.of(
            new TaskReminderItem("3", "Implement feature", "Description", null, null,
                "pending", List.of(), List.of("1"), Optional.of(Map.of()))), 1);
        JsonNode node = mapper.valueToTree(p);
        assertEquals("task_reminder", node.get("type").asText());
        assertEquals("3", node.path("content").get(0).path("id").asText());
        assertFalse(node.path("content").get(0).has("activeForm"));
        assertTrue(node.path("content").get(0).path("metadata").isObject());
        assertTrue(node.path("content").get(0).path("metadata").isEmpty());
        assertEquals(p, roundtrip(p));

        TaskReminderAttachment absent = new TaskReminderAttachment(List.of(
            new TaskReminderItem("4", "No metadata", "Description", null, null,
                "pending", List.of(), List.of(), null)), 1);
        assertFalse(mapper.valueToTree(absent).path("content").get(0).has("metadata"));
    }

    @Test
    void planModeExitRoundtrips() throws Exception {
        PlanModeExitAttachment p = new PlanModeExitAttachment("/plan.md", true);
        JsonNode node = mapper.valueToTree(p);
        assertEquals("plan_mode_exit", node.get("type").asText());
        assertEquals("/plan.md", node.get("planFilePath").asText());
        assertTrue(node.get("planExists").asBoolean());
        assertEquals(p, roundtrip(p));
    }

    @Test
    void dynamicSkillRoundtrips() throws Exception {
        DynamicSkillAttachment p = new DynamicSkillAttachment("/skills", List.of("foo"), "skills");
        JsonNode node = mapper.valueToTree(p);
        assertEquals("dynamic_skill", node.get("type").asText());
        assertEquals(p, roundtrip(p));
    }

    @Test
    void skillListingRoundtrips() throws Exception {
        SkillListingAttachment p = new SkillListingAttachment("- foo: d", 1, true);
        JsonNode node = mapper.valueToTree(p);
        assertEquals("skill_listing", node.get("type").asText());
        assertTrue(node.get("isInitial").asBoolean());
        assertTrue(node.get("names").isArray());
        assertEquals(p, roundtrip(p));
    }

    @Test
    void hookAdditionalContextRoundtripsWithNativeFieldNames() throws Exception {
        HookAdditionalContextAttachment p = new HookAdditionalContextAttachment(
            List.of("WIRE_A", "WIRE_B"), "UserPromptSubmit", "hook-1", "UserPromptSubmit");
        JsonNode node = mapper.valueToTree(p);
        assertEquals("hook_additional_context", node.get("type").asText());
        assertEquals("WIRE_A", node.path("content").get(0).asText());
        assertEquals("UserPromptSubmit", node.path("hookName").asText());
        assertEquals("hook-1", node.path("toolUseID").asText());
        assertEquals("UserPromptSubmit", node.path("hookEvent").asText());
        assertEquals(p, roundtrip(p));
    }

    @Test
    void tokenUsageRoundtrips() throws Exception {
        TokenUsageAttachment p = new TokenUsageAttachment(100, 200, 100);
        JsonNode node = mapper.valueToTree(p);
        assertEquals("token_usage", node.get("type").asText());
        assertEquals(100, node.get("used").asLong());
        assertEquals(p, roundtrip(p));
    }

    @Test
    void budgetUsdRoundtrips() throws Exception {
        BudgetUsdAttachment p = new BudgetUsdAttachment(1.5, 5.0, 3.5);
        JsonNode node = mapper.valueToTree(p);
        assertEquals("budget_usd", node.get("type").asText());
        assertEquals(1.5, node.get("used").asDouble(), 0.001);
        assertEquals(p, roundtrip(p));
    }

    @Test
    void outputTokenUsageRoundtripsWithNullableBudget() throws Exception {
        OutputTokenUsageAttachment p = new OutputTokenUsageAttachment(50, 100L, 50);
        JsonNode node = mapper.valueToTree(p);
        assertEquals("output_token_usage", node.get("type").asText());
        assertEquals(100, node.get("budget").asLong());
        assertEquals(p, roundtrip(p));

        OutputTokenUsageAttachment nullBudget = new OutputTokenUsageAttachment(50, null, 50);
        assertEquals(nullBudget, roundtrip(nullBudget));
    }
}

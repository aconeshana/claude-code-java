package com.claudecode.services.hooks;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.tools.cron.CronCreateTool;
import com.claudecode.tools.cron.CronDeleteTool;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks down the wire field names {@link HookInput#toJson} emits for non-tool lifecycle events.
 */
class HookInputTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(HookInput input) {
        try {
            return MAPPER.readTree(input.toJson());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void sessionStart_usesSourceFieldNotTrigger() {
        JsonNode node = json(HookInput.forSessionStart("startup"));
        assertEquals("startup", node.path("source").asText());
        assertFalse(node.has("trigger"), "SessionStart's TS field is \"source\", not \"trigger\"");
    }

    @Test
    void preCompact_carriesCustomInstructionsWhenPresent() {
        JsonNode node = json(HookInput.forPreCompact("manual", "focus on the auth bug", 1000L, null, null));
        assertEquals("manual", node.path("trigger").asText());
        assertEquals("focus on the auth bug", node.path("custom_instructions").asText());
    }

    @Test
    void preCompact_carriesTheCurrentHeadlessPromptIdWhenAvailable() {
        JsonNode node = json(HookInput.forPreCompact(
            "manual", "focus", 1000L, "session-197", "/tmp/project", "prompt-197"));

        assertEquals("prompt-197", node.path("prompt_id").asText());
    }

    @Test
    void preCompact_serializesAbsentCustomInstructionsAsNullAndHasNoJavaOnlyTokenCount() {
        JsonNode absent = json(HookInput.forPreCompact("auto", null, 1000L, null, null));
        JsonNode blank = json(HookInput.forPreCompact("auto", "  ", 1000L, null, null));
        assertTrue(absent.has("custom_instructions"));
        assertTrue(absent.get("custom_instructions").isNull());
        assertTrue(blank.get("custom_instructions").isNull());
        assertFalse(absent.has("pre_compact_token_count"));
    }

    @Test
    void postCompact_carriesCompactSummaryWhenPresent() {
        JsonNode node = json(HookInput.forPostCompact("manual", "Discussed X, changed Y.", 500L, null, null));
        assertEquals("manual", node.path("trigger").asText());
        assertEquals("Discussed X, changed Y.", node.path("compact_summary").asText());
        assertFalse(node.has("post_compact_token_count"));
    }

    @Test
    void postCompact_omitsCompactSummaryWhenNullOrBlank() {
        assertFalse(json(HookInput.forPostCompact("auto", null, 500L, null, null)).has("compact_summary"));
    }

    @Test
    void sessionEnd_usesReasonField() {
        JsonNode node = json(HookInput.forSessionEnd("clear"));
        assertEquals("clear", node.path("reason").asText());
    }

    @Test
    void cwdChangedCarriesOldAndNewCwdAndUsesNewCwdAsBase() {
        JsonNode node = json(HookInput.forCwdChanged("/tmp/old", "/tmp/new", "session-1"));
        assertEquals("CwdChanged", node.path("hook_event_name").asText());
        assertEquals("/tmp/old", node.path("old_cwd").asText());
        assertEquals("/tmp/new", node.path("new_cwd").asText());
        assertEquals("/tmp/new", node.path("cwd").asText());
    }

    @Test
    void postToolUseFailureCarriesReleasedErrorPayload() {
        JsonNode toolInput = MAPPER.createObjectNode().put("file_path", "/tmp/a");
        JsonNode node = json(HookInput.forPostToolUseFailure(
            "Edit", toolInput, "tu-1", "write failed", true,
            "session-197", "/tmp/project", "default"));

        assertEquals("PostToolUseFailure", node.path("hook_event_name").asText());
        assertEquals("Edit", node.path("tool_name").asText());
        assertEquals("/tmp/a", node.path("tool_input").path("file_path").asText());
        assertEquals("tu-1", node.path("tool_use_id").asText());
        assertEquals("write failed", node.path("error").asText());
        assertTrue(node.path("is_interrupt").asBoolean());
        assertFalse(node.has("tool_response"));
    }

    @Test
    void missingLifecycleInputsUseReleasedFieldNames() {
        JsonNode denied = json(HookInput.forPermissionDenied(
            "Bash", MAPPER.createObjectNode(), "tu-2", "rule denied", "s", "/tmp", "default"));
        assertEquals("rule denied", denied.path("reason").asText());

        JsonNode notification = json(HookInput.forNotification(
            "done", "Build", "task_complete", "s", "/tmp"));
        assertEquals("task_complete", notification.path("notification_type").asText());
        assertEquals("Build", notification.path("title").asText());

        JsonNode setup = json(HookInput.forSetup("maintenance", "s", "/tmp"));
        assertEquals("maintenance", setup.path("trigger").asText());

        JsonNode file = json(HookInput.forFileChanged(
            "/tmp/a", "unlink", "s", "/tmp"));
        assertEquals("unlink", file.path("event").asText());
        assertEquals("/tmp/a", file.path("file_path").asText());

        JsonNode elicitation = json(HookInput.forElicitation(
            "server", "choose", "form", null, "el-1",
            MAPPER.createObjectNode().put("type", "object"), "s", "/tmp", "default"));
        assertEquals("server", elicitation.path("mcp_server_name").asText());
        assertEquals("el-1", elicitation.path("elicitation_id").asText());
    }

    @Test
    void taskLifecycleCarriesReleasedTeamContextAndPreservesEmptyDescription() {
        JsonNode created = json(HookInput.forTaskCreated(
            "1", "Implement", "", "reviewer", "alpha",
            "session-197", "/tmp/project", "default"));
        JsonNode completed = json(HookInput.forTaskCompleted(
            "1", "Implement", "done", "reviewer", "alpha",
            "session-197", "/tmp/project", "default"));

        assertTrue(created.has("task_description"));
        assertEquals("", created.path("task_description").asText());
        assertEquals("reviewer", created.path("teammate_name").asText());
        assertEquals("alpha", created.path("team_name").asText());
        assertEquals("reviewer", completed.path("teammate_name").asText());
        assertEquals("alpha", completed.path("team_name").asText());
    }



    @Test
    void hookEventName_isPascalCaseTsSpelling() {
        assertEquals("Stop", json(HookInput.forStop(false)).path("hook_event_name").asText());
        assertEquals("StopFailure",
            json(HookInput.forStopFailure("unknown", null, null, null)).path("hook_event_name").asText());
        assertEquals("UserPromptSubmit",
            json(HookInput.forUserPromptSubmit("hi", null, null, null)).path("hook_event_name").asText());
        assertEquals("SessionStart",
            json(HookInput.forSessionStart("startup")).path("hook_event_name").asText());
    }

    @Test
    void subagentStartCarriesReleasedAgentIdentityFields() {
        JsonNode node = json(HookInput.forSubagentStart(
            "a0123456789abcdef", "boot", "session-197", "/tmp/project"));

        assertEquals("SubagentStart", node.path("hook_event_name").asText());
        assertEquals("a0123456789abcdef", node.path("agent_id").asText());
        assertEquals("boot", node.path("agent_type").asText());
        assertEquals("session-197", node.path("session_id").asText());
        assertEquals("/tmp/project", node.path("cwd").asText());
    }

    @Test
    void subagentStopMatchesTheReleased197Payload() {
        HookInput input = HookInput.forSubagentStop(
            "a28ef7de44a1d0212",
            "/tmp/config/projects/-tmp-project/session-197/subagents/agent-a28ef7de44a1d0212.jsonl",
            "boot", false, "OK", "session-197", "/tmp/project",
            "bypassPermissions", "prompt-197", "high");
        JsonNode node = json(input);

        assertEquals("SubagentStop", node.path("hook_event_name").asText());
        assertEquals("a28ef7de44a1d0212", node.path("agent_id").asText());
        assertEquals("boot", node.path("agent_type").asText());
        assertEquals("/tmp/config/projects/-tmp-project/session-197/subagents/agent-a28ef7de44a1d0212.jsonl",
            node.path("agent_transcript_path").asText());
        assertEquals("OK", node.path("last_assistant_message").asText());
        assertFalse(node.path("stop_hook_active").asBoolean());
        assertEquals("bypassPermissions", node.path("permission_mode").asText());
        assertEquals("prompt-197", node.path("prompt_id").asText());
        assertEquals("high", node.path("effort").path("level").asText());
        assertTrue(node.path("background_tasks").isArray());
        assertTrue(node.path("session_crons").isArray());
    }



    @Test
    void stop_carriesStopHookActiveAsRealBoolean() {
        JsonNode node = json(HookInput.forStop(true, null, "sess-1", "/tmp", null));
        assertTrue(node.path("stop_hook_active").isBoolean(),
            "stop_hook_active must serialize as a JSON boolean, not a string");
        assertTrue(node.path("stop_hook_active").asBoolean());
        assertFalse(json(HookInput.forStop(false)).path("stop_hook_active").asBoolean());
    }

    @Test
    void stop_carriesLastAssistantMessageWhenPresentOmitsWhenAbsent() {
        JsonNode with = json(HookInput.forStop(false, "final answer", null, null, null));
        assertEquals("final answer", with.path("last_assistant_message").asText());
        assertFalse(json(HookInput.forStop(false)).has("last_assistant_message"),
            "TS leaves last_assistant_message undefined when unavailable");
    }

    @Test
    void stop_hasNoStopReasonField() {

        assertFalse(json(HookInput.forStop(false, "text", "s", "/tmp", "default")).has("stop_reason"));
    }

    @Test
    void stop_includesPermissionMode() {

        JsonNode node = json(HookInput.forStop(false, null, "s", "/tmp", "acceptEdits"));
        assertEquals("acceptEdits", node.path("permission_mode").asText());
    }

    @Test
    void stopEvaluatorPayloadMatchesReleased197FieldsAndOrder() {
        HookInput input = HookInput.forStop(false, "OK", "session-197", "/tmp/project",
            "bypassPermissions", "prompt-197", "high");
        String raw = input.toJson();
        JsonNode node = json(input);

        assertEquals("prompt-197", node.path("prompt_id").asText());
        assertEquals("high", node.path("effort").path("level").asText());
        assertTrue(node.path("background_tasks").isArray());
        assertEquals(0, node.path("background_tasks").size());
        assertTrue(node.path("session_crons").isArray());
        assertEquals(0, node.path("session_crons").size());

        assertFieldOrder(raw, "session_id", "transcript_path", "cwd", "prompt_id",
            "permission_mode", "effort", "hook_event_name", "stop_hook_active",
            "last_assistant_message", "background_tasks", "session_crons");
    }

    @Test
    void stopIncludesLiveSessionCronAndLoopWakeupSnapshots() {
        var context = ToolExecutionContext.of(new AbortController(), "hook-cron-test");
        var createInput = JsonUtils.getMapper().createObjectNode();
        createInput.put("cron", "*/5 * * * *");
        createInput.put("prompt", "/loop check deploy");
        createInput.put("recurring", false);
        String created = new CronCreateTool().call(createInput, context);
        String id = created.split(" ", 5)[3];
        try {
            JsonNode cron = json(HookInput.forStop(false)).path("session_crons").get(0);
            assertEquals(id, cron.path("id").asText());
            assertEquals("*/5 * * * *", cron.path("schedule").asText());
            assertFalse(cron.path("recurring").asBoolean());
            assertEquals("/loop check deploy", cron.path("prompt").asText());
        } finally {
            var deleteInput = JsonUtils.getMapper().createObjectNode().put("id", id);
            new CronDeleteTool().call(deleteInput, context);
        }
    }

    private static void assertFieldOrder(String json, String... fields) {
        int previous = -1;
        for (String field : fields) {
            int current = json.indexOf('"' + field + '"');
            assertTrue(current > previous, field + " must follow the previous field in: " + json);
            previous = current;
        }
    }



    @Test
    void stopFailure_carriesErrorAndDefaultsToUnknown() {
        assertEquals("stream_error",
            json(HookInput.forStopFailure("stream_error", null, null, null)).path("error").asText());
        assertEquals("unknown",
            json(HookInput.forStopFailure(null, null, null, null)).path("error").asText());
    }



    @Test
    void userPromptSubmit_carriesPromptText() {
        JsonNode node = json(HookInput.forUserPromptSubmit("fix the login bug", "s", "/tmp", "default"));
        assertEquals("fix the login bug", node.path("prompt").asText());
        assertEquals("default", node.path("permission_mode").asText());
    }

    @Test
    void userPromptSubmit_carriesTheCurrentPromptIdWithoutEmbeddingCallbackToolUseId() {
        JsonNode node = json(HookInput.forUserPromptSubmit(
            "fix the login bug", "s", "/tmp", "default", "prompt-197"));

        assertEquals("prompt-197", node.path("prompt_id").asText());
        assertFalse(node.has("tool_use_id"),
            "UserPromptSubmit callback tool_use_id belongs to the control envelope, not hook input");
    }

    @Test
    void userPromptExpansionUsesReleased197Fields() {
        JsonNode node = json(HookInput.forUserPromptExpansion(
            "slash_command", "plugin:review", "src/Main.java", "plugin",
            "/plugin:review src/Main.java", "s", "/tmp", "default"));

        assertEquals("UserPromptExpansion", node.path("hook_event_name").asText());
        assertEquals("slash_command", node.path("expansion_type").asText());
        assertEquals("plugin:review", node.path("command_name").asText());
        assertEquals("src/Main.java", node.path("command_args").asText());
        assertEquals("plugin", node.path("command_source").asText());
        assertEquals("/plugin:review src/Main.java", node.path("prompt").asText());
    }

    @Test
    void messageDisplayUsesReleased197DeltaFields() {
        JsonNode node = json(HookInput.forMessageDisplay(
            "turn-1", "message-1", 3, true, "done\n", "s", "/tmp"));

        assertEquals("MessageDisplay", node.path("hook_event_name").asText());
        assertEquals("turn-1", node.path("turn_id").asText());
        assertEquals("message-1", node.path("message_id").asText());
        assertEquals(3, node.path("index").asInt());
        assertTrue(node.path("final").asBoolean());
        assertEquals("done\n", node.path("delta").asText());
    }



    @Test
    void baseFields_deriveTranscriptPathFromSessionIdAndCwd() {
        JsonNode node = json(HookInput.forUserPromptSubmit("hi", "abc-123", "/tmp/proj", null));
        String path = node.path("transcript_path").asText();
        assertTrue(Strings.CS.endsWith(path, "/abc-123.jsonl"),
            "transcript_path must be <projectDir>/<sessionId>.jsonl, got: " + path);
        assertTrue(Strings.CS.contains(path, "projects"),
            "transcript_path must live under the projects dir, got: " + path);
    }

    @Test
    void baseFields_omitTranscriptPathWithoutSession() {
        assertFalse(json(HookInput.forSessionStart("startup")).has("transcript_path"));
    }



    @Test
    void postToolUse_usesToolResponseFieldName() {
        JsonNode output = MAPPER.createObjectNode().put("stdout", "ok");
        JsonNode node = json(HookInput.forPostToolUse("Bash",
            MAPPER.createObjectNode().put("command", "ls"), output, "tu-9"));
        assertEquals("ok", node.path("tool_response").path("stdout").asText(),
            "TS field is tool_response, not tool_output");
        assertFalse(node.has("tool_output"));
    }
}

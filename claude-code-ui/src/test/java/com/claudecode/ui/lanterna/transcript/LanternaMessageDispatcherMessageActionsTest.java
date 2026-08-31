package com.claudecode.ui.lanterna.transcript;

import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.engine.PermissionAskContext;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageFactory;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.ProgressMessage;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.message.Usage;
import com.claudecode.core.constants.Figures;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.ui.lanterna.dialog.PermissionPreviewPreparer;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

class LanternaMessageDispatcherMessageActionsTest {

    @Test
    void rejectedEditUsesThePermissionSnapshotAfterTheFileChanges(@TempDir Path temp)
            throws Exception {
        Path file = temp.resolve("Example.java");
        Files.writeString(file, "class Example { int oldValue; }\n");
        var input = JsonUtils.getMapper().createObjectNode()
            .put("file_path", file.toString())
            .put("old_string", "int oldValue;")
            .put("new_string", "int newValue;");
        String id = "toolu_snapshot_rejection";
        var prepared = PermissionPreviewPreparer.standard().prepare(
            PermissionAskContext.simple("Edit", input, id));
        ToolPresentationSnapshotStore store = new ToolPresentationSnapshotStore();
        store.publishFilePreview(store.ticket(id), prepared.rejectedFileChangePreview());
        Files.writeString(file, "class Example { int diskChanged; }\n");

        MessagePanel panel = new MessagePanel();
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher(store);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_start", "Edit|" + id), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_done", "Edit|" + id + "|" + input), panel);
        dispatcher.dispatch(new SDKMessage.User(new UserMessage(
            "result-" + id,
            MessageContent.ofToolResult(id,
                List.of(new TextBlock(MessageConstants.REJECT_MESSAGE)), true))), panel);

        List<String> rows = texts(panel);
        assertTrue(rows.stream().anyMatch(text -> Strings.CS.contains(text, "int newValue;")),
            rows.toString());
        assertFalse(rows.stream().anyMatch(text -> Strings.CS.contains(text, "diskChanged")),
            rows.toString());
        assertNull(store.consumeFilePreview(id));
    }

    @Test
    void replayedRejectedEditUsesOnlyTheRecordedInput() {
        String id = "toolu_replayed_rejection";
        String input = """
            {"file_path":"/definitely/missing/replay.txt",\
             "old_string":"recorded old",\
             "new_string":"recorded new"}
            """;
        MessagePanel panel = new MessagePanel();
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_start", "Edit|" + id), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_done", "Edit|" + id + "|" + input), panel);
        dispatcher.dispatch(new SDKMessage.User(new UserMessage(
            "result-" + id,
            MessageContent.ofToolResult(id,
                List.of(new TextBlock(MessageConstants.REJECT_MESSAGE)), true))), panel);

        List<String> rows = texts(panel);
        assertTrue(rows.stream().anyMatch(text -> Strings.CS.contains(text, "recorded old")),
            rows.toString());
        assertTrue(rows.stream().anyMatch(text -> Strings.CS.contains(text, "recorded new")),
            rows.toString());
    }

    private static final List<TaskToolCase> TASK_TOOL_CASES = List.of(
        new TaskToolCase("TaskCreate", "{\"subject\":\"Fix coverage\",\"description\":\"Close the gap\"}",
            "Task #1 created successfully: Fix coverage"),
        new TaskToolCase("TaskGet", "{\"taskId\":\"1\"}",
            "Task #1: Fix coverage\nStatus: pending\nDescription: Close the gap"),
        new TaskToolCase("TaskList", "{}", "#1 [pending] Fix coverage"),
        new TaskToolCase("TaskUpdate", "{\"taskId\":\"1\",\"status\":\"completed\"}",
            "Updated task #1 status")
    );

    @Test
    void taskToolsHideTheirToolUseAndSuccessfulResult() {
        for (TaskToolCase taskTool : TASK_TOOL_CASES) {
            MessagePanel panel = new MessagePanel();
            LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
            String id = "toolu_" + taskTool.name();

            dispatcher.dispatch(new SDKMessage.StreamEvent(
                "tool_streaming_start", taskTool.name() + "|" + id), panel);
            dispatcher.dispatch(new SDKMessage.StreamEvent(
                "tool_streaming_done", taskTool.name() + "|" + id + "|" + taskTool.inputJson()), panel);
            dispatcher.dispatch(new SDKMessage.StreamEvent(
                "tool_call_start", taskTool.name() + "|" + id + "|" + taskTool.inputJson()), panel);
            dispatcher.dispatch(new SDKMessage.StreamEvent(
                "tool_result_success", taskTool.name() + "|" + taskTool.resultText()), panel);

            assertTrue(panel.displayRowsForTest(80).isEmpty(),
                taskTool.name() + " renderToolUseMessage() is null and no result renderer is defined in the released UI");
        }
    }

    @Test
    void taskToolsHideTheFinalUserToolResultProjection() {
        for (TaskToolCase taskTool : TASK_TOOL_CASES) {
            MessagePanel panel = new MessagePanel();
            LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
            String id = "toolu_" + taskTool.name();

            dispatcher.dispatch(new SDKMessage.StreamEvent(
                "tool_streaming_start", taskTool.name() + "|" + id), panel);
            dispatcher.dispatch(new SDKMessage.StreamEvent(
                "tool_streaming_done", taskTool.name() + "|" + id + "|" + taskTool.inputJson()), panel);
            dispatcher.dispatch(new SDKMessage.User(new UserMessage(
                "result-" + taskTool.name(),
                MessageContent.ofToolResult(id,
                    List.of(new TextBlock(taskTool.resultText())), false))), panel);

            assertTrue(panel.displayRowsForTest(80).isEmpty(),
                "the persisted/replayed " + taskTool.name() + " result must remain hidden too");
        }
    }

    @Test
    void enterPlanModeUsesReleasedResultCard() {
        String toolName = "EnterPlanMode";
        MessagePanel panel = new MessagePanel();
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        String id = "toolu_" + toolName;

        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_start", toolName + "|" + id), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_done", toolName + "|" + id + "|{}"), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_call_start", toolName + "|" + id + "|{}"), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "permission_waiting", toolName), panel);

        assertTrue(panel.displayRowsForTest(80).isEmpty(),
            toolName + " renderToolUseMessage() is null in the released UI");

        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_result_success", toolName + "|completed"), panel);
        dispatcher.dispatch(new SDKMessage.User(new UserMessage(
            "result-" + id,
            MessageContent.ofToolResult(id, List.of(new TextBlock("completed")), false),
            false, false, Map.of("message", "Entered plan mode."),
            MessageOrigin.USER, null, Instant.now(), null, null)), panel);

        var rows = panel.displayRowsForTest(80);
        assertEquals(List.of(
            LanternaMessageDispatcher.BLACK_CIRCLE + "Entered plan mode",
            "  Claude is now exploring and designing an implementation approach."),
            rows.stream().map(MessagePanel.StyledLine::text).toList());
        assertEquals(LanternaTheme.modePlan(), rows.getFirst().segments().getFirst().color());
    }

    @Test
    void enterPlanModeRejectionUsesReleasedResultCard() {
        ToolHarness harness = startTool("EnterPlanMode", "toolu_enter_rejected", "{}");
        harness.dispatcher().dispatch(new SDKMessage.User(new UserMessage(
            "result-toolu_enter_rejected",
            MessageContent.ofToolResult("toolu_enter_rejected",
                List.of(new TextBlock(MessageConstants.REJECT_MESSAGE)), true))), harness.panel());

        assertEquals(List.of(LanternaMessageDispatcher.BLACK_CIRCLE
            + "User declined to enter plan mode"), texts(harness.panel()));
    }

    @Test
    void exitPlanModeUsesReleasedApprovedPlanCard() {
        ToolHarness harness = startTool("ExitPlanMode", "toolu_exit_approved", "{}");
        harness.dispatcher().dispatch(new SDKMessage.User(new UserMessage(
            "result-toolu_exit_approved",
            MessageContent.ofToolResult("toolu_exit_approved",
                List.of(new TextBlock("model result")), false),
            false, false, Map.of(
                "plan", "# Plan\n\n- update parser",
                "isAgent", false,
                "filePath", "/tmp/plans/steady-otter.md"),
            MessageOrigin.USER, null, Instant.now(), null, null)), harness.panel());

        assertEquals(List.of(
            LanternaMessageDispatcher.BLACK_CIRCLE + "User approved Claude's plan",
            Figures.RESULT_INDENT + "Plan saved to: /tmp/plans/steady-otter.md · /plan to edit",
            "  Plan",
            "",
            "  - update parser"), texts(harness.panel()));
    }

    @Test
    void exitPlanModeUsesReleasedEmptyAndLeaderApprovalCards() {
        ToolHarness empty = startTool("ExitPlanMode", "toolu_exit_empty", "{}");
        var emptyOutput = JsonUtils.getMapper().createObjectNode();
        emptyOutput.putNull("plan");
        emptyOutput.put("isAgent", false);
        emptyOutput.put("filePath", "/tmp/plans/empty.md");
        empty.dispatcher().dispatch(new SDKMessage.User(new UserMessage(
            "result-toolu_exit_empty",
            MessageContent.ofToolResult("toolu_exit_empty",
                List.of(new TextBlock("model result")), false),
            false, false, emptyOutput,
            MessageOrigin.USER, null, Instant.now(), null, null)), empty.panel());
        assertEquals(List.of(LanternaMessageDispatcher.BLACK_CIRCLE + "Exited plan mode"),
            texts(empty.panel()));

        ToolHarness leader = startTool("ExitPlanMode", "toolu_exit_leader", "{}");
        leader.dispatcher().dispatch(new SDKMessage.User(new UserMessage(
            "result-toolu_exit_leader",
            MessageContent.ofToolResult("toolu_exit_leader",
                List.of(new TextBlock("model result")), false),
            false, false, Map.of(
                "plan", "# Team plan",
                "isAgent", true,
                "filePath", "/tmp/plans/team.md",
                "awaitingLeaderApproval", true),
            MessageOrigin.USER, null, Instant.now(), null, null)), leader.panel());
        assertEquals(List.of(
            LanternaMessageDispatcher.BLACK_CIRCLE + "Plan submitted for team lead approval",
            Figures.RESULT_INDENT + "Plan file: /tmp/plans/team.md",
            Figures.RESULT_INDENT + "Waiting for team lead to review and approve..."),
            texts(leader.panel()));
    }

    @Test
    void currentPlanLocalCommandUsesReleasedStructuredProjection() {
        MessagePanel panel = new MessagePanel();
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        String output = """
            <local-command-stdout>Current Plan
            /tmp/plans/steady-otter.md

            1. Inspect the current wiring
            2. Connect the shared controller

            "/plan open" to edit this plan in vim</local-command-stdout>""";

        dispatcher.dispatch(new SDKMessage.User(MessageFactory.createUserMessage(output)), panel);

        assertEquals(List.of(
            Figures.RESULT_PREFIX + "Current Plan",
            Figures.RESULT_INDENT + "/tmp/plans/steady-otter.md",
            "",
            Figures.RESULT_INDENT + "1. Inspect the current wiring",
            Figures.RESULT_INDENT + "2. Connect the shared controller",
            "",
            Figures.RESULT_INDENT + "\"/plan open\" to edit this plan in vim"), texts(panel));
        assertEquals(LanternaTheme.welcomeDim(),
            panel.displayRowsForTest(160).get(1).segments().getLast().color());
    }

    @Test
    void requestedToolHeadersMatchReleasedVisualContracts() {
        assertToolHeader("Grep", "{\"pattern\":\"TODO\",\"path\":\"src\"}",
            "Search(pattern: \"TODO\", path: \"src\")");
        assertToolHeader("Glob", "{\"pattern\":\"**/*.java\"}",
            "Search(pattern: \"**/*.java\")");
        assertToolHeader("Edit",
            "{\"file_path\":\"new.txt\",\"old_string\":\"\",\"new_string\":\"x\"}",
            "Create(new.txt)");
        assertToolHeader("TaskOutput", "{\"task_id\":\"17\",\"block\":false}",
            "Task Output(non-blocking)");
        assertToolHeader("Skill", "{\"skill\":\"security-review\"}",
            "Skill(security-review)");
        assertToolHeader("Workflow", "{\"name\":\"audit\"}",
            "Workflow(dynamic workflow: audit)");
    }

    @Test
    void workflowKeepsItsHeaderButHidesSuccessfulResultAndScheduleWakeupHidesAll() {
        MessagePanel workflowPanel = completedLiveTool("Workflow", "toolu_workflow",
            "{\"name\":\"audit\"}", "Workflow launched in background");
        List<String> workflowRows = texts(workflowPanel);
        assertTrue(workflowRows.stream().anyMatch(text -> Strings.CS.contains(text, "Workflow(dynamic workflow: audit)")));
        assertFalse(workflowRows.stream().anyMatch(text -> Strings.CS.contains(text, "launched in background")));

        MessagePanel wakeupPanel = completedLiveTool("ScheduleWakeup", "toolu_wakeup",
            "{\"delaySeconds\":5,\"reason\":\"poll\",\"prompt\":\"continue\"}",
            "Next wakeup scheduled");
        assertTrue(wakeupPanel.displayRowsForTest(100).isEmpty());
    }

    @Test
    void searchSkillAndTaskOutputUseSpecializedSuccessfulResults() {
        ToolHarness search = startTool("Grep", "toolu_search",
            "{\"pattern\":\"needle\",\"output_mode\":\"files_with_matches\"}");
        structuredResult("Grep", "toolu_search", "Found 2 files\na.txt\nb.txt", null, search);
        assertTrue(texts(search.panel()).stream().anyMatch(text -> Strings.CS.contains(text, "Found 2 files")));
        assertFalse(texts(search.panel()).stream().anyMatch(text -> Strings.CS.contains(text, "a.txt")));

        ToolHarness skill = startTool("Skill", "toolu_skill",
            "{\"skill\":\"review\"}");
        structuredResult("Skill", "toolu_skill", "Launching skill: review",
            Map.of("success", true, "commandName", "review",
                "allowedTools", List.of("Read", "Grep"), "model", "sonnet"), skill);
        assertTrue(texts(skill.panel()).stream().anyMatch(text ->
            Strings.CS.contains(text, "Successfully loaded skill · 2 tools allowed · sonnet")));

        ToolHarness task = startTool("TaskOutput", "toolu_task_output",
            "{\"task_id\":\"9\"}");
        structuredResult("TaskOutput", "toolu_task_output", "<retrieval_status>success</retrieval_status>",
            Map.of("retrieval_status", "success", "task", Map.of(
                "task_id", "9", "task_type", "local_agent", "status", "completed",
                "description", "Inspect code", "output", "answer", "result", "answer",
                "prompt", "inspect")), task);
        assertTrue(texts(task.panel()).stream().anyMatch(text -> Strings.CS.contains(text, "Read output (ctrl+o to expand)")));
        assertFalse(texts(task.panel()).stream().anyMatch(text -> Strings.CS.contains(text, "retrieval_status")));
    }

    @Test
    void firstBatchHeadersAndHiddenRowsMatchReleasedUi() {
        assertToolHeader("TaskStop", "{\"task_id\":\"7\"}", "TaskStop");
        assertToolHeader("TeamCreate", "{\"team_name\":\"platform\"}",
            "TeamCreate(create team: platform)");
        assertToolHeader("TeamDelete", "{}", "TeamDelete(cleanup team: current)");
        assertToolHeader("EnterWorktree", "{\"name\":\"feature\"}",
            "Creating worktree(Creating worktree…)");
        assertToolHeader("ExitWorktree", "{\"action\":\"keep\"}",
            "Exiting worktree(Exiting worktree…)");

        ToolHarness normalMessage = startTool("SendMessage", "toolu_message",
            "{\"to\":\"worker\",\"message\":\"hello\",\"summary\":\"greeting\"}");
        assertTrue(texts(normalMessage.panel()).isEmpty());

        assertToolHeader("SendMessage",
            "{\"to\":\"lead\",\"message\":{\"type\":\"plan_approval_response\",\"approve\":true}}",
            "SendMessage(approve plan from: lead)");
    }

    @Test
    void persistedPlanRejectionRendersTheRejectedPlanProjection() {
        String id = "toolu_exit_plan_rejected";
        String released197Prefix = "The agent proposed a plan that was rejected by the user. "
            + "The user chose to stay in plan mode rather than proceed with implementation.\n"
            + "Rejected plan:\n";
        assertEquals(released197Prefix, MessageConstants.PLAN_REJECTION_PREFIX);
        String content = released197Prefix + "# Plan\n\n- update the parser";
        MessagePanel panel = new MessagePanel();
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();

        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_start", "ExitPlanMode|" + id), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_done", "ExitPlanMode|" + id + "|{}"), panel);
        dispatcher.dispatch(new SDKMessage.User(new UserMessage(
            "result-" + id,
            MessageContent.ofToolResult(id, List.of(new TextBlock(content)), true))), panel);

        List<String> rows = texts(panel);
        assertTrue(rows.stream().anyMatch(text -> Strings.CS.contains(
            text, "User rejected Claude's plan:")));
        assertTrue(rows.stream().anyMatch(text -> Strings.CS.contains(
            text, "update the parser")));
        assertFalse(rows.stream().anyMatch(text -> Strings.CS.contains(
            text, MessageConstants.PLAN_REJECTION_PREFIX)));
    }

    @Test
    void liveExitPlanModeRejectionUsesTheRememberedPlanWithoutChangingToolResultText() {
        String id = "toolu_exit_plan_live_rejected";
        MessagePanel panel = new MessagePanel();
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        dispatcher.setPersistedPlanSupplier(() -> "# Stale persisted plan");
        dispatcher.rememberPlanForRejection(id, "# Live plan\n\n- keep planning");

        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_start", "ExitPlanMode|" + id), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_done", "ExitPlanMode|" + id + "|{}"), panel);
        dispatcher.dispatch(new SDKMessage.User(new UserMessage(
            "result-" + id,
            MessageContent.ofToolResult(id,
                List.of(new TextBlock(MessageConstants.REJECT_MESSAGE)), true))), panel);

        List<String> rows = texts(panel);
        assertTrue(rows.stream().anyMatch(text -> Strings.CS.contains(text, "User rejected Claude's plan:")));
        assertTrue(rows.stream().anyMatch(text -> Strings.CS.contains(text, "keep planning")));
        assertFalse(rows.stream().anyMatch(text -> Strings.CS.contains(text, "Stale persisted plan")));
    }

    @Test
    void replayedGenericExitPlanModeRejectionReloadsThePersistedSessionPlan() {
        String id = "toolu_exit_plan_replayed_rejected";
        MessagePanel panel = new MessagePanel();
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        dispatcher.setPersistedPlanSupplier(() -> "# Persisted plan\n\n- keep the context");

        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_start", "ExitPlanMode|" + id), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_done", "ExitPlanMode|" + id + "|{}"), panel);
        dispatcher.dispatch(new SDKMessage.User(new UserMessage(
            "result-" + id,
            MessageContent.ofToolResult(id,
                List.of(new TextBlock(MessageConstants.REJECT_MESSAGE)), true))), panel);

        List<String> rows = texts(panel);
        assertTrue(rows.stream().anyMatch(text -> Strings.CS.contains(
            text, "User rejected Claude's plan:")));
        assertTrue(rows.stream().anyMatch(text -> Strings.CS.contains(
            text, "keep the context")));
    }

    @Test
    void replayedExitPlanModeRejectionWithoutAPlanUsesTheReleasedFallback() {
        String id = "toolu_exit_plan_missing_plan";
        MessagePanel panel = new MessagePanel();
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();

        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_start", "ExitPlanMode|" + id), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_done", "ExitPlanMode|" + id + "|{}"), panel);
        dispatcher.dispatch(new SDKMessage.User(new UserMessage(
            "result-" + id,
            MessageContent.ofToolResult(id,
                List.of(new TextBlock(MessageConstants.REJECT_MESSAGE)), true))), panel);

        List<String> rows = texts(panel);
        assertTrue(rows.stream().anyMatch(text -> Strings.CS.contains(
            text, "User rejected Claude's plan:")));
        assertTrue(rows.stream().anyMatch(text -> Strings.CS.contains(
            text, "No plan found")));
    }

    @Test
    void ordinaryToolRejectionDoesNotUseThePlanProjection() {
        String id = "toolu_bash_rejected";
        MessagePanel panel = new MessagePanel();
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();

        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_start", "Bash|" + id), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_done", "Bash|" + id + "|{\"command\":\"git status\"}"), panel);
        dispatcher.dispatch(new SDKMessage.User(new UserMessage(
            "result-" + id,
            MessageContent.ofToolResult(id, List.of(new TextBlock(
                MessageConstants.REJECT_MESSAGE_WITH_REASON_PREFIX + "do not run it")), true))), panel);

        assertFalse(texts(panel).stream().anyMatch(text -> Strings.CS.contains(
            text, "User rejected Claude's plan:")));
    }

    @Test
    void firstBatchStructuredResultsMatchReleasedUi() {
        ToolHarness ask = startTool("AskUserQuestion", "toolu_ask", "{\"questions\":[]}");
        structuredResult("AskUserQuestion", "toolu_ask", "User has answered", Map.of(
            "answers", Map.of("Deploy now?", "Yes", "Region?", "us-west")), ask);
        assertTrue(texts(ask.panel()).stream().anyMatch(text ->
            Strings.CS.contains(text, "User answered Claude's questions:")));
        assertTrue(texts(ask.panel()).stream().anyMatch(text -> Strings.CS.contains(text, "Deploy now? → Yes")));

        ToolHarness stop = startTool("TaskStop", "toolu_stop", "{\"task_id\":\"7\"}");
        structuredResult("TaskStop", "toolu_stop", "json", Map.of(
            "task_id", "7", "task_type", "local_bash", "command", "mvn test"), stop);
        assertTrue(texts(stop.panel()).stream().anyMatch(text -> Strings.CS.contains(text, "mvn test · stopped")));

        ToolHarness teamDelete = startTool("TeamDelete", "toolu_team_delete", "{}");
        structuredResult("TeamDelete", "toolu_team_delete", "cleanup", Map.of(
            "success", true, "team_name", "platform", "message", "cleaned"), teamDelete);
        assertTrue(texts(teamDelete.panel()).stream().anyMatch(text -> Strings.CS.contains(text, "TeamDelete")));
        assertFalse(texts(teamDelete.panel()).stream().anyMatch(text -> Strings.CS.contains(text, "cleaned")));

        ToolHarness routed = startTool("SendMessage", "toolu_routed",
            "{\"to\":\"worker\",\"message\":\"hello\",\"summary\":\"greeting\"}");
        structuredResult("SendMessage", "toolu_routed", "sent", Map.of(
            "success", true, "message", "sent", "routing", Map.of("kind", "direct")), routed);
        assertTrue(texts(routed.panel()).isEmpty());

        ToolHarness enter = startTool("EnterWorktree", "toolu_enter", "{\"name\":\"feature\"}");
        structuredResult("EnterWorktree", "toolu_enter", "created", Map.of(
            "worktreePath", "/tmp/project-feature", "worktreeBranch", "worktree-feature",
            "message", "created"), enter);
        assertTrue(texts(enter.panel()).stream().anyMatch(text ->
            Strings.CS.contains(text, "Switched to worktree on branch worktree-feature")));

        ToolHarness exit = startTool("ExitWorktree", "toolu_exit", "{\"action\":\"keep\"}");
        structuredResult("ExitWorktree", "toolu_exit", "exited", Map.of(
            "action", "keep", "originalCwd", "/tmp/project", "worktreePath", "/tmp/worktree",
            "worktreeBranch", "worktree-feature", "message", "exited"), exit);
        assertTrue(texts(exit.panel()).stream().anyMatch(text ->
            Strings.CS.contains(text, "Kept worktree (branch worktree-feature)")));
        assertTrue(texts(exit.panel()).stream().anyMatch(text -> Strings.CS.contains(text, "Returned to /tmp/project")));
    }

    @Test
    void secondBatchHeadersAndStructuredResultsMatchReleasedUi() {
        assertToolHeader("LSP",
            "{\"operation\":\"hover\",\"filePath\":\"/tmp/Demo.java\",\"line\":4,\"character\":2}",
            "LSP(operation: \"hover\", file: \"Demo.java\", position: 4:2)");
        assertToolHeader("ListMcpResourcesTool", "{\"server\":\"docs\"}",
            "listMcpResources(List MCP resources from server \"docs\")");
        assertToolHeader("ReadMcpResourceTool",
            "{\"server\":\"docs\",\"uri\":\"docs://guide\"}",
            "readMcpResource(Read resource \"docs://guide\" from server \"docs\")");
        assertToolHeader("WebFetch", "{\"url\":\"https://example.com\",\"prompt\":\"summary\"}",
            "WebFetch(https://example.com)");
        assertToolHeader("WebSearch", "{\"query\":\"java 21\"}",
            "Web Search(\"java 21\")");
        assertToolHeader("mcp__docs__lookup", "{\"query\":\"records\"}",
            "docs - lookup (MCP)(query: \"records\")");

        ToolHarness lsp = startTool("LSP", "toolu_lsp", "{\"operation\":\"findReferences\"}");
        structuredResult("LSP", "toolu_lsp", "A.java:1\nB.java:2", Map.of(
            "operation", "findReferences", "result", "A.java:1\nB.java:2",
            "filePath", "Demo.java", "resultCount", 2, "fileCount", 2), lsp);
        assertTrue(texts(lsp.panel()).stream().anyMatch(text ->
            Strings.CS.contains(text, "Found 2 references across 2 files")));

        ToolHarness list = startTool("ListMcpResourcesTool", "toolu_list_resources", "{}");
        structuredResult("ListMcpResourcesTool", "toolu_list_resources", "[]", List.of(), list);
        assertTrue(texts(list.panel()).stream().anyMatch(text -> Strings.CS.contains(text, "(No resources found)")));

        ToolHarness read = startTool("ReadMcpResourceTool", "toolu_read_resource",
            "{\"server\":\"docs\",\"uri\":\"docs://guide\"}");
        structuredResult("ReadMcpResourceTool", "toolu_read_resource", "{}",
            Map.of("contents", List.of()), read);
        assertTrue(texts(read.panel()).stream().anyMatch(text -> Strings.CS.contains(text, "(No content)")));

        ToolHarness fetch = startTool("WebFetch", "toolu_fetch",
            "{\"url\":\"https://example.com\",\"prompt\":\"summary\"}");
        structuredResult("WebFetch", "toolu_fetch", "body", Map.of(
            "bytes", 1536, "code", 200, "codeText", "OK", "result", "body",
            "durationMs", 20, "url", "https://example.com"), fetch);
        assertTrue(texts(fetch.panel()).stream().anyMatch(text ->
            Strings.CS.contains(text, "Received 1.5KB (200 OK)")));
        assertFalse(texts(fetch.panel()).stream().anyMatch(text -> Strings.CS.equals("body", text)));

        ToolHarness search = startTool("WebSearch", "toolu_web_search", "{\"query\":\"java 21\"}");
        structuredResult("WebSearch", "toolu_web_search", "links", Map.of(
            "query", "java 21", "results", List.of(
                Map.of("tool_use_id", "srv_1", "content", List.of(
                    Map.of("title", "JEP", "url", "https://openjdk.org"))),
                "commentary"), "durationSeconds", 1.6), search);
        assertTrue(texts(search.panel()).stream().anyMatch(text -> Strings.CS.contains(text, "Did 1 search in 2s")));

        ToolHarness mcp = startTool("mcp__docs__lookup", "toolu_mcp", "{\"query\":\"records\"}");
        structuredResult("mcp__docs__lookup", "toolu_mcp", "blocks", List.of(
            Map.of("type", "image", "source", Map.of("type", "base64")),
            Map.of("type", "text", "text", "Found records")), mcp);
        assertTrue(texts(mcp.panel()).stream().anyMatch(text -> Strings.CS.contains(text, "[Image]")));
        assertTrue(texts(mcp.panel()).stream().anyMatch(text -> Strings.CS.contains(text, "Found records")));

        ToolHarness slack = startTool("mcp__slack__send_message", "toolu_slack",
            "{\"channel\":\"eng\",\"message\":\"hello\"}");
        structuredResult("mcp__slack__send_message", "toolu_slack", "sent",
            "{\"ok\":true,\"message_link\":\"https://acme.slack.com/archives/C123/p123456\"}", slack);
        assertTrue(texts(slack.panel()).stream().anyMatch(text ->
            Strings.CS.contains(text, "Sent a message to #eng")));

        ToolHarness flat = startTool("mcp__docs__metadata", "toolu_mcp_flat", "{}");
        structuredResult("mcp__docs__metadata", "toolu_mcp_flat", "metadata",
            "{\"status\":\"ready\",\"count\":3}", flat);
        assertTrue(texts(flat.panel()).stream().anyMatch(text -> Strings.CS.contains(text, "status: ready")));
        assertTrue(texts(flat.panel()).stream().anyMatch(text -> Strings.CS.contains(text, "count : 3")));
    }

    @Test
    void secondBatchProgressPayloadsUseReleasedLabels() {
        ToolHarness mcp = startTool("mcp__docs__lookup", "toolu_mcp_progress", "{}");
        mcp.dispatcher().dispatch(progress("toolu_mcp_progress", new ProgressMessage.ProgressData(
            "mcp_progress", null, null, null, null, null, null, null, true,
            null, null, null, 5.0, 10.0, "Indexing", null, null)), mcp.panel());
        assertTrue(texts(mcp.panel()).stream().anyMatch(text -> Strings.CS.contains(text, "Indexing")));
        assertTrue(texts(mcp.panel()).stream().anyMatch(text -> Strings.CS.contains(text, "50%")));

        ToolHarness web = startTool("WebSearch", "toolu_search_progress", "{\"query\":\"java\"}");
        web.dispatcher().dispatch(progress("toolu_search_progress", new ProgressMessage.ProgressData(
            "query_update", null, null, null, null, null, null, null, true,
            null, null, null, null, null, null, "java records", null)), web.panel());
        assertTrue(texts(web.panel()).stream().anyMatch(text -> Strings.CS.contains(text, "Searching: java records")));
        web.dispatcher().dispatch(progress("toolu_search_progress", new ProgressMessage.ProgressData(
            "search_results_received", null, null, null, null, null, null, null, true,
            null, null, null, null, null, null, "java records", 4L)), web.panel());
        assertTrue(texts(web.panel()).stream().anyMatch(text ->
            Strings.CS.contains(text, "Found 4 results for \"java records\"")));
    }

    @Test
    void thirdBatchHiddenToolsStayAbsent() {
        ToolHarness todo = startTool("TodoWrite", "toolu_todo", "{\"todos\":[]}");
        structuredResult("TodoWrite", "toolu_todo", "Todos have been modified successfully",
            Map.of("oldTodos", List.of(), "newTodos", List.of()), todo);
        assertTrue(texts(todo.panel()).isEmpty());

        ToolHarness search = startTool("ToolSearch", "toolu_tool_search",
            "{\"query\":\"browser\"}");
        structuredResult("ToolSearch", "toolu_tool_search", "Found BrowserTool",
            Map.of("matches", List.of("BrowserTool"), "query", "browser",
                "total_deferred_tools", 1), search);
        assertTrue(texts(search.panel()).isEmpty());
    }

    @Test
    void thirdBatchCronAndMcpAuthContractsMatchReleasedUi() {
        assertToolHeader("CronCreate",
            "{\"cron\":\"*/5 * * * *\",\"prompt\":\"check build\"}",
            "CronCreate(*/5 * * * *: check build)");
        assertToolHeader("CronDelete", "{\"id\":\"job-1\"}", "CronDelete(job-1)");
        assertToolHeader("CronList", "{}", "CronList");
        assertToolHeader("mcp__slack__authenticate", "{}",
            "slack - authenticate (MCP)(Authenticate slack MCP server)");

        ToolHarness create = startTool("CronCreate", "toolu_cron_create",
            "{\"cron\":\"*/5 * * * *\",\"prompt\":\"check build\"}");
        structuredResult("CronCreate", "toolu_cron_create", "scheduled", Map.of(
            "id", "job-1", "humanSchedule", "Every 5 minutes",
            "recurring", true, "durable", false), create);
        assertTrue(texts(create.panel()).stream().anyMatch(text ->
            Strings.CS.contains(text, "Scheduled job-1 (Every 5 minutes)")));

        ToolHarness delete = startTool("CronDelete", "toolu_cron_delete",
            "{\"id\":\"job-1\"}");
        structuredResult("CronDelete", "toolu_cron_delete", "cancelled",
            Map.of("id", "job-1"), delete);
        assertTrue(texts(delete.panel()).stream().anyMatch(text ->
            Strings.CS.contains(text, "Cancelled job-1")));

        ToolHarness emptyList = startTool("CronList", "toolu_cron_list_empty", "{}");
        structuredResult("CronList", "toolu_cron_list_empty", "[]",
            Map.of("jobs", List.of()), emptyList);
        assertTrue(texts(emptyList.panel()).stream().anyMatch(text ->
            Strings.CS.contains(text, "No scheduled jobs")));

        ToolHarness list = startTool("CronList", "toolu_cron_list", "{}");
        structuredResult("CronList", "toolu_cron_list", "jobs", Map.of("jobs", List.of(
            Map.of("id", "job-1", "cron", "*/5 * * * *",
                "humanSchedule", "Every 5 minutes", "prompt", "check build"))), list);
        assertTrue(texts(list.panel()).stream().anyMatch(text ->
            Strings.CS.contains(text, "job-1 Every 5 minutes")));
        assertFalse(texts(list.panel()).stream().anyMatch(text ->
            Strings.CS.contains(text, "check build")));

        ToolHarness auth = startTool("mcp__slack__authenticate", "toolu_mcp_auth", "{}");
        structuredResult("mcp__slack__authenticate", "toolu_mcp_auth", "Open the URL",
            Map.of("status", "auth_url", "message", "Open the URL",
                "authUrl", "https://example.com/auth"), auth);
        assertTrue(texts(auth.panel()).stream().anyMatch(text ->
            Strings.CS.contains(text, "Authenticate slack MCP server")));
        assertFalse(texts(auth.panel()).stream().anyMatch(text ->
            Strings.CS.contains(text, "Open the URL")
                || Strings.CS.contains(text, "https://example.com/auth")));
    }

    @Test
    void finalToolUseAndResultRemainVisibleWithoutStreamingEvents() {
        MessagePanel panel = new MessagePanel();
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        String id = "toolu_cron_delete_projection";
        var input = JsonUtils.getMapper().createObjectNode().put("id", "job-1");

        dispatcher.dispatch(new SDKMessage.Assistant(new AssistantMessage(
            "assistant-cron-delete",
            AssistantContent.of(List.of(new ToolUseBlock(id, "CronDelete", input)))),
            Usage.EMPTY), panel);

        assertTrue(texts(panel).stream().anyMatch(text ->
            Strings.CS.contains(text, "CronDelete(job-1)")));

        UserMessage result = new UserMessage("result-" + id,
            MessageContent.ofToolResult(id, List.of(new TextBlock("Cancelled job-1.")), false),
            false, false, Map.of("id", "job-1"), MessageOrigin.USER, null, Instant.now(),
            null, null);
        dispatcher.dispatch(new SDKMessage.User(result), panel);

        assertTrue(texts(panel).stream().anyMatch(text ->
            Strings.CS.contains(text, "Cancelled job-1")));
    }

    @Test
    void finalToolUseProjectionDoesNotDuplicateAnExistingStreamedHeader() {
        MessagePanel panel = new MessagePanel();
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        String id = "toolu_cron_delete_streamed";
        var input = JsonUtils.getMapper().createObjectNode().put("id", "job-1");

        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_start", "CronDelete|" + id + "|assistant-cron-delete"), panel);
        dispatcher.dispatch(new SDKMessage.Assistant(new AssistantMessage(
            "assistant-cron-delete",
            AssistantContent.of(List.of(new ToolUseBlock(id, "CronDelete", input)))),
            Usage.EMPTY), panel);

        long headers = texts(panel).stream()
            .filter(text -> Strings.CS.contains(text, "CronDelete(job-1)"))
            .count();
        assertEquals(1, headers);
    }

    @Test
    void toolMessageCopiesResultWhilePrimaryActionCopiesCommand() {
        MessagePanel panel = new MessagePanel();
        panel.appendLine("prompt", LanternaTheme.inputText());
        panel.registerLogicalMessage("user", MessagePanel.LogicalMessageKind.USER,
            0, 0, "prompt", "prompt", null, null, false);
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();

        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_start", "Bash|toolu_1"), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_done", "Bash|toolu_1|{\"command\":\"git status\"}"), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_result_success", "Bash|working tree clean"), panel);

        panel.enterMessageActions();
        MessagePanel.LogicalMessage selected = panel.selectBottomLogicalMessage().orElseThrow();
        assertEquals("working tree clean", selected.copyText());
        assertEquals("git status", selected.primaryInput());
        assertEquals("command", selected.primaryInputLabel());
    }

    private static void assertToolHeader(String toolName, String input, String expected) {
        ToolHarness harness = startTool(toolName, "toolu_" + toolName, input);
        assertTrue(texts(harness.panel()).stream().anyMatch(text -> Strings.CS.contains(text, expected)),
            () -> toolName + " rows: " + texts(harness.panel()));
    }

    private static ToolHarness startTool(String toolName, String id, String input) {
        MessagePanel panel = new MessagePanel();
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        dispatcher.dispatch(new SDKMessage.StreamEvent("tool_streaming_start", toolName + "|" + id), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_done", toolName + "|" + id + "|" + input), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_call_start", toolName + "|" + id + "|" + input), panel);
        return new ToolHarness(dispatcher, panel);
    }

    private static MessagePanel completedLiveTool(String toolName, String id,
                                                  String input, String result) {
        ToolHarness harness = startTool(toolName, id, input);
        harness.dispatcher().dispatch(new SDKMessage.StreamEvent(
            "tool_result_success", toolName + "|" + result), harness.panel());
        return harness.panel();
    }

    private static void structuredResult(String toolName, String id, String text,
                                         Object payload, ToolHarness harness) {
        LanternaMessageDispatcher dispatcher = harness.dispatcher();
        MessagePanel panel = harness.panel();
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_result_success", toolName + "|" + text), panel);
        UserMessage message = new UserMessage("result-" + id,
            MessageContent.ofToolResult(id, List.of(new TextBlock(text)), false),
            false, false, payload, MessageOrigin.USER, null, Instant.now(), null, null);
        dispatcher.dispatch(new SDKMessage.User(message), panel);
    }

    private static List<String> texts(MessagePanel panel) {
        return panel.displayRowsForTest(160).stream().map(MessagePanel.StyledLine::text).toList();
    }

    private static SDKMessage.Progress progress(String toolUseId,
                                                ProgressMessage.ProgressData data) {
        return new SDKMessage.Progress(new ProgressMessage(
            "progress-" + toolUseId, "", null, Instant.now(), toolUseId, null, data));
    }

    private record ToolHarness(LanternaMessageDispatcher dispatcher, MessagePanel panel) {}
    private record TaskToolCase(String name, String inputJson, String resultText) {}
}

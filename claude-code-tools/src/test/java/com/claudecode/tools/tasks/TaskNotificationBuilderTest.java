package com.claudecode.tools.tasks;

import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskNotificationBuilderTest {

    private static TaskState terminal(TaskType type, String desc, TaskStatus status) {
        return TaskState.withId("t-" + type.name(), type, desc)
            .withStatus(TaskStatus.RUNNING)
            .withStatus(status);
    }

    @Test
    void agent_completed_includesResultUsageWorktree_noTaskType() {
        TaskState task = terminal(TaskType.LOCAL_AGENT, "my agent", TaskStatus.COMPLETED)
            .withToolUseId("toolu_agent")
            .withFinalMessage("did the thing")
            .withUsage(new TaskUsage(1234, 7, 5000))
            .withWorktreePath("/repo/.claude/worktrees/wt");

        String xml = TaskNotificationBuilder.build(task);

        assertFalse(Strings.CS.contains(xml, "<task_type>"), "agent omits task_type (LocalAgentTask.tsx)");
        assertTrue(Strings.CS.startsWith(xml, "<task-notification>\n"), xml);
        assertTrue(Strings.CS.contains(xml, "<task-id>t-LOCAL_AGENT</task-id>"), xml);
        assertTrue(Strings.CS.contains(xml, "<tool-use-id>toolu_agent</tool-use-id>"), xml);
        assertTrue(Strings.CS.contains(xml, "<output-file>"), xml);
        assertTrue(Strings.CS.contains(xml, "<status>completed</status>"), xml);
        assertTrue(Strings.CS.contains(xml, "<summary>Agent \"my agent\" finished</summary>"), xml);
        assertTrue(Strings.CS.contains(xml, "<note>A task-notification fires each time this agent stops with no live "
            + "background children of its own. The user can send it another message and resume it, so the "
            + "same task-id may notify more than once.</note>"), xml);
        assertTrue(Strings.CS.contains(xml, "<result>did the thing</result>"), xml);
        assertTrue(Strings.CS.contains(xml, "<usage><subagent_tokens>1234</subagent_tokens>"
            + "<tool_uses>7</tool_uses><duration_ms>5000</duration_ms></usage>"), xml);
        assertTrue(Strings.CS.contains(xml, "<worktree><worktree_path>/repo/.claude/worktrees/wt"
            + "</worktree_path></worktree>"), xml);
        // element order: output-file → status → summary → note → result → usage → worktree
        int out = xml.indexOf("<output-file>");
        int st = xml.indexOf("<status>");
        int sum = xml.indexOf("<summary>");
        int note = xml.indexOf("<note>");
        int res = xml.indexOf("<result>");
        int use = xml.indexOf("<usage>");
        int wt = xml.indexOf("<worktree>");
        assertTrue(out < st && st < sum && sum < note && note < res && res < use && use < wt, xml);
    }

    @Test
    void agent_failed_includesErrorInSummary_noTaskType() {
        TaskState task = terminal(TaskType.LOCAL_AGENT, "review PR", TaskStatus.FAILED)
            .withErrorMessage("boom");

        String xml = TaskNotificationBuilder.build(task);

        assertFalse(Strings.CS.contains(xml, "<task_type>"), xml);
        assertTrue(Strings.CS.startsWith(xml, "<task-notification>\n"), xml);
        assertTrue(Strings.CS.contains(xml, "<summary>Agent \"review PR\" failed: boom</summary>"), xml);
        assertFalse(Strings.CS.contains(xml, "<result>"), "no result section when none present");
        assertFalse(Strings.CS.contains(xml, "<usage>"), "no usage section when none present");
    }

    @Test
    void bash_completed_includesExitCode_noTaskType() {
        TaskState task = terminal(TaskType.LOCAL_BASH, "build", TaskStatus.COMPLETED)
            .withExitCode(0);

        String xml = TaskNotificationBuilder.build(task);

        assertFalse(Strings.CS.contains(xml, "<task_type>"), "bash omits task_type (LocalShellTask.tsx)");
        assertTrue(Strings.CS.contains(xml, "<summary>Background command \"build\" completed (exit code 0)</summary>"), xml);
    }

    @Test
    void bash_failed_includesExitCodeSuffix() {
        TaskState task = terminal(TaskType.LOCAL_BASH, "build", TaskStatus.FAILED)
            .withExitCode(2);

        String xml = TaskNotificationBuilder.build(task);

        assertTrue(Strings.CS.contains(xml, "<summary>Background command \"build\" failed (with exit code 2)</summary>"), xml);
    }

    @Test
    void remote_agent_includesTaskTypeAndRemoteSummary() {
        TaskState task = terminal(TaskType.REMOTE_AGENT, "scan", TaskStatus.COMPLETED);

        String xml = TaskNotificationBuilder.build(task);

        assertTrue(Strings.CS.contains(xml, "<task_type>remote_agent</task_type>"), xml);
        assertTrue(Strings.CS.contains(xml, "<summary>Remote task \"scan\" completed successfully</summary>"), xml);
    }

    @Test
    void workflow_includesOfficialSummaryAndUsage() {
        TaskState task = terminal(TaskType.LOCAL_WORKFLOW, "flow", TaskStatus.COMPLETED)
            .withUsage(new TaskUsage(123, 4, 987));

        String xml = TaskNotificationBuilder.build(task);

        assertFalse(Strings.CS.contains(xml, "<task_type>"), xml);
        assertTrue(Strings.CS.contains(xml, "<summary>Dynamic workflow \"flow\" completed</summary>"), xml);
        assertTrue(Strings.CS.contains(xml, "<usage><agent_count>0</agent_count>"
            + "<subagent_tokens>123</subagent_tokens><tool_uses>4</tool_uses>"
            + "<duration_ms>987</duration_ms></usage>"), xml);
    }

    @Test
    void failed_workflow_includesTheRuntimeErrorInItsSummary() {
        String xml = TaskNotificationBuilder.build(
            terminal(TaskType.LOCAL_WORKFLOW, "flow", TaskStatus.FAILED)
                .withErrorMessage("invalid structured response"));

        assertTrue(Strings.CS.contains(xml, "<summary>Dynamic workflow \"flow\" failed: "
            + "invalid structured response</summary>"), xml);
    }
}

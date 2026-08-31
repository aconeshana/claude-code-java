package com.claudecode.services.session;

import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.message.*;
import com.claudecode.core.queue.QueuePriority;
import com.claudecode.core.queue.QueuedCommand;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.query.DefaultQuerySession;
import com.claudecode.runtime.query.QuerySessionSpec;
import com.claudecode.session.SessionStorage;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskStore;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.apache.commons.lang3.Strings;

import java.time.Instant;
import java.util.Collections;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResumeStateRestorerWorkflowOrphanTest {

    private static final StreamingClient NOOP_CLIENT = new StreamingClient() {
        @Override public Iterator<StreamingEvent> createStream(StreamRequest request) {
            return Collections.emptyIterator();
        }

        @Override public String getModel() { return "test-model"; }
    };

    @AfterEach
    void resetTasks() {
        TaskRegistry.resetGlobalForTest();
    }

    @Test
    void releasedResumeQueuesStoppedNotificationForWorkflowWithoutCompletionRecord() {
        TaskRegistry.setGlobalForTest(new TaskRegistry(TaskStore.inMemory()));
        DefaultQuerySession engine = engine();
        ResumeStateRestorer restorer = new ResumeStateRestorer(
            engine, new SessionStorage(), null, null);

        restorer.restoreOrphanedBackgroundWorkflows(List.of(
            workflowLaunch("task-workflow", "toolu-workflow", "audit & verify", "wf_resume-123")));

        QueuedCommand notification = engine.getMessageQueue().snapshot().getFirst();
        assertEquals("task-notification", notification.mode());
        assertEquals(QueuePriority.NEXT, notification.priority());
        assertEquals("task-workflow", notification.taskId());
        assertEquals("""
            <task-notification>
            <task-id>task-workflow</task-id>
            <tool-use-id>toolu-workflow</tool-use-id>
            <status>stopped</status>
            <summary>No completion record was found for background workflow "audit &amp; verify" from the previous session. It may have been stopped (via the UI or TaskStop — these leave no transcript marker), or it may have been running when the previous Claude Code process exited. To pick up where it left off, relaunch with Workflow({scriptPath, resumeFromRunId: "wf_resume-123"}) — completed agent() calls return cached.</summary>
            </task-notification>""", notification.text());
        SDKMessage.TaskNotification sdk = (SDKMessage.TaskNotification)
            engine.getMessageQueue().drainSdkEvents().getFirst();
        assertEquals("task-workflow", sdk.taskId());
        assertEquals("stopped", sdk.status());
    }

    @Test
    void releasedResumeSkipsWorkflowWithCompletionRecordOrCompileError() {
        TaskRegistry.setGlobalForTest(new TaskRegistry(TaskStore.inMemory()));
        DefaultQuerySession engine = engine();
        ResumeStateRestorer restorer = new ResumeStateRestorer(
            engine, new SessionStorage(), null, null);
        UserMessage compileError = workflowLaunch(
            "task-error", "toolu-error", "broken", "wf_error-123");
        ((ObjectNode) compileError.toolUseResult())
            .put("error", "SyntaxError");
        UserMessage completed = new UserMessage(
            "completed", MessageContent.ofText("""
                <task-notification>
                <task-id>task-workflow</task-id>
                <status>completed</status>
                </task-notification>"""), false, false, null,
            MessageOrigin.TASK_NOTIFICATION, null, Instant.now(), null, null);

        restorer.restoreOrphanedBackgroundWorkflows(List.of(
            workflowLaunch("task-workflow", "toolu-workflow", "audit", "wf_resume-123"),
            compileError,
            completed));

        assertTrue(engine.getMessageQueue().snapshot().isEmpty());
    }

    @Test
    void releasedResumeAggregatesMoreThanTwentyOrphanedWorkflows() {
        TaskRegistry.setGlobalForTest(new TaskRegistry(TaskStore.inMemory()));
        DefaultQuerySession engine = engine();
        ResumeStateRestorer restorer = new ResumeStateRestorer(
            engine, new SessionStorage(), null, null);
        List<Message> launches = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            launches.add(workflowLaunch("task-" + i, "toolu-" + i,
                "workflow-" + i, "wf_resume-" + i));
        }

        restorer.restoreOrphanedBackgroundWorkflows(launches);

        assertEquals(1, engine.getMessageQueue().snapshot().size());
        String aggregate = engine.getMessageQueue().snapshot().getFirst().text();
        assertTrue(Strings.CS.contains(aggregate, "21 background workflow task(s)"), aggregate);
        assertTrue(Strings.CS.contains(aggregate, "<task-id>task-0</task-id>"), aggregate);
        assertTrue(Strings.CS.contains(aggregate, "<task-id>task-19</task-id>"), aggregate);
        assertTrue(Strings.CS.contains(aggregate,
            "<task-id>__orphan_summary__:workflow</task-id>"), aggregate);
        assertFalse(Strings.CS.contains(aggregate, "<task-id>task-20</task-id>"), aggregate);
        assertEquals(21, engine.getMessageQueue().drainSdkEvents().size());
    }

    @Test
    void taskIdMarkupOutsideAStatusNotificationDoesNotSuppressRecovery() {
        TaskRegistry.setGlobalForTest(new TaskRegistry(TaskStore.inMemory()));
        DefaultQuerySession engine = engine();
        ResumeStateRestorer restorer = new ResumeStateRestorer(
            engine, new SessionStorage(), null, null);
        UserMessage ordinaryText = new UserMessage(
            "ordinary", MessageContent.ofText("Mention <task-id>task-workflow</task-id> only"),
            false, false, null, MessageOrigin.USER, null, Instant.now(), null, null);

        restorer.restoreOrphanedBackgroundWorkflows(List.of(
            workflowLaunch("task-workflow", "toolu-workflow", "audit", "wf_resume-123"),
            ordinaryText));

        assertEquals(1, engine.getMessageQueue().snapshot().size());
    }

    private static DefaultQuerySession engine() {
        return new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .workingDirectory(System.getProperty("user.dir"))
            .build());
    }

    private static UserMessage workflowLaunch(
            String taskId, String toolUseId, String workflowName, String runId) {
        var result = JsonUtils.getMapper().createObjectNode();
        result.put("status", "async_launched");
        result.put("taskType", "local_workflow");
        result.put("taskId", taskId);
        result.put("workflowName", workflowName);
        result.put("runId", runId);
        return new UserMessage(
            "launch-" + taskId,
            MessageContent.ofBlocks(List.of(new ToolResultBlock(
                toolUseId, List.of(new TextBlock("launched")), false))),
            false, false, result, MessageOrigin.USER, null, Instant.now(),
            null, null);
    }
}

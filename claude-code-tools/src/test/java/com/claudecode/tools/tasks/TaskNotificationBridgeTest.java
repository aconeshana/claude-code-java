package com.claudecode.tools.tasks;

import org.apache.commons.lang3.Strings;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.queue.MessageQueueManager;
import com.claudecode.core.queue.QueuePriority;
import com.claudecode.core.queue.QueuedCommand;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TaskNotificationBridgeTest {

    @AfterEach
    void reset() {
        TaskRegistry.resetGlobalForTest();
    }

    @Test
    void enqueuesNotification_onTerminalTransition() {
        TaskRegistry.setGlobalForTest(new TaskRegistry(TaskStore.inMemory()));
        MessageQueueManager queue = new MessageQueueManager();
        new TaskNotificationBridge(queue).register();

        TaskState task = TaskRegistry.global().store().create(TaskType.LOCAL_BASH, "build the thing");
        TaskRegistry.global().store().updateStatus(task.id(), TaskStatus.RUNNING);
        TaskRegistry.global().store().updateStatus(task.id(), TaskStatus.COMPLETED);

        assertEquals(1, queue.size(), "one terminal transition → one notification");
        QueuedCommand cmd = queue.peek();
        assertEquals("task-notification", cmd.mode());
        assertEquals(QueuePriority.LATER, cmd.priority(),
            "Background notification must sit at LATER priority");
        String text = cmd.text();
        assertTrue(Strings.CS.contains(text, "<task_notification>"), text);
        assertTrue(Strings.CS.contains(text, "<task_id>" + task.id() + "</task_id>"), text);
        assertFalse(Strings.CS.contains(text, "<task_type>"),
            "bash completion omits task_type (LocalShellTask.tsx shape)");
        assertTrue(Strings.CS.contains(text, "<status>completed</status>"), text);
        assertTrue(Strings.CS.contains(text, "<output_file>"), text);
        assertTrue(Strings.CS.contains(text, "Background command \"build the thing\" completed"), text);
    }

    @Test
    void enqueuesSubagentNotification_withAgentType() {
        TaskRegistry.setGlobalForTest(new TaskRegistry(TaskStore.inMemory()));
        MessageQueueManager queue = new MessageQueueManager();
        new TaskNotificationBridge(queue).register();

        TaskState task = TaskRegistry.global().store().create(TaskType.LOCAL_AGENT, "review PR");
        TaskRegistry.global().store().updateToolUseId(task.id(), "toolu_agent");
        TaskRegistry.global().store().updateStatus(task.id(), TaskStatus.RUNNING);
        TaskRegistry.global().store().updateStatus(task.id(), TaskStatus.FAILED);

        QueuedCommand cmd = queue.peek();
        assertFalse(Strings.CS.contains(cmd.text(), "<task_type>"),
            "agent completion omits task_type (LocalAgentTask.tsx shape)");
        assertTrue(Strings.CS.contains(cmd.text(), "<task-id>" + task.id() + "</task-id>"), cmd.text());
        assertTrue(Strings.CS.contains(cmd.text(), "<tool-use-id>toolu_agent</tool-use-id>"), cmd.text());
        assertTrue(Strings.CS.contains(cmd.text(), "<status>failed</status>"), cmd.text());
        assertTrue(Strings.CS.contains(cmd.text(), "Agent \"review PR\" failed: Unknown error"), cmd.text());

        SDKMessage.TaskUpdated updated = assertInstanceOf(
            SDKMessage.TaskUpdated.class, queue.drainSdkEvents().getFirst());
        assertEquals(task.id(), updated.taskId());
        assertEquals("failed", updated.patch().get("status"));
        assertTrue(updated.patch().containsKey("end_time"));
    }

    @Test
    void tagsAgentId_whenTaskOwnedBySubagent() {
        TaskRegistry.setGlobalForTest(new TaskRegistry(TaskStore.inMemory()));
        MessageQueueManager queue = new MessageQueueManager();
        new TaskNotificationBridge(queue).register();

        // A background bash started inside a sub-agent carries the sub-agent's id.
        TaskState task = TaskRegistry.global().store().create(TaskType.LOCAL_BASH, "sleep", "sub-agent-7");
        TaskRegistry.global().store().updateStatus(task.id(), TaskStatus.RUNNING);
        TaskRegistry.global().store().updateStatus(task.id(), TaskStatus.COMPLETED);

        QueuedCommand cmd = queue.peek();
        assertEquals("sub-agent-7", cmd.agentId(),
            "completion notification must be routed to the owning sub-agent");
    }

    @Test
    void tagsNullAgentId_forMainThreadTask() {
        TaskRegistry.setGlobalForTest(new TaskRegistry(TaskStore.inMemory()));
        MessageQueueManager queue = new MessageQueueManager();
        new TaskNotificationBridge(queue).register();

        TaskState task = TaskRegistry.global().store().create(TaskType.LOCAL_BASH, "build the thing");
        TaskRegistry.global().store().updateStatus(task.id(), TaskStatus.RUNNING);
        TaskRegistry.global().store().updateStatus(task.id(), TaskStatus.COMPLETED);

        QueuedCommand cmd = queue.peek();
        assertNull(cmd.agentId(),
            "main-thread task notification must stay unrouted (drained by coordinator)");
    }

    @Test
    void skipsAlreadyNotifiedTask() {
        TaskRegistry.setGlobalForTest(new TaskRegistry(TaskStore.inMemory()));
        MessageQueueManager queue = new MessageQueueManager();
        new TaskNotificationBridge(queue).register();

        TaskState task = TaskRegistry.global().store().create(TaskType.LOCAL_AGENT, "agent");
        TaskRegistry.global().store().markNotified(task.id());
        TaskRegistry.global().store().updateStatus(task.id(), TaskStatus.KILLED);

        assertEquals(0, queue.size(),
            "a task killed via the cancel flow (notified up-front) must not enqueue a per-task notification");
    }

    @Test
    void commandMonitorUsesHyphenatedNextPriorityShapeAndReleasesHandle() {
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        MessageQueueManager queue = new MessageQueueManager();
        new TaskNotificationBridge(queue, registry).register();
        TaskState task = registry.store().create(
            TaskType.LOCAL_BASH, "wire events", null);
        registry.registerMonitor(new MonitorTaskHandle() {
            @Override public String getTaskId() { return task.id(); }
            @Override public Path getOutputPath() { return Path.of("monitor.output"); }
            @Override public String displaySource() { return "printf one"; }
            @Override public boolean kill() { return false; }
        });

        registry.store().updateStatus(task.id(), TaskStatus.RUNNING);
        registry.store().updateStatus(task.id(), TaskStatus.COMPLETED);

        QueuedCommand cmd = queue.peek();
        assertEquals(QueuePriority.NEXT, cmd.priority());
        assertTrue(Strings.CS.contains(cmd.text(), "<task-notification>"), cmd.text());
        assertTrue(Strings.CS.contains(cmd.text(), "Monitor \"wire events\" stream ended"), cmd.text());
        SDKMessage.TaskUpdated updated = assertInstanceOf(
            SDKMessage.TaskUpdated.class, queue.drainSdkEvents().getFirst());
        assertEquals(task.id(), updated.taskId());
        assertEquals("completed", updated.patch().get("status"));
        assertTrue(updated.patch().containsKey("end_time"));
        assertFalse(registry.isMonitorTask(task.id()));
    }

    @Test
    void builder_escapesSpecialCharsInDescription() {
        String xml = TaskNotificationBuilder.build(
            TaskState.withId("b123", TaskType.LOCAL_BASH, "a <b> & c"));
        assertTrue(Strings.CS.contains(xml, "a &lt;b&gt; &amp; c"), xml);
        assertFalse(Strings.CS.contains(xml, "a <b> & c"), "raw special chars must be escaped");
    }
}

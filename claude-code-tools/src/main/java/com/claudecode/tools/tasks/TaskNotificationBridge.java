package com.claudecode.tools.tasks;

import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.queue.MessageQueueManager;
import com.claudecode.core.queue.QueuePriority;
import com.claudecode.core.queue.QueuedCommand;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Bridges background-task terminal transitions into the session message queue.
 */
public final class TaskNotificationBridge {

    private final MessageQueueManager queue;
    private final TaskRegistry registry;

    public TaskNotificationBridge(MessageQueueManager queue) {
        this(queue, TaskRegistry.global());
    }

    /** Test/composition seam for a non-global registry. */
    public TaskNotificationBridge(MessageQueueManager queue, TaskRegistry registry) {
        this.queue = queue;
        this.registry = registry;
    }

    /** Bind this session queue and ensure the registry owns one listener. */
    public void register() {
        registry.setMessageQueue(queue);
        registry.bindTaskNotifications();
    }

    /** Performs one queue delivery after the registry has claimed the task. */
    static void deliver(MessageQueueManager queue, TaskRegistry registry, TaskState task) {
        boolean monitor = task.type() == TaskType.MONITOR_MCP
            || task.type() == TaskType.MONITOR_WS
            || registry.isMonitorTask(task.id());
        String xml = TaskNotificationBuilder.build(task, monitor,
            task.type() == TaskType.LOCAL_WORKFLOW
                ? registry.workflowRun(task.id()).orElse(null) : null);
        if (monitor || task.type() == TaskType.LOCAL_AGENT
                || task.type() == TaskType.LOCAL_WORKFLOW) {
            Map<String, Object> patch = new LinkedHashMap<>();
            patch.put("status", task.status().name().toLowerCase(Locale.ROOT));
            task.endTime().ifPresent(end -> patch.put("end_time", end.toEpochMilli()));
            queue.enqueueSdkEvent(new SDKMessage.TaskUpdated(task.id(), patch));
        }
        // Route the notification to the owning agent: a sub-agent's task
        // carries its agentId, so only that agent's engine loop drains it.
        // The task id rides along so a notification still sitting in the queue at
        // turn end counts as pending background work (PendingBackgroundWork).
        queue.enqueuePendingNotification(new QueuedCommand(
            xml, null, "task-notification",
            monitor || task.type() == TaskType.LOCAL_WORKFLOW
                ? QueuePriority.NEXT : QueuePriority.LATER,
            true, null, false, false, null, null, task.agentId().orElse(null),
            null, task.id()));
    }
}

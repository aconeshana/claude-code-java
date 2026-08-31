package com.claudecode.ui.lanterna.repl;

import com.claudecode.core.queue.MessageQueueManager;
import com.claudecode.core.queue.QueuePriority;
import com.claudecode.core.queue.QueuedCommand;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskState;
import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.tools.tasks.TaskType;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Executes the confirmed user action that stops every running background agent.
 */
final class BackgroundAgentCancellationAction {

    private BackgroundAgentCancellationAction() {}

    static void execute(TaskRegistry registry, MessageQueueManager queue) {
        queue.clear();
        List<TaskState> running = registry.listBackground().stream()
            .filter(t -> t.type() == TaskType.LOCAL_AGENT && t.status() == TaskStatus.RUNNING)
            .toList();
        if (running.isEmpty()) return;

        List<String> descriptions = new ArrayList<>();
        for (TaskState task : running) {
            registry.store().markNotified(task.id());
            registry.killAgentByUser(task.id());
            descriptions.add(task.description());
        }

        String summary;
        if (descriptions.size() == 1) {
            summary = "Background agent \"" + descriptions.getFirst()
                + "\" was stopped by the user.";
        } else {
            String joined = descriptions.stream()
                .map(description -> "\"" + description + "\"")
                .collect(Collectors.joining(", "));
            summary = descriptions.size() + " background agents were stopped by the user: "
                + joined + ".";
        }
        queue.enqueuePendingNotification(
            new QueuedCommand(summary, null, "task-notification", QueuePriority.LATER,
                false, null, false, false, null, null, null));
    }
}

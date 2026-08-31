package com.claudecode.ui.lanterna.repl;

import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskState;
import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.tools.tasks.TaskType;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Routes prompt-bar input to the local agent whose transcript is being viewed.
 */
final class LocalAgentInputRouter {

    @FunctionalInterface
    interface AgentResumer {
        void resume(String agentId, String prompt, ToolExecutionContext context,
                    boolean userInitiated);
    }

    private final TaskRegistry registry;
    private final AgentResumer resumer;
    private final Supplier<ToolExecutionContext> contextSupplier;
    private final BiConsumer<String, String> transcriptAppender;
    private final Consumer<String> failureReporter;

    LocalAgentInputRouter(TaskRegistry registry, AgentResumer resumer,
                          Supplier<ToolExecutionContext> contextSupplier,
                          BiConsumer<String, String> transcriptAppender,
                          Consumer<String> failureReporter) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.resumer = Objects.requireNonNull(resumer, "resumer");
        this.contextSupplier = Objects.requireNonNull(contextSupplier, "contextSupplier");
        this.transcriptAppender = Objects.requireNonNull(transcriptAppender, "transcriptAppender");
        this.failureReporter = Objects.requireNonNull(failureReporter, "failureReporter");
    }

    /** Returns false only when the task vanished and leader routing should continue. */
    boolean submit(String taskId, String prompt) {
        TaskState task = registry.get(taskId).orElse(null);
        if (task == null || task.type() != TaskType.LOCAL_AGENT) return false;

        transcriptAppender.accept(taskId, prompt);
        if (task.status() == TaskStatus.RUNNING) {
            if (!registry.queueAgentMessage(taskId, prompt, "user")) {
                failureReporter.accept("Failed to queue message for agent " + taskId);
            }
            return true;
        }

        try {
            resumer.resume(taskId, prompt, contextSupplier.get(), true);
        } catch (RuntimeException failure) {
            failureReporter.accept("Failed to resume agent: " + failure.getMessage());
        }
        return true;
    }
}

package com.claudecode.tools.tasks;

import java.util.List;

/**
 * Dependency-inversion seam letting {@link TodoStore} (claude-code-tools) trigger user-configured
 * {@code TaskCreated}/{@code TaskCompleted} hooks without depending on {@code claude-code-services}
 * (which owns {@code HookEngine} and depends on tools, so the reverse import is impossible).
 */
public interface TaskLifecycleHooks {

    /** Whether any {@code TaskCreated} hook is configured. */
    boolean hasTaskCreatedHook();

    /** Runs {@code TaskCreated} hooks; returns formatted blocking messages (empty = not blocked). */
    List<String> dispatchTaskCreated(String taskId, String subject, String description);

    /**
     * Runs {@code TaskCreated} hooks with the released team-context fields. Implementations that
     * predate those fields continue to receive the event through the three-argument method.
     */
    default List<String> dispatchTaskCreated(
            String taskId, String subject, String description,
            String teammateName, String teamName) {
        return dispatchTaskCreated(taskId, subject, description);
    }

    /** Whether any {@code TaskCompleted} hook is configured. */
    boolean hasTaskCompletedHook();

    /** Runs {@code TaskCompleted} hooks; returns formatted blocking messages (empty = not blocked). */
    List<String> dispatchTaskCompleted(String taskId, String subject, String description);

    /** Released form of {@link #dispatchTaskCompleted(String, String, String)}. */
    default List<String> dispatchTaskCompleted(
            String taskId, String subject, String description,
            String teammateName, String teamName) {
        return dispatchTaskCompleted(taskId, subject, description);
    }
}

package com.claudecode.tools.tasks;

import com.claudecode.core.queue.QueuedCommand;

import org.apache.commons.lang3.Strings;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * How much background work a finished turn is still waiting on.
 */
public record PendingBackgroundWork(int pendingAgents, int pendingWorkflows) {

    public static final PendingBackgroundWork NONE = new PendingBackgroundWork(0, 0);

    /** True when the turn ended with something still to wait for. */
    public boolean any() {
        return pendingAgents > 0 || pendingWorkflows > 0;
    }

    /** The count to put on the transcript row: {@code null}, never {@code 0}. */
    public Integer agentCountOrNull() {
        return pendingAgents > 0 ? pendingAgents : null;
    }

    /** The count to put on the transcript row: {@code null}, never {@code 0}. */
    public Integer workflowCountOrNull() {
        return pendingWorkflows > 0 ? pendingWorkflows : null;
    }

    /**
     * Counts what the main session is still waiting on.
     */
    public static PendingBackgroundWork count(TaskRegistry registry, List<QueuedCommand> queued) {
        Set<String> agents = new LinkedHashSet<>();
        Set<String> workflows = new LinkedHashSet<>();
        for (TaskState task : registry.store().list()) {
            if (isUnreported(task)) classify(registry, task, agents, workflows);
        }
        for (QueuedCommand cmd : queued) {
            if (!Strings.CS.equals("task-notification", cmd.mode())) continue;
            if (cmd.agentId() != null || cmd.taskId() == null) continue;
            registry.get(cmd.taskId())
                .ifPresent(task -> classify(registry, task, agents, workflows));
        }
        return new PendingBackgroundWork(agents.size(), workflows.size());
    }

    /**
     * Applies the cross-turn accumulation rule to one turn's numbers.
     *
     * @param pending     what {@link #count} found at this turn's end
     * @param turnDuration this turn's own active duration, already excluding pauses
     * @param turnStart   when this turn began
     * @param now         the turn-end instant
     * @param waitStart   the wait start carried over from earlier turns, or
     *                    {@code null} when no wait is in progress
     * @return the duration to report plus the wait start to carry into the next turn
     */
    public static Resolved resolve(PendingBackgroundWork pending, long turnDuration,
                                   long turnStart, long now, Long waitStart) {
        if (pending.any()) {
            // Still waiting: report only this turn's own time and remember when the
            // wait began, so the eventual reporting turn can cover the whole span.
            return new Resolved(turnDuration, pending.agentCountOrNull(),
                pending.workflowCountOrNull(), waitStart != null ? waitStart : turnStart);
        }
        // Nothing left: if a wait was in progress, report it end-to-end and clear it.
        return new Resolved(waitStart != null ? now - waitStart : turnDuration,
            null, null, null);
    }

    /**
     * The outcome of {@link #resolve}: what the {@code turn_duration} row should say
     * and what the caller must remember for the next turn.
     *
     * @param durationMs                 duration to render/persist
     * @param pendingBackgroundAgentCount row field, {@code null} when nothing pends
     * @param pendingWorkflowCount        row field, {@code null} when nothing pends
     * @param backgroundWaitStartTime     wait start to carry over, {@code null} to clear
     */
    public record Resolved(long durationMs, Integer pendingBackgroundAgentCount,
                           Integer pendingWorkflowCount, Long backgroundWaitStartTime) {

        /** True when this row renders the "Waiting for …" branch. */
        public boolean waiting() {
            return pendingBackgroundAgentCount != null || pendingWorkflowCount != null;
        }
    }


    private static boolean isUnreported(TaskState task) {
        if (task.status() == TaskStatus.RUNNING) return true;
        boolean terminal = task.status() == TaskStatus.COMPLETED
            || task.status() == TaskStatus.FAILED
            || task.status() == TaskStatus.KILLED;
        return terminal && !task.notified();
    }

    private static void classify(TaskRegistry registry, TaskState task,
                                 Set<String> agents, Set<String> workflows) {
        if (registry.isPanelAgentTask(task) && registry.isBackgroundedAgent(task.id())) {
            agents.add(task.id());
        } else if (task.type() == TaskType.LOCAL_WORKFLOW) {
            workflows.add(task.id());
        }
    }
}

package com.claudecode.tools.tasks;

import java.time.Instant;
import java.util.Optional;

/**
 * Full task state record.
 *
 * <ul>
 *   <li>{@code
 *       LocalAgentTaskState.progress?.summary} (background 1-2 sentence
 *       progress summary, set by {@code updateAgentSummary}) and {@code
 *       LocalAgentTaskState.error} (set by {@code failAgentTask}); represented
 *       here by {@link #progressSummary} / {@link #errorMessage}. Live
 *       {@code tokenCount}/{@code toolUseCount} are projected through
 *       {@link #usage} while the task is running; the summary string is sourced
 *       from {@link LocalAgentTask#updateProgress}'s {@code step} argument.</li>
 *   <li>common
 *       {@code DreamTaskState} fields live here; dream-only phase/session/file/
 *       turn history is composed through {@link #dreamDetails} and
 *       {@link DreamTaskDetails}.</li>
 * </ul>
 */
public record TaskState(
    String id,
    TaskType type,
    TaskStatus status,
    String description,
    Optional<String> toolUseId,
    Instant startTime,
    Optional<Instant> endTime,
    boolean notified,
    Optional<String> progressSummary,
    Optional<String> errorMessage,
    Optional<String> finalMessage,
    Optional<TaskUsage> usage,
    Optional<String> worktreePath,
    Optional<Integer> exitCode,
    /**
     * The agent that owns this task, or empty for a task created by the main thread.
     */
    Optional<String> agentId,
    /**
     * The agent/leader that has claimed this task for work, or empty when it is unclaimed/available.
     */
    Optional<String> claimedBy,
    /** Dream-only live history/state; empty for every other task type. */
    Optional<DreamTaskDetails> dreamDetails
) {

    public TaskState {
        dreamDetails = dreamDetails == null ? Optional.empty() : dreamDetails;
    }

    public static TaskState create(TaskType type, String description) {
        return new TaskState(
            TaskIdGenerator.generate(type),
            type,
            TaskStatus.PENDING,
            description,
            Optional.empty(),
            Instant.now(),
            Optional.empty(),
            false,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        );
    }

    /** Creates a task owned by a specific agent (main thread passes {@code null}). */
    public static TaskState create(TaskType type, String description, String agentId) {
        return new TaskState(
            TaskIdGenerator.generate(type),
            type,
            TaskStatus.PENDING,
            description,
            Optional.empty(),
            Instant.now(),
            Optional.empty(),
            false,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.ofNullable(agentId),
            Optional.empty(),
            Optional.empty()
        );
    }

    /** Creates a task with an externally-supplied id (used by TaskStore for sequential IDs). */
    public static TaskState withId(String id, TaskType type, String description) {
        return new TaskState(
            id,
            type,
            TaskStatus.PENDING,
            description,
            Optional.empty(),
            Instant.now(),
            Optional.empty(),
            false,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        );
    }

    /** Creates a task with an externally-supplied id, owned by a specific agent. */
    public static TaskState withId(String id, TaskType type, String description, String agentId) {
        return new TaskState(
            id,
            type,
            TaskStatus.PENDING,
            description,
            Optional.empty(),
            Instant.now(),
            Optional.empty(),
            false,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.ofNullable(agentId),
            Optional.empty(),
            Optional.empty()
        );
    }

    public TaskState withStatus(TaskStatus newStatus) {
        TaskStateMachine.validateTransition(this.status, newStatus);
        Optional<Instant> end = newStatus.isTerminal() ? Optional.of(Instant.now()) : this.endTime;
        return new TaskState(id, type, newStatus, description, toolUseId, startTime, end, notified,
            progressSummary, errorMessage, finalMessage, usage, worktreePath, exitCode, agentId, claimedBy,
            dreamDetails);
    }

    /** Associates this background task with its originating model tool_use. */
    public TaskState withToolUseId(String toolUseId) {
        return new TaskState(id, type, status, description, Optional.ofNullable(toolUseId), startTime,
            endTime, notified, progressSummary, errorMessage, finalMessage, usage, worktreePath,
            exitCode, agentId, claimedBy, dreamDetails);
    }

    public TaskState withNotified(boolean notified) {
        return new TaskState(id, type, status, description, toolUseId, startTime, endTime, notified,
            progressSummary, errorMessage, finalMessage, usage, worktreePath, exitCode, agentId, claimedBy,
            dreamDetails);
    }


    public TaskState withProgressSummary(String progressSummary) {
        return new TaskState(id, type, status, description, toolUseId, startTime, endTime, notified,
            Optional.ofNullable(progressSummary), errorMessage, finalMessage, usage, worktreePath, exitCode, agentId, claimedBy,
            dreamDetails);
    }


    public TaskState withErrorMessage(String errorMessage) {
        return new TaskState(id, type, status, description, toolUseId, startTime, endTime, notified,
            progressSummary, Optional.ofNullable(errorMessage), finalMessage, usage, worktreePath, exitCode, agentId, claimedBy,
            dreamDetails);
    }


    public TaskState withFinalMessage(String finalMessage) {
        return new TaskState(id, type, status, description, toolUseId, startTime, endTime, notified,
            progressSummary, errorMessage, Optional.ofNullable(finalMessage), usage, worktreePath, exitCode, agentId, claimedBy,
            dreamDetails);
    }

    /** Agent aggregated usage — surfaced as the {@code <usage>} section. */
    public TaskState withUsage(TaskUsage usage) {
        return new TaskState(id, type, status, description, toolUseId, startTime, endTime, notified,
            progressSummary, errorMessage, finalMessage, Optional.ofNullable(usage), worktreePath, exitCode, agentId, claimedBy,
            dreamDetails);
    }

    /** Agent worktree path — surfaced as the {@code <worktree>} section. */
    public TaskState withWorktreePath(String worktreePath) {
        return new TaskState(id, type, status, description, toolUseId, startTime, endTime, notified,
            progressSummary, errorMessage, finalMessage, usage, Optional.ofNullable(worktreePath), exitCode, agentId, claimedBy,
            dreamDetails);
    }

    /** Background bash exit code — appended to the completion summary. */
    public TaskState withExitCode(Integer exitCode) {
        return new TaskState(id, type, status, description, toolUseId, startTime, endTime, notified,
            progressSummary, errorMessage, finalMessage, usage, worktreePath, Optional.ofNullable(exitCode), agentId, claimedBy,
            dreamDetails);
    }

    /** Re-targets the owning agent (used when a background task is started inside a sub-agent). */
    public TaskState withAgentId(String agentId) {
        return new TaskState(id, type, status, description, toolUseId, startTime, endTime, notified,
            progressSummary, errorMessage, finalMessage, usage, worktreePath, exitCode, Optional.ofNullable(agentId), claimedBy,
            dreamDetails);
    }

    /** Marks the task claimed by {@code agentId}, or unclaimed when {@code null}. */
    public TaskState withClaimedBy(String agentId) {
        return new TaskState(id, type, status, description, toolUseId, startTime, endTime, notified,
            progressSummary, errorMessage, finalMessage, usage, worktreePath, exitCode, this.agentId, Optional.ofNullable(agentId),
            dreamDetails);
    }

    public TaskState withDreamDetails(DreamTaskDetails details) {
        return new TaskState(id, type, status, description, toolUseId, startTime, endTime, notified,
            progressSummary, errorMessage, finalMessage, usage, worktreePath, exitCode, agentId, claimedBy,
            Optional.ofNullable(details));
    }
}

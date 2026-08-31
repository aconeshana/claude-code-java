package com.claudecode.tools.tasks;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Status of a to-do list {@link Task} (TaskCreate/TaskGet/TaskList/TaskUpdate
 * tools). Named {@code TodoStatus} rather than {@code TaskStatus} because that
 * name is already taken by the unrelated background-task lifecycle enum
 * ({@link TaskStatus}: PENDING/RUNNING/COMPLETED/FAILED/KILLED) — the two
 * systems share the word "task" but not a type.
 * <p>
 * {@code deleted} is intentionally absent: it is not a resting state but a
 * TaskUpdate action that removes the task outright (see
 * {@code TaskUpdateTool}), so it never appears as a stored {@link Task#status}.
 */
public enum TodoStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static TodoStatus fromWireValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Task status is required");
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "pending" -> PENDING;
            case "in_progress" -> IN_PROGRESS;
            case "completed" -> COMPLETED;
            default -> throw new IllegalArgumentException("Unknown task status: " + value);
        };
    }
}

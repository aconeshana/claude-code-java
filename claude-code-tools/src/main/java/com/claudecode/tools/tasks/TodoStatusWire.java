package com.claudecode.tools.tasks;


final class TodoStatusWire {

    private TodoStatusWire() {}

    static String toWire(TodoStatus status) {
        return switch (status) {
            case PENDING -> "pending";
            case IN_PROGRESS -> "in_progress";
            case COMPLETED -> "completed";
        };
    }

    /** Returns {@code null} if {@code wire} is not one of the three resting-state strings. */
    static TodoStatus fromWire(String wire) {
        return switch (wire) {
            case "pending" -> TodoStatus.PENDING;
            case "in_progress" -> TodoStatus.IN_PROGRESS;
            case "completed" -> TodoStatus.COMPLETED;
            default -> null;
        };
    }
}

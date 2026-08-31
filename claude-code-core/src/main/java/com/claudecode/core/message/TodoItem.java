package com.claudecode.core.message;

/**
 * One entry in the {@code todo_reminder} attachment payload.
 */
public record TodoItem(String status, String content) {
}

package com.claudecode.core.message;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Nudge to use the TodoWrite tool, listing the current todos.
 */
public record TodoReminderAttachment(
    @JsonProperty("content") List<TodoItem> content,
    @JsonProperty("itemCount") int itemCount
) implements AttachmentPayload {

    @JsonCreator
    public TodoReminderAttachment {
    }
}

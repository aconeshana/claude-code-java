package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Nudge to use TaskCreate/TaskUpdate, carrying the current persistent tasks. */
public record TaskReminderAttachment(
    @JsonProperty("content") List<TaskReminderItem> content,
    @JsonProperty("itemCount") int itemCount
) implements AttachmentPayload {

    @JsonCreator
    public TaskReminderAttachment {
        content = List.copyOf(content == null ? List.of() : content);
    }
}

package com.claudecode.core.message;

import org.apache.commons.lang3.StringUtils;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Full or sparse autonomous-execution reminder.
 */
public record AutoModeReminderAttachment(
    @JsonProperty("reminderType") String reminderType
) implements AttachmentPayload {

    @JsonCreator
    public AutoModeReminderAttachment {
        if (StringUtils.isBlank(reminderType)) reminderType = "full";
    }
}

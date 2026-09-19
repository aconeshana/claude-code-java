package com.claudecode.core.message;

import org.apache.commons.lang3.StringUtils;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Announces that {@code ultracode} effort is active. The first one after it is switched on
 * carries the full instructions; later ones restate them briefly.
 */
public record UltraEffortEnterAttachment(
    @JsonProperty("reminderType") String reminderType
) implements AttachmentPayload {

    @JsonCreator
    public UltraEffortEnterAttachment {
        if (StringUtils.isBlank(reminderType)) reminderType = "full";
    }
}

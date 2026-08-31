package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * USD budget usage reminder.
 */
public record BudgetUsdAttachment(
    @JsonProperty("used") double used,
    @JsonProperty("total") double total,
    @JsonProperty("remaining") double remaining
) implements AttachmentPayload {

    @JsonCreator
    public BudgetUsdAttachment {
    }
}

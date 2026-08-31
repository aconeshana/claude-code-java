package com.claudecode.core.message;

import com.claudecode.core.plan.PlanHistoryEntry;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.apache.commons.lang3.StringUtils;

/**
 * Reminds the model that plan mode is active.
 */
public record PlanModeReminderAttachment(
    @JsonProperty("reminderType") String reminderType,
    @JsonProperty("isSubAgent") boolean isSubAgent,
    @JsonProperty("planFilePath") String planFilePath,
    @JsonProperty("planExists") boolean planExists,
    @JsonProperty("planId") @JsonInclude(JsonInclude.Include.NON_NULL) String planId,
    @JsonProperty("planStatus") @JsonInclude(JsonInclude.Include.NON_NULL) String planStatus,
    @JsonProperty("resumedDraft") @JsonInclude(JsonInclude.Include.NON_NULL) Boolean resumedDraft,
    @JsonProperty("recentPlans") @JsonInclude(JsonInclude.Include.NON_NULL)
    List<PlanHistoryEntry> recentPlans
) implements AttachmentPayload {

    @JsonCreator
    public PlanModeReminderAttachment {
        if (StringUtils.isBlank(reminderType)) reminderType = "full";
        recentPlans = recentPlans == null ? null : List.copyOf(recentPlans);
    }

    public PlanModeReminderAttachment(
            String reminderType, boolean isSubAgent, String planFilePath, boolean planExists) {
        this(reminderType, isSubAgent, planFilePath, planExists,
            null, null, null, null);
    }

    public PlanModeReminderAttachment(boolean isSubAgent, String planFilePath, boolean planExists) {
        this("full", isSubAgent, planFilePath, planExists,
            null, null, null, null);
    }
}

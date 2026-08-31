package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnore;


@JsonInclude(JsonInclude.Include.NON_NULL)
public record GoalStatusAttachment(
    boolean met,
    Boolean sentinel,
    String condition,
    Boolean failed,
    String reason,
    Integer iterations,
    Long durationMs,
    Long tokens
) implements AttachmentPayload {

    public static GoalStatusAttachment sentinel(boolean met, String condition) {
        return new GoalStatusAttachment(met, true, condition, null, null,
            null, null, null);
    }

    public static GoalStatusAttachment pending(String condition, String reason) {
        return new GoalStatusAttachment(false, null, condition, null, reason,
            null, null, null);
    }

    public static GoalStatusAttachment achieved(String condition, String reason,
                                                 int iterations, long durationMs,
                                                 long tokens) {
        return new GoalStatusAttachment(true, null, condition, null, reason,
            iterations, durationMs, tokens);
    }

    public static GoalStatusAttachment failed(String condition, String reason,
                                               int iterations, long durationMs,
                                               long tokens) {
        return new GoalStatusAttachment(false, null, condition, true, reason,
            iterations, durationMs, tokens);
    }

    @JsonIgnore
    public boolean hasSentinelMarker() {
        return Boolean.TRUE.equals(sentinel);
    }

    @JsonIgnore
    public boolean hasFailedMarker() {
        return Boolean.TRUE.equals(failed);
    }
}

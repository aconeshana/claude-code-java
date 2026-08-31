package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * A system message in the conversation.
 */
public record SystemMessage(
    @JsonProperty("uuid") String uuid,
    @JsonProperty("subtype") String subtype,
    @JsonProperty("level") String level,
    @JsonProperty("content") String content,
    @JsonProperty("parentUuid") String parentUuidValue,
    @JsonProperty("timestamp") Instant timestampValue,
    @JsonProperty("compactMetadata") CompactMetadata compactMetadata,
    @JsonProperty("durationMs") @JsonInclude(JsonInclude.Include.NON_NULL) Long durationMs,
    @JsonProperty("messageCount") @JsonInclude(JsonInclude.Include.NON_NULL) Integer messageCount,
    /** Background subagents still running when this turn ended; absent, never 0. */
    @JsonProperty("pendingBackgroundAgentCount") @JsonInclude(JsonInclude.Include.NON_NULL)
    Integer pendingBackgroundAgentCount,
    /** Dynamic workflows still running when this turn ended; absent, never 0. */
    @JsonProperty("pendingWorkflowCount") @JsonInclude(JsonInclude.Include.NON_NULL)
    Integer pendingWorkflowCount,
    @JsonProperty("retractedMessageUuids") @JsonInclude(JsonInclude.Include.NON_NULL)
    List<String> retractedMessageUuids,
    @JsonProperty("refusedUserMessageUuid") @JsonInclude(JsonInclude.Include.NON_NULL)
    String refusedUserMessageUuid,
    @JsonProperty("direction") @JsonInclude(JsonInclude.Include.NON_NULL) String direction,
    @JsonProperty("trigger") @JsonInclude(JsonInclude.Include.NON_NULL) String trigger,
    @JsonProperty("originalModel") @JsonInclude(JsonInclude.Include.NON_NULL) String originalModel,
    @JsonProperty("fallbackModel") @JsonInclude(JsonInclude.Include.NON_NULL) String fallbackModel,
    @JsonProperty("requestId") @JsonInclude(JsonInclude.Include.NON_NULL) String requestId,
    @JsonProperty("apiRefusalCategory") @JsonInclude(JsonInclude.Include.NON_NULL)
    String apiRefusalCategory,
    @JsonProperty("apiRefusalExplanation") @JsonInclude(JsonInclude.Include.NON_NULL)
    String apiRefusalExplanation,
    @JsonProperty("budgetTokens") @JsonInclude(JsonInclude.Include.NON_NULL) Long budgetTokens,
    @JsonProperty("budgetLimit") @JsonInclude(JsonInclude.Include.NON_NULL) Long budgetLimit,
    @JsonProperty("budgetNudges") @JsonInclude(JsonInclude.Include.NON_NULL) Integer budgetNudges,
    @JsonProperty("briefHiddenCount") @JsonInclude(JsonInclude.Include.NON_NULL) Integer briefHiddenCount
) implements Message {

    @JsonCreator
    public SystemMessage {

        // so an announcement that retracted nothing is not the same as a row
        // that carries no list at all.
        retractedMessageUuids = retractedMessageUuids == null
            ? null : List.copyOf(retractedMessageUuids);
    }

    /** Backward-compatible canonical shape from before turn budget/brief metadata. */
    public SystemMessage(String uuid, String subtype, String level, String content,
                         String parentUuidValue, Instant timestampValue,
                         CompactMetadata compactMetadata, Long durationMs,
                         Integer messageCount, Integer pendingBackgroundAgentCount,
                         Integer pendingWorkflowCount, List<String> retractedMessageUuids,
                         String refusedUserMessageUuid, String direction, String trigger,
                         String originalModel, String fallbackModel, String requestId,
                         String apiRefusalCategory, String apiRefusalExplanation) {
        this(uuid, subtype, level, content, parentUuidValue, timestampValue,
            compactMetadata, durationMs, messageCount, pendingBackgroundAgentCount,
            pendingWorkflowCount, retractedMessageUuids, refusedUserMessageUuid,
            direction, trigger, originalModel, fallbackModel, requestId,
            apiRefusalCategory, apiRefusalExplanation, null, null, null, null);
    }

    /** Backward-compatible constructor from before refusal diagnostic metadata. */
    public SystemMessage(String uuid, String subtype, String level, String content,
                         String parentUuidValue, Instant timestampValue,
                         CompactMetadata compactMetadata, Long durationMs,
                         Integer messageCount, List<String> retractedMessageUuids,
                         String refusedUserMessageUuid) {
        this(uuid, subtype, level, content, parentUuidValue, timestampValue,
            compactMetadata, durationMs, messageCount, null, null, retractedMessageUuids,
            refusedUserMessageUuid, null, null, null, null, null, null, null,
            null, null, null, null);
    }

    /**
     * {@code turn_duration} row carrying the pending background-work counts.
     */
    public static SystemMessage turnDuration(String uuid, Instant timestampValue,
                                             Long durationMs, Integer messageCount,
                                             Integer pendingBackgroundAgentCount,
                                             Integer pendingWorkflowCount) {
        return turnDuration(uuid, timestampValue, durationMs, messageCount,
            pendingBackgroundAgentCount, pendingWorkflowCount, null, null, null, null);
    }

    public static SystemMessage turnDuration(String uuid, Instant timestampValue,
                                             Long durationMs, Integer messageCount,
                                             Integer pendingBackgroundAgentCount,
                                             Integer pendingWorkflowCount,
                                             Long budgetTokens, Long budgetLimit,
                                             Integer budgetNudges, Integer briefHiddenCount) {
        return new SystemMessage(uuid, "turn_duration", null, null, null, timestampValue,
            null, durationMs, messageCount, pendingBackgroundAgentCount,
            pendingWorkflowCount, null, null, null, null, null, null, null, null, null,
            budgetTokens, budgetLimit, budgetNudges, briefHiddenCount);
    }

    /**
     * Convenience constructor.
     */
    public SystemMessage(String uuid, String subtype, String level, String content) {
        this(uuid, subtype, level, content, null, Instant.now(), null, null, null, null, null);
    }

    /** Backward-compatible full constructor for non-duration system messages. */
    public SystemMessage(String uuid, String subtype, String level, String content,
                         String parentUuidValue, Instant timestampValue,
                         CompactMetadata compactMetadata) {
        this(uuid, subtype, level, content, parentUuidValue, timestampValue,
            compactMetadata, null, null, null, null);
    }

    /** Backward-compatible constructor for rows that carry no retraction list. */
    public SystemMessage(String uuid, String subtype, String level, String content,
                         String parentUuidValue, Instant timestampValue,
                         CompactMetadata compactMetadata, Long durationMs, Integer messageCount) {
        this(uuid, subtype, level, content, parentUuidValue, timestampValue,
            compactMetadata, durationMs, messageCount, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null);
    }

    /** Backward-compatible constructor for retractions without a refused turn. */
    public SystemMessage(String uuid, String subtype, String level, String content,
                         String parentUuidValue, Instant timestampValue,
                         CompactMetadata compactMetadata, Long durationMs, Integer messageCount,
                         List<String> retractedMessageUuids) {
        this(uuid, subtype, level, content, parentUuidValue, timestampValue,
            compactMetadata, durationMs, messageCount, retractedMessageUuids, null);
    }

    @Override
    public String type() {
        return "system";
    }

    @Override
    public Optional<String> parentUuid() {
        return Optional.ofNullable(parentUuidValue);
    }

    @Override
    public Optional<Instant> timestamp() {
        return Optional.ofNullable(timestampValue);
    }
}

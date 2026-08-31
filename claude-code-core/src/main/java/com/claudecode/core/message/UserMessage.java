package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A user message in the conversation.
 */
public record UserMessage(
    @JsonProperty("uuid") String uuid,
    @JsonProperty("message") MessageContent message,
    @JsonProperty("isMeta") boolean isMeta,
    @JsonProperty("isCompactSummary") boolean isCompactSummary,
    @JsonProperty("toolUseResult") Object toolUseResult,
    @JsonProperty("origin") MessageOrigin origin,
    @JsonProperty("parentUuid") String parentUuidValue,
    @JsonProperty("timestamp") Instant timestampValue,
    @JsonProperty("imagePasteIds") @JsonInclude(JsonInclude.Include.NON_NULL) List<Integer> imagePasteIds,
/**
     * Permission mode active when the message was submitted — stored for rewind restore.
     */
    @JsonProperty("permissionMode") @JsonInclude(JsonInclude.Include.NON_NULL) String permissionMode,
/**
     * Logical session id — forked sessions inherit the parent's sessionId so {@code /resume} can group
     * forks.
     */
    @JsonProperty("sessionId") @JsonInclude(JsonInclude.Include.NON_NULL) String sessionIdValue,
/**
     * Assistant uuid that produced this tool result, when the user message wraps a tool result spawned
     * by a sub-agent/tool.
     */
    @JsonProperty("sourceToolAssistantUUID") @JsonInclude(JsonInclude.Include.NON_NULL) String sourceToolAssistantUUID,
/**
     * Tool-use id that injected this transient user message.
     */
    @JsonProperty("sourceToolUseID") @JsonInclude(JsonInclude.Include.NON_NULL) String sourceToolUseID,
    @JsonProperty("isVirtual") @JsonInclude(JsonInclude.Include.NON_NULL) Boolean isVirtual,
    @JsonProperty("mcpMeta") @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> mcpMeta,
    @JsonProperty("isVisibleInTranscriptOnly") @JsonInclude(JsonInclude.Include.NON_NULL) Boolean isVisibleInTranscriptOnly,

    @JsonProperty("planContent") @JsonInclude(JsonInclude.Include.NON_NULL) String planContent,
    @JsonProperty("summarizeMetadata") @JsonInclude(JsonInclude.Include.NON_NULL)
    SummarizeMetadata summarizeMetadata
) implements Message {

    @JsonCreator
    public UserMessage {
    }

    /** Convenience constructor for simple user messages. */
    public UserMessage(String uuid, MessageContent message) {
        this(uuid, message, false, false, null, MessageOrigin.USER, null, Instant.now(), null, null, null, null, null,
            null, null, null, null);
    }

    /** Convenience constructor carrying pasted-image chip ids. */
    public UserMessage(String uuid, MessageContent message, List<Integer> imagePasteIds) {
        this(uuid, message, false, false, null, MessageOrigin.USER, null, Instant.now(), imagePasteIds, null, null, null, null,
            null, null, null, null);
    }

    /** Backward-compatible 10-arg constructor (pre-sessionId/sourceToolAssistantUUID). Delegates with nulls. */
    public UserMessage(String uuid, MessageContent message, boolean isMeta, boolean isCompactSummary,
                       Object toolUseResult, MessageOrigin origin, String parentUuidValue,
                       Instant timestampValue, List<Integer> imagePasteIds, String permissionMode) {
        this(uuid, message, isMeta, isCompactSummary, toolUseResult, origin,
             parentUuidValue, timestampValue, imagePasteIds, permissionMode, null, null, null,
             null, null, null, null);
    }

    /** Backward-compatible 11-arg constructor (pre-sourceToolAssistantUUID). Delegates with null. */
    public UserMessage(String uuid, MessageContent message, boolean isMeta, boolean isCompactSummary,
                       Object toolUseResult, MessageOrigin origin, String parentUuidValue,
                       Instant timestampValue, List<Integer> imagePasteIds, String permissionMode,
                       String sessionIdValue) {
        this(uuid, message, isMeta, isCompactSummary, toolUseResult, origin,
             parentUuidValue, timestampValue, imagePasteIds, permissionMode, sessionIdValue, null, null,
             null, null, null, null);
    }

    /** Backward-compatible 12-arg constructor (pre-sourceToolUseID). */
    public UserMessage(String uuid, MessageContent message, boolean isMeta, boolean isCompactSummary,
                       Object toolUseResult, MessageOrigin origin, String parentUuidValue,
                       Instant timestampValue, List<Integer> imagePasteIds, String permissionMode,
                       String sessionIdValue, String sourceToolAssistantUUID) {
        this(uuid, message, isMeta, isCompactSummary, toolUseResult, origin,
             parentUuidValue, timestampValue, imagePasteIds, permissionMode,
             sessionIdValue, sourceToolAssistantUUID, null,
             null, null, null, null);
    }

    /** Backward-compatible 13-arg constructor (pre-isVirtual/mcpMeta/isVisibleInTranscriptOnly). */
    public UserMessage(String uuid, MessageContent message, boolean isMeta, boolean isCompactSummary,
                       Object toolUseResult, MessageOrigin origin, String parentUuidValue,
                       Instant timestampValue, List<Integer> imagePasteIds, String permissionMode,
                       String sessionIdValue, String sourceToolAssistantUUID, String sourceToolUseID) {
        this(uuid, message, isMeta, isCompactSummary, toolUseResult, origin,
             parentUuidValue, timestampValue, imagePasteIds, permissionMode,
             sessionIdValue, sourceToolAssistantUUID, sourceToolUseID,
             null, null, null, null);
    }

    /** Tool-result convenience constructor carrying MCP envelope metadata. */
    public UserMessage(String uuid, MessageContent message, boolean isMeta, boolean isCompactSummary,
                       Object toolUseResult, MessageOrigin origin, String parentUuidValue,
                       Instant timestampValue, List<Integer> imagePasteIds, String permissionMode,
                       String sessionIdValue, String sourceToolAssistantUUID, String sourceToolUseID,
                       Map<String, Object> mcpMeta) {
        this(uuid, message, isMeta, isCompactSummary, toolUseResult, origin,
             parentUuidValue, timestampValue, imagePasteIds, permissionMode,
             sessionIdValue, sourceToolAssistantUUID, sourceToolUseID,
             null, mcpMeta, null, null);
    }

    /** Backward-compatible canonical signature predating planContent. */
    public UserMessage(String uuid, MessageContent message, boolean isMeta, boolean isCompactSummary,
                       Object toolUseResult, MessageOrigin origin, String parentUuidValue,
                       Instant timestampValue, List<Integer> imagePasteIds, String permissionMode,
                       String sessionIdValue, String sourceToolAssistantUUID, String sourceToolUseID,
                       Boolean isVirtual, Map<String, Object> mcpMeta,
                       Boolean isVisibleInTranscriptOnly) {
        this(uuid, message, isMeta, isCompactSummary, toolUseResult, origin,
            parentUuidValue, timestampValue, imagePasteIds, permissionMode,
            sessionIdValue, sourceToolAssistantUUID, sourceToolUseID,
            isVirtual, mcpMeta, isVisibleInTranscriptOnly, null, null);
    }

    /** Backward-compatible canonical signature predating summarizeMetadata. */
    public UserMessage(String uuid, MessageContent message, boolean isMeta, boolean isCompactSummary,
                       Object toolUseResult, MessageOrigin origin, String parentUuidValue,
                       Instant timestampValue, List<Integer> imagePasteIds, String permissionMode,
                       String sessionIdValue, String sourceToolAssistantUUID, String sourceToolUseID,
                       Boolean isVirtual, Map<String, Object> mcpMeta,
                       Boolean isVisibleInTranscriptOnly, String planContent) {
        this(uuid, message, isMeta, isCompactSummary, toolUseResult, origin,
            parentUuidValue, timestampValue, imagePasteIds, permissionMode,
            sessionIdValue, sourceToolAssistantUUID, sourceToolUseID,
            isVirtual, mcpMeta, isVisibleInTranscriptOnly, planContent, null);
    }

    @Override
    public String type() {
        return "user";
    }

    @Override
    public Optional<String> parentUuid() {
        return Optional.ofNullable(parentUuidValue);
    }

    @Override
    public Optional<Instant> timestamp() {
        return Optional.ofNullable(timestampValue);
    }

    @Override
    public Optional<String> sessionId() {
        return Optional.ofNullable(sessionIdValue);
    }
}

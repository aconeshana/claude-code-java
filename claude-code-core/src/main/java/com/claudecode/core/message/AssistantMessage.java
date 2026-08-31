package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Optional;

/**
 * An assistant message in the conversation.
 */
public record AssistantMessage(
    @JsonProperty("uuid") String uuid,
    @JsonProperty("message") AssistantContent message,
    @JsonProperty("isApiErrorMessage") boolean isApiErrorMessage,
    @JsonProperty("parentUuid") String parentUuidValue,
    @JsonProperty("timestamp") Instant timestampValue,
    @JsonProperty("attributionSkill") @JsonInclude(JsonInclude.Include.NON_NULL) String attributionSkill,
    @JsonProperty("attributionPlugin") @JsonInclude(JsonInclude.Include.NON_NULL) String attributionPlugin,
    @JsonProperty("attributionMcpServer") @JsonInclude(JsonInclude.Include.NON_NULL) String attributionMcpServer,
    @JsonProperty("attributionMcpTool") @JsonInclude(JsonInclude.Include.NON_NULL) String attributionMcpTool,
    @JsonProperty("apiError") @JsonInclude(JsonInclude.Include.NON_NULL) String apiError,
    @JsonProperty("error") @JsonInclude(JsonInclude.Include.NON_NULL) String error,
    @JsonProperty("isVirtual") @JsonInclude(JsonInclude.Include.NON_NULL) Boolean isVirtual,
    @JsonProperty("requestId") @JsonInclude(JsonInclude.Include.NON_NULL) String requestId,
    @JsonProperty("advisorModel") @JsonInclude(JsonInclude.Include.NON_NULL) String advisorModel,
    @JsonProperty("isMeta") @JsonInclude(JsonInclude.Include.NON_NULL) Boolean isMeta
) implements Message {

    @JsonCreator
    public AssistantMessage {
    }

    /**
     * Convenience constructor.
     */
    public AssistantMessage(String uuid, AssistantContent message) {
        this(uuid, message, false, null, Instant.now(), null, null, null, null, null, null,
            null, null, null, null);
    }

    /** Backward-compatible 5-arg constructor (pre-Skill attribution). */
    public AssistantMessage(String uuid, AssistantContent message, boolean isApiErrorMessage,
                            String parentUuidValue, Instant timestampValue) {
        this(uuid, message, isApiErrorMessage, parentUuidValue, timestampValue,
            null, null, null, null, null, null, null, null, null, null);
    }

    /** Backward-compatible 7-arg constructor (pre-MCP attribution). */
    public AssistantMessage(String uuid, AssistantContent message, boolean isApiErrorMessage,
                            String parentUuidValue, Instant timestampValue,
                            String attributionSkill, String attributionPlugin) {
        this(uuid, message, isApiErrorMessage, parentUuidValue, timestampValue,
            attributionSkill, attributionPlugin, null, null, null, null, null, null, null, null);
    }

    /** Backward-compatible 9-arg constructor (pre-API-error metadata). */
    public AssistantMessage(String uuid, AssistantContent message, boolean isApiErrorMessage,
                            String parentUuidValue, Instant timestampValue,
                            String attributionSkill, String attributionPlugin,
                            String attributionMcpServer, String attributionMcpTool) {
        this(uuid, message, isApiErrorMessage, parentUuidValue, timestampValue,
            attributionSkill, attributionPlugin, attributionMcpServer, attributionMcpTool,
            null, null, null, null, null, null);
    }

    /** Backward-compatible 11-arg constructor (pre-isVirtual/requestId/advisorModel/isMeta). */
    public AssistantMessage(String uuid, AssistantContent message, boolean isApiErrorMessage,
                            String parentUuidValue, Instant timestampValue,
                            String attributionSkill, String attributionPlugin,
                            String attributionMcpServer, String attributionMcpTool,
                            String apiError, String error) {
        this(uuid, message, isApiErrorMessage, parentUuidValue, timestampValue,
            attributionSkill, attributionPlugin, attributionMcpServer, attributionMcpTool,
            apiError, error, null, null, null, null);
    }

    @Override
    public String type() {
        return "assistant";
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

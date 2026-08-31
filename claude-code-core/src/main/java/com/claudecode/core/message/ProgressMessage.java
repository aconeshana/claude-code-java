package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * A progress message indicating ongoing work.
 */
public record ProgressMessage(
    @JsonProperty("uuid") String uuid,
    @JsonProperty("content") String content,
    @JsonProperty("parentUuid") String parentUuidValue,
    @JsonProperty("timestamp") Instant timestampValue,
    @JsonProperty("toolUseId") String toolUseId,
    @JsonProperty("parentToolUseId") String parentToolUseId,
    @JsonProperty("data") ProgressData data
) implements Message {

    @JsonCreator
    public ProgressMessage {
    }

    public ProgressMessage(String uuid, String content) {
        this(uuid, content, null, Instant.now(), null, null, null);
    }

    public ProgressMessage(String uuid, String content, String parentUuidValue, Instant timestampValue) {
        this(uuid, content, parentUuidValue, timestampValue, null, null, null);
    }

    @Override
    public String type() {
        return "progress";
    }

    @Override
    public Optional<String> parentUuid() {
        return Optional.ofNullable(parentUuidValue);
    }

    @Override
    public Optional<Instant> timestamp() {
        return Optional.ofNullable(timestampValue);
    }


    public record ProgressData(
        @JsonProperty("type") String type,
        @JsonProperty("output") String output,
        @JsonProperty("fullOutput") String fullOutput,
        @JsonProperty("elapsedTimeSeconds") Double elapsedTimeSeconds,
        @JsonProperty("totalLines") Long totalLines,
        @JsonProperty("totalBytes") Long totalBytes,
        @JsonProperty("timeoutMs") Long timeoutMs,
        @JsonProperty("taskId") String taskId,
        @JsonProperty("isIncomplete") Boolean isIncomplete,
        @JsonProperty("message") Message message,
        @JsonProperty("prompt") String prompt,
        @JsonProperty("agentId") String agentId,
        @JsonProperty("progress") Double progress,
        @JsonProperty("total") Double total,
        @JsonProperty("progressMessage") String progressMessage,
        @JsonProperty("query") String query,
        @JsonProperty("resultCount") Long resultCount,
        @JsonProperty("resolvedModel") String resolvedModel
    ) {
        /** Backward-compatible constructor for the pre-Web/MCP raw progress union. */
        public ProgressData(String type, String output, String fullOutput,
                Double elapsedTimeSeconds, Long totalLines, Long totalBytes,
                Long timeoutMs, String taskId, Boolean isIncomplete,
                Message message, String prompt, String agentId) {
            this(type, output, fullOutput, elapsedTimeSeconds, totalLines, totalBytes,
                timeoutMs, taskId, isIncomplete, message, prompt, agentId,
                null, null, null, null, null, null);
        }

        /** Backward-compatible constructor for the pre-resolvedModel progress union. */
        public ProgressData(String type, String output, String fullOutput,
                Double elapsedTimeSeconds, Long totalLines, Long totalBytes,
                Long timeoutMs, String taskId, Boolean isIncomplete,
                Message message, String prompt, String agentId,
                Double progress, Double total, String progressMessage,
                String query, Long resultCount) {
            this(type, output, fullOutput, elapsedTimeSeconds, totalLines, totalBytes,
                timeoutMs, taskId, isIncomplete, message, prompt, agentId,
                progress, total, progressMessage, query, resultCount, null);
        }

        /** Convenience: is this an ephemeral tool-progress tick (bash/powershell/mcp/sleep)? */
        public boolean isEphemeral() {
            return type != null && EPHEMERAL_TYPES.contains(type);
        }


        public static final Set<String> EPHEMERAL_TYPES = Set.of(
            "bash_progress", "powershell_progress", "mcp_progress", "sleep_progress");
    }
}

package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Anthropic assistant-message envelope retained by conversation messages.
 *
 * <ul>
 *   <li>preserve the {@code message_start}
 *       response envelope, replace its content per {@code content_block_stop},
 *       and apply final usage/stop fields from {@code message_delta}.</li>
 *   <li>the nested
 *       {@code message} object written verbatim to JSONL and restored by
 *       {@code --resume}/{@code --continue}, including the {@code stop_details}
 *       envelope that accompanies a {@code refusal} stop reason.</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AssistantContent(
    @JsonProperty("id") String id,
    @JsonProperty("content") List<ContentBlock> content,
    @JsonProperty("usage") @JsonInclude(JsonInclude.Include.NON_NULL) Usage usage,
    @JsonProperty("model") @JsonInclude(JsonInclude.Include.NON_NULL) String model,
    @JsonProperty("stop_reason") @JsonInclude(JsonInclude.Include.NON_NULL) String stopReason,
    @JsonProperty("stop_sequence") @JsonInclude(JsonInclude.Include.NON_NULL) String stopSequence,
    @JsonProperty("stop_details") @JsonInclude(JsonInclude.Include.NON_NULL) StopDetails stopDetails
) {

    @JsonCreator
    public AssistantContent {
    }

    /** Backward-compatible constructor for envelopes without refusal details. */
    public AssistantContent(String id, List<ContentBlock> content, Usage usage,
                            String model, String stopReason, String stopSequence) {
        this(id, content, usage, model, stopReason, stopSequence, null);
    }

    /** Backward-compatible constructor for content without API envelope metadata. */
    public AssistantContent(String id, List<ContentBlock> content, Usage usage) {
        this(id, content, usage, null, null, null, null);
    }
    /**
     * Creates assistant content with the given blocks.
     */
    public static AssistantContent of(List<ContentBlock> content) {
        return new AssistantContent(null, content, null, null, null, null, null);
    }

    /**
     * Creates assistant content with an API message ID and blocks.
     */
    public static AssistantContent of(String id, List<ContentBlock> content) {
        return new AssistantContent(id, content, null, null, null, null, null);
    }

    /** Creates assistant content with API id, blocks and reported token usage. */
    public static AssistantContent of(String id, List<ContentBlock> content, Usage usage) {
        return new AssistantContent(id, content, usage, null, null, null, null);
    }

    /** Creates the API-native envelope used for streamed assistant blocks. */
    public static AssistantContent apiResponse(String id, List<ContentBlock> content,
                                               Usage usage, String model,
                                               String stopReason, String stopSequence) {
        return new AssistantContent(id, content, usage, model, stopReason, stopSequence, null);
    }

    /** Creates the API-native envelope for a turn the model refused. */
    public static AssistantContent apiResponse(String id, List<ContentBlock> content,
                                               Usage usage, String model,
                                               String stopReason, String stopSequence,
                                               StopDetails stopDetails) {
        return new AssistantContent(id, content, usage, model, stopReason, stopSequence,
            stopDetails);
    }

    /** Applies the final delta without dropping the response model or content. */
    public AssistantContent withFinalDelta(Usage finalUsage,
                                           String finalStopReason,
                                           String finalStopSequence,
                                           StopDetails finalStopDetails) {
        return new AssistantContent(id, content,
            finalUsage != null ? finalUsage : usage,
            model,
            finalStopReason != null ? finalStopReason : stopReason,
            finalStopSequence != null ? finalStopSequence : stopSequence,
            finalStopDetails != null ? finalStopDetails : stopDetails);
    }
}

package com.claudecode.api;

import com.claudecode.core.message.StopDetails;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Message-level delta data (stop reason update).
 *
 * <p>{@code stop_details} rides along with a {@code refusal} stop reason; it is
 * {@code null} on every other turn.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MessageDeltaData(
        @JsonProperty("stop_reason") String stopReason,
        @JsonProperty("stop_sequence") String stopSequence,
        @JsonProperty("stop_details") StopDetails stopDetails
) {
    @JsonCreator
    public MessageDeltaData {
    }

    /** Delta for a turn that carries no refusal detail. */
    public MessageDeltaData(String stopReason, String stopSequence) {
        this(stopReason, stopSequence, null);
    }
}

package com.claudecode.core.metrics;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.message.Usage;

/**
 * One durable, versioned input to the whole-session HUD projection.
 * Nullable identity fields are absent for event kinds that do not use them;
 * token buckets are disjoint.
 */
@Explanation("Adds durable white-box telemetry for the built-in HUD")
public record SessionMetricsEvent(
    int schemaVersion,
    long seq,
    long time,
    String sessionId,
    Kind kind,
    String turnId,
    long turn,
    long step,
    String callId,
    long uncachedInputTokens,
    long outputTokens,
    long cacheWriteTokens,
    long cacheReadTokens,
    boolean synthetic
) {
    public static final int SCHEMA_VERSION = 1;
    public static final String TRANSCRIPT_TYPE = "java-session-metrics";

    public enum Kind {
        SESSION_START("session/start"),
        TURN_START("turn/start"),
        STEP_START("step/start"),
        ASSISTANT_FIRST_TOKEN("assistant/first-token"),
        ASSISTANT_USAGE("assistant/usage"),
        ASSISTANT_MESSAGE("assistant/message"),
        TOOL_CALL("tool/call"),
        TOOL_RESULT("tool/result"),
        STEP_END("step/end"),
        TURN_END("turn/end");

        private final String wireName;

        Kind(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public static Kind fromWireName(String value) {
            for (Kind kind : values()) {
                if (kind.wireName.equals(value)) return kind;
            }
            throw new IllegalArgumentException("Unknown session metrics event: " + value);
        }
    }

    public static SessionMetricsEvent usage(long seq, long time, String sessionId,
                                            String turnId, long turn, long step,
                                            Usage usage) {
        Usage value = usage != null ? usage : Usage.EMPTY;
        return new SessionMetricsEvent(SCHEMA_VERSION, seq, time, sessionId,
            Kind.ASSISTANT_USAGE, turnId, turn, step, null,
            Math.max(0, value.inputTokens()), Math.max(0, value.outputTokens()),
            Math.max(0, value.cacheCreationInputTokens()),
            Math.max(0, value.cacheReadInputTokens()), false);
    }
}

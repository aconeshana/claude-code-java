package com.claudecode.runtime.query;

import java.util.Map;

/** Immutable latency projection for one completed headless turn. */
public record HeadlessTurnMetrics(
    int turnNumber,
    long timeToSystemMessageMs,
    long timeToQueryStartMs,
    long timeToFirstResponseMs,
    long queryOverheadMs,
    int checkpointCount,
    String entrypoint,
    Map<String, Long> checkpoints
) {
    public HeadlessTurnMetrics {
        checkpoints = Map.copyOf(checkpoints != null ? checkpoints : Map.of());
    }
}

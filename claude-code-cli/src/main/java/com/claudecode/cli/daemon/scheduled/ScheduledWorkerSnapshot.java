package com.claudecode.cli.daemon.scheduled;

import java.util.Map;

/** Immutable status projection for the scheduled daemon worker. */
record ScheduledWorkerSnapshot(
    boolean acceptingTasks,
    int running,
    int queued,
    Map<String, Long> lastFiredAt
) {
    ScheduledWorkerSnapshot {
        lastFiredAt = Map.copyOf(lastFiredAt);
    }
}

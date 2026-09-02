package com.claudecode.runtime.query;

import org.apache.commons.lang3.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** In-memory profiler that retains only the currently active turn. */
public final class BoundedHeadlessTurnProfiler implements HeadlessTurnProfiler {

    private static final int MAX_CHECKPOINTS_PER_TURN = 32;

    private final LongSupplier clock;
    private final Consumer<HeadlessTurnMetrics> sink;
    private final String entrypoint;
    private int nextTurnNumber;
    private TurnState current;

    public BoundedHeadlessTurnProfiler(LongSupplier clock,
                                       Consumer<HeadlessTurnMetrics> sink,
                                       String entrypoint) {
        this.clock = clock;
        this.sink = sink;
        this.entrypoint = entrypoint;
    }

    @Override
    public synchronized void startTurn() {
        long startedAt = clock.getAsLong();
        current = new TurnState(nextTurnNumber++, startedAt,
            new LinkedHashMap<>(Map.of("turn_start", startedAt)));
    }

    @Override
    public synchronized void checkpoint(String name) {
        if (current == null || name == null || StringUtils.isBlank(name)) return;
        if (current.checkpoints().size() >= MAX_CHECKPOINTS_PER_TURN
                && !current.checkpoints().containsKey(name)) return;
        current.checkpoints().putIfAbsent(name, clock.getAsLong());
    }

    @Override
    public void finishTurn() {
        HeadlessTurnMetrics metrics;
        synchronized (this) {
            if (current == null) return;
            TurnState completed = current;
            current = null;
            Map<String, Long> marks = Map.copyOf(completed.checkpoints());
            metrics = new HeadlessTurnMetrics(
                completed.turnNumber(),
                elapsed(completed, marks.get("system_message_yielded")),
                elapsed(completed, marks.get("query_started")),
                elapsed(completed, marks.get("first_chunk")),
                difference(marks.get("query_started"), marks.get("api_request_sent")),
                marks.size(), entrypoint, marks);
        }
        sink.accept(metrics);
    }

    private static long elapsed(TurnState state, Long timestamp) {
        return timestamp == null ? -1L : Math.max(0L, timestamp - state.startedAt());
    }

    private static long difference(Long start, Long end) {
        return start == null || end == null ? -1L : Math.max(0L, end - start);
    }

    private record TurnState(int turnNumber, long startedAt,
                             Map<String, Long> checkpoints) {
    }
}

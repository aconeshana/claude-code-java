package com.claudecode.runtime.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class BoundedHeadlessTurnProfilerTest {

    @Test
    void emitsOneBoundedSnapshotPerTurnAndClearsPreviousMarks() {
        AtomicLong clock = new AtomicLong(1_000L);
        List<HeadlessTurnMetrics> emitted = new ArrayList<>();
        BoundedHeadlessTurnProfiler profiler = new BoundedHeadlessTurnProfiler(
            clock::get, emitted::add, "sdk-cli");

        profiler.startTurn();
        clock.set(1_010L);
        profiler.checkpoint("query_started");
        clock.set(1_025L);
        profiler.checkpoint("api_request_sent");
        clock.set(1_060L);
        profiler.checkpoint("first_chunk");
        profiler.finishTurn();

        clock.set(2_000L);
        profiler.startTurn();
        clock.set(2_020L);
        profiler.checkpoint("query_started");
        profiler.finishTurn();

        assertEquals(2, emitted.size());
        assertEquals(0, emitted.getFirst().turnNumber());
        assertEquals(10L, emitted.getFirst().timeToQueryStartMs());
        assertEquals(15L, emitted.getFirst().queryOverheadMs());
        assertEquals(60L, emitted.getFirst().timeToFirstResponseMs());
        assertEquals(4, emitted.getFirst().checkpointCount());
        assertEquals("sdk-cli", emitted.getFirst().entrypoint());

        HeadlessTurnMetrics second = emitted.getLast();
        assertEquals(1, second.turnNumber());
        assertEquals(20L, second.timeToQueryStartMs());
        assertEquals(-1L, second.queryOverheadMs());
        assertEquals(-1L, second.timeToFirstResponseMs());
        assertEquals(2, second.checkpointCount());
        assertFalse(second.checkpoints().containsKey("first_chunk"));
    }

    @Test
    void repeatedCheckpointKeepsTheFirstTimestampAndFinishWithoutTurnIsNoop() {
        AtomicLong clock = new AtomicLong(100L);
        List<HeadlessTurnMetrics> emitted = new ArrayList<>();
        BoundedHeadlessTurnProfiler profiler = new BoundedHeadlessTurnProfiler(
            clock::get, emitted::add, null);

        profiler.finishTurn();
        profiler.startTurn();
        clock.set(110L);
        profiler.checkpoint("first_chunk");
        clock.set(150L);
        profiler.checkpoint("first_chunk");
        profiler.finishTurn();
        profiler.finishTurn();

        assertEquals(1, emitted.size());
        assertEquals(10L, emitted.getFirst().timeToFirstResponseMs());
        assertEquals(2, emitted.getFirst().checkpointCount());
    }
}

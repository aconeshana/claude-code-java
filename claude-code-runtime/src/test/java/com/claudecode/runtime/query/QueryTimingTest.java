package com.claudecode.runtime.query;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QueryTimingTest {

    @Test
    void capturesFirstRequestStreamAndOutputMilestonesRelativeToQueryStart() {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        QueryTiming timing = new QueryTiming(now::get);

        timing.reset();
        now.addAndGet(51_000_000L);
        timing.markRequestStarted();
        now.addAndGet(8_000_000L);
        timing.markStreamEvent();
        now.addAndGet(1_000_000L);
        timing.markOutput();

        QueryTiming.Snapshot snapshot = timing.snapshot();
        assertEquals(51, snapshot.timeToRequestMs());
        assertEquals(59, snapshot.ttftStreamMs());
        assertEquals(60, snapshot.ttftMs());
    }

    @Test
    void preservesFirstMilestoneAndUsesZeroWhenAMilestoneNeverOccurred() {
        AtomicLong now = new AtomicLong(5_000_000_000L);
        QueryTiming timing = new QueryTiming(now::get);

        timing.reset();
        now.addAndGet(7_000_000L);
        timing.markRequestStarted();
        now.addAndGet(5_000_000L);
        timing.markRequestStarted();
        timing.markStreamEvent();

        QueryTiming.Snapshot snapshot = timing.snapshot();
        assertEquals(7, snapshot.timeToRequestMs());
        assertEquals(12, snapshot.ttftStreamMs());
        assertEquals(0, snapshot.ttftMs());
    }
}

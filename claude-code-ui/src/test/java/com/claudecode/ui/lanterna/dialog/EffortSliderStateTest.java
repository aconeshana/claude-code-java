package com.claudecode.ui.lanterna.dialog;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the invariant that made the {@code /effort} slider crash the Lanterna GUI thread:
 * {@code layout} and {@code selectedIdx} were separate mutable fields, so a repaint could read
 * a freshly published 3-slot layout alongside the previous 7-slot selection and blow up in
 * {@code markerColumns().get(6)}.
 *
 * <p>These tests exercise the state holder directly rather than through Lanterna, so the
 * consistency property is checked without a terminal and without depending on timing.
 */
class EffortSliderStateTest {

    /**
     * gpt-5.6's capability list. With the ultracode slot appended this is the widest slider the
     * picker can open — seven slots.
     */
    private static final List<String> SIX_LEVELS =
        List.of("none", "low", "medium", "high", "xhigh", "max");

    /** opus-4-5: the narrowest, three slots and no ultracode. */
    private static final List<String> THREE_SLOT_LEVELS = List.of("low", "medium", "high");

    @Test
    void reopeningOnAShorterLevelListCannotStrandTheSelection() {
        EffortSliderState wide =
            EffortSliderState.opened("max", SIX_LEVELS, true, _ -> {}, 0L);
        assertEquals(7, wide.layout().slots().size());

        // Arrow right off max onto ultracode — the last slot of the widest layout.
        EffortSliderState onUltracode = wide.moved(1);
        assertEquals(6, onUltracode.selectedIdx());
        assertEquals("ultracode", onUltracode.selectedSlot().value());

        // Esc, switch model, reopen on three levels. The reopened snapshot carries its own
        // selection; the stale index cannot survive into it.
        EffortSliderState narrow = EffortSliderState.opened(
            "high", THREE_SLOT_LEVELS, false, _ -> {}, 0L);
        assertEquals(3, narrow.layout().slots().size());
        assertTrue(narrow.selectedIdx() < narrow.layout().slots().size());
        assertDoesNotThrow(narrow::markerColumn);
        assertDoesNotThrow(narrow::costNote);
        assertDoesNotThrow(narrow::selectedAccent);
    }

    @Test
    void everySnapshotHasASelectionItsOwnLayoutCanResolve() {
        // The constructor is the single place the pair is reconciled, so an out-of-range index
        // is clamped rather than surviving to a reader as an IndexOutOfBoundsException.
        EffortSliderLayout narrow = EffortSliderLayout.compute(THREE_SLOT_LEVELS, false);
        EffortSliderState state = new EffortSliderState(true, narrow, 6, 0L, null);

        assertEquals(2, state.selectedIdx());
        assertEquals("high", state.selectedSlot().value());
        assertEquals(narrow.markerColumns().get(2), state.markerColumn());
    }

    @Test
    void anUnknownInitialLevelFallsBackToHigh() {
        EffortSliderState state = EffortSliderState.opened(
            "ultracode", THREE_SLOT_LEVELS, false, null, 0L);
        assertEquals("high", state.selectedSlot().value());
    }

    @Test
    void movingWrapsAtBothEnds() {
        EffortSliderState state =
            EffortSliderState.opened("low", THREE_SLOT_LEVELS, false, null, 0L);
        assertEquals(0, state.selectedIdx());
        assertEquals(2, state.moved(-1).selectedIdx());
        assertEquals(0, state.moved(-1).moved(1).selectedIdx());
    }

    @Test
    void theIdleSnapshotCollapsesAndStillResolvesGeometry() {
        assertFalse(EffortSliderState.IDLE.active());
        assertFalse(EffortSliderState.IDLE.animated());
        assertDoesNotThrow(EffortSliderState.IDLE::markerColumn);
        assertDoesNotThrow(EffortSliderState.IDLE::bodyRows);
    }

    @Test
    void closingDropsTheCallbackButKeepsGeometryResolvable() {
        EffortSliderState closed =
            EffortSliderState.opened("high", THREE_SLOT_LEVELS, false, _ -> {}, 0L).closed();
        assertFalse(closed.active());
        assertNull(closed.onResult());
        assertFalse(closed.animated());
        assertDoesNotThrow(closed::markerColumn);
    }

    @Test
    void onlyAnimatedAccentsAskForARepaintTimer() {
        assertTrue(EffortSliderState.opened("xhigh", SIX_LEVELS, false, null, 0L)
            .animated(), "xhigh shimmers");
        assertTrue(EffortSliderState.opened("ultracode", SIX_LEVELS, true, null, 0L)
            .animated(), "ultracode ripples");
        assertFalse(EffortSliderState.opened("high", THREE_SLOT_LEVELS, false, null, 0L)
            .animated(), "high is a static colour");
    }

    /**
     * A reader racing a writer must never see a mixed pair. Both threads hammer the same
     * reference while the writer alternates between the widest and narrowest layouts; the
     * reader resolves the marker on every snapshot it observes. Under the old two-field design
     * this is exactly the interleaving that threw.
     */
    @Test
    void concurrentReadsAlwaysSeeAConsistentLayoutAndSelection() throws Exception {
        AtomicReference<EffortSliderState> holder =
            new AtomicReference<>(EffortSliderState.IDLE);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        int iterations = 20_000;
        CountDownLatch start = new CountDownLatch(1);

        Thread writer = new Thread(() -> {
            awaitQuietly(start);
            for (int i = 0; i < iterations; i++) {
                // Alternate the slot count, then walk the selection to the far end so the
                // widest layout really is sitting on an index the narrowest cannot resolve.
                EffortSliderState opened = (i % 2 == 0)
                    ? EffortSliderState.opened("max", SIX_LEVELS, true, null, i)
                    : EffortSliderState.opened("high", THREE_SLOT_LEVELS, false, null, i);
                holder.set(opened);
                holder.set(holder.get().moved(-1));
            }
        }, "effort-state-writer");

        Thread reader = new Thread(() -> {
            awaitQuietly(start);
            try {
                for (int i = 0; i < iterations; i++) {
                    EffortSliderState s = holder.get();
                    int marker = s.markerColumn();
                    assertTrue(marker >= 0 && marker < s.layout().trackChars().length(),
                        "marker " + marker + " outside a " + s.layout().slots().size()
                            + "-slot track");
                    assertEquals(s.layout().slots().get(s.selectedIdx()), s.selectedSlot());
                    s.costNote();
                    s.bodyRows();
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "effort-state-reader");

        writer.start();
        reader.start();
        start.countDown();
        writer.join(TimeUnit.SECONDS.toMillis(30));
        reader.join(TimeUnit.SECONDS.toMillis(30));

        assertFalse(writer.isAlive(), "writer did not finish");
        assertFalse(reader.isAlive(), "reader did not finish");
        if (failure.get() != null) {
            throw new AssertionError("reader observed a torn state", failure.get());
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}

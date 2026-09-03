package com.claudecode.tools.tasks;

import org.apache.commons.lang3.StringUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutionContext;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The show/teardown pairing that keeps the "press Ctrl+B" affordance from outliving the call it
 * belongs to.
 */
class BackgroundHintTest {

    private final List<ToolExecutionContext.ProgressUpdate> updates = new CopyOnWriteArrayList<>();

    private BackgroundHint hint() {
        return new BackgroundHint(ToolExecutionContext
            .builder(new AbortController(), "test-session")
            .progressSink(updates::add)
            .build());
    }

    @Test
    void theAffordanceCarriesNoDisplayableText() {
        hint().show();

        ToolExecutionContext.ProgressUpdate shown = updates.getFirst();
        assertTrue(shown.uiAffordanceOnly());
        assertTrue(StringUtils.isEmpty(shown.message()), shown.message());
    }

    @Test
    void clearRetiresAnAffordanceThatWasActuallyShown() {
        BackgroundHint hint = hint();
        hint.show();
        hint.clear();

        assertEquals(2, updates.size(), updates.toString());
        assertTrue(updates.get(1).complete());
    }

    @Test
    void clearIsSilentWhenNothingWasShown() {
        // A short-lived call must not clear a concurrently running tool's progress affordances.
        hint().clear();

        assertTrue(updates.isEmpty(), updates.toString());
    }

    @Test
    void clearIsIdempotent() {
        BackgroundHint hint = hint();
        hint.show();
        hint.clear();
        hint.clear();

        assertEquals(1, updates.stream().filter(
            ToolExecutionContext.ProgressUpdate::complete).count(), updates.toString());
    }

    @Test
    void showAfterTeardownIsRefused() {
        // ScheduledFuture.cancel(false) cannot stop a callback that already entered run(), so
        // the state machine — not the timer — is what guarantees this.
        BackgroundHint hint = hint();
        hint.disarm();
        hint.show();

        assertTrue(updates.isEmpty(), updates.toString());
    }

    @Test
    void disarmReportsWhetherAnAffordanceWasLeftVisible() {
        BackgroundHint shown = hint();
        shown.show();
        assertTrue(shown.disarm());
        assertFalse(shown.disarm());

        assertFalse(hint().disarm());
    }

    @Test
    void aRacingShowNeverLandsAfterTeardown() throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            updates.clear();
            BackgroundHint hint = hint();
            CountDownLatch start = new CountDownLatch(1);
            Thread shower = Thread.ofVirtual().start(() -> {
                awaitQuietly(start);
                hint.show();
            });
            Thread clearer = Thread.ofVirtual().start(() -> {
                awaitQuietly(start);
                hint.clear();
            });
            start.countDown();
            shower.join();
            clearer.join();

            // Either the hint won (show then clear) or teardown won (nothing at all), but never
            // an affordance emitted with no clear behind it.
            boolean shownHint = updates.stream()
                .anyMatch(ToolExecutionContext.ProgressUpdate::uiAffordanceOnly);
            boolean cleared = updates.stream()
                .anyMatch(ToolExecutionContext.ProgressUpdate::complete);
            assertEquals(shownHint, cleared,
                "attempt " + attempt + " left the affordance unpaired: " + updates);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }
}

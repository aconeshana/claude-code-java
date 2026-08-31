package com.claudecode.core.feature;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeatureGateTest {

    @Test
    void nestedOverridesAccumulateAndRestoreTheOuterBinding() {
        FeatureGate.withFlags(() -> {
            assertTrue(FeatureGate.isEnabled(FeatureGate.Flag.KAIROS));
            assertFalse(FeatureGate.isEnabled(FeatureGate.Flag.STRICT_TOOLS));

            FeatureGate.withFlags(() -> {
                assertTrue(FeatureGate.isEnabled(FeatureGate.Flag.KAIROS));
                assertTrue(FeatureGate.isEnabled(FeatureGate.Flag.STRICT_TOOLS));
            }, FeatureGate.Flag.STRICT_TOOLS);

            assertTrue(FeatureGate.isEnabled(FeatureGate.Flag.KAIROS));
            assertFalse(FeatureGate.isEnabled(FeatureGate.Flag.STRICT_TOOLS));
        }, FeatureGate.Flag.KAIROS);
    }

    @Test
    void exceptionRestoresThePreviousFeatureState() {
        boolean baseline = FeatureGate.isEnabled(FeatureGate.Flag.KAIROS);

        assertThrows(IllegalStateException.class, () ->
            FeatureGate.withFlags(() -> {
                throw new IllegalStateException("boom");
            }, FeatureGate.Flag.KAIROS));

        assertEquals(baseline, FeatureGate.isEnabled(FeatureGate.Flag.KAIROS));
    }

    @Test
    void concurrentVirtualThreadsKeepOverridesIsolated() throws InterruptedException {
        AtomicBoolean firstOwn = new AtomicBoolean();
        AtomicBoolean firstOther = new AtomicBoolean(true);
        AtomicBoolean secondOwn = new AtomicBoolean();
        AtomicBoolean secondOther = new AtomicBoolean(true);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        Thread first = featureThread(FeatureGate.Flag.KAIROS, FeatureGate.Flag.STRICT_TOOLS,
            firstOwn, firstOther, ready, release);
        Thread second = featureThread(FeatureGate.Flag.STRICT_TOOLS, FeatureGate.Flag.KAIROS,
            secondOwn, secondOther, ready, release);
        ready.await();
        release.countDown();
        first.join();
        second.join();

        assertTrue(firstOwn.get());
        assertFalse(firstOther.get());
        assertTrue(secondOwn.get());
        assertFalse(secondOther.get());
    }

    private static Thread featureThread(FeatureGate.Flag own,
                                        FeatureGate.Flag other,
                                        AtomicBoolean ownResult,
                                        AtomicBoolean otherResult,
                                        CountDownLatch ready,
                                        CountDownLatch release) {
        return Thread.startVirtualThread(() -> FeatureGate.withFlags(() -> {
            ready.countDown();
            await(release);
            ownResult.set(FeatureGate.isEnabled(own));
            otherResult.set(FeatureGate.isEnabled(other));
        }, own));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while coordinating feature-gate threads");
        }
    }
}

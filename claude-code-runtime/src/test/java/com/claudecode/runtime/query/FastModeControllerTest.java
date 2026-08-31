package com.claudecode.runtime.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class FastModeControllerTest {

    @Test
    void enabledSupportedModelProducesFastRequests() {
        FastModeController controller = new FastModeController(true, true, () -> 1_000L);

        assertEquals(FastModeRuntimeState.ON, controller.state("claude-opus-4-8"));
        assertTrue(controller.isFastRequest("opus"));
        assertFalse(controller.isFastRequest("claude-sonnet-4-6"));
        assertFalse(controller.isFastRequest("claude-opus-4-1"));
        assertEquals(FastModeRuntimeState.OFF, controller.state("claude-sonnet-4-6"));
    }

    @Test
    void cooldownFallsBackUntilItsDeadlineThenRecovers() {
        AtomicLong clock = new AtomicLong(1_000L);
        FastModeController controller = new FastModeController(true, true, clock::get);

        controller.enterCooldown(Duration.ofSeconds(30), FastModeCooldownReason.RATE_LIMIT);

        assertEquals(FastModeRuntimeState.COOLDOWN, controller.state("opus"));
        assertFalse(controller.isFastRequest("opus"));
        assertEquals(FastModeCooldownReason.RATE_LIMIT, controller.cooldownReason());

        clock.set(31_000L);
        assertEquals(FastModeRuntimeState.ON, controller.state("opus"));
        assertTrue(controller.isFastRequest("opus"));
    }

    @Test
    void unavailableOrDisabledControllerAlwaysReportsOff() {
        FastModeController unavailable = new FastModeController(false, true, () -> 0L);
        FastModeController disabled = new FastModeController(true, false, () -> 0L);

        assertFalse(unavailable.setEnabled(true));
        assertEquals(FastModeRuntimeState.OFF, unavailable.state("opus"));
        assertEquals(FastModeRuntimeState.OFF, disabled.state("opus"));
        assertTrue(disabled.setEnabled(true));
        assertEquals(FastModeRuntimeState.ON, disabled.state("opus"));
    }

    @Test
    void preferenceChangesArePublishedOnlyAfterAcceptedStateChanges() {
        AtomicReference<Boolean> persisted = new AtomicReference<>();
        FastModeController controller = new FastModeController(
            true, false, () -> 0L, persisted::set);

        assertTrue(controller.setEnabled(true));
        assertEquals(Boolean.TRUE, persisted.get());

        assertTrue(controller.setEnabled(false));
        assertEquals(Boolean.FALSE, persisted.get());

        persisted.set(null);
        FastModeController unavailable = new FastModeController(
            false, false, () -> 0L, persisted::set);
        assertFalse(unavailable.setEnabled(true));
        assertNull(persisted.get());
    }
}

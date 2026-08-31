package com.claudecode.ui.lanterna.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.text.FormatUtils;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;


class SpinnerTimerVisibilityTest {

    @Test
    void elapsedTimerAppearsAfterReleasedSixteenSecondThresholdWithoutResponseTokens() {
        assertTrue(SpinnerComponent.shouldShowTimer(false,
            SpinnerFrames.SHOW_TOKENS_AFTER_MS + 1));
    }

    @Test
    void elapsedTimerStaysHiddenBeforeThresholdUnlessVerbose() {
        assertFalse(SpinnerComponent.shouldShowTimer(false,
            SpinnerFrames.SHOW_TOKENS_AFTER_MS));
        assertTrue(SpinnerComponent.shouldShowTimer(true, 0));
    }

    @Test
    void thinkingOrResponseTokensEnableTimerBeforeThreshold() {
        assertTrue(SpinnerComponent.shouldShowTimer(false, 0, false, true, 0));
        assertTrue(SpinnerComponent.shouldShowTimer(false, 0, false, false, 1));
        assertFalse(SpinnerComponent.shouldShowTimer(false, 0, false, false, 0));
    }

    @Test
    void runningTeammatesShowTheTimerImmediatelyAndAnchorItsEarlierStart() {
        assertTrue(SpinnerComponent.shouldShowTimer(false, 0, true));
        assertEquals(45_000L, SpinnerComponent.effectiveElapsedMs(
            2_000L, 100_000L, 55_000L, true));
        assertEquals(1_250L, SpinnerComponent.effectiveTokenCount(1_000,
            List.of(
                new SpinnerComponent.TeammateMetric(55_000L, 0L, 400L),
                new SpinnerComponent.TeammateMetric(80_000L, 5_000L, 600L))));
    }

    @Test
    void teammateClockKeepsTheEarliestLeaderTurnAnchorAcrossTurns() {
        assertEquals(35_000L, SpinnerComponent.effectiveElapsedMs(
            2_000L, 100_000L, 65_000L, true));
        assertEquals(55_000L, SpinnerComponent.nextTeammateTurnStartMs(
            55_000L, 95_000L, true));
        assertEquals(95_000L, SpinnerComponent.nextTeammateTurnStartMs(
            55_000L, 95_000L, false));
        assertEquals(251L, SpinnerComponent.effectiveTokenCount(1_002, List.of()));
    }

    @Test
    void metricGatingUsesTerminalCellsForWideSpinnerMessages() {
        String message = "编码";

        assertEquals(20 - FormatUtils.displayWidth(message) - 2 - 5,
            SpinnerComponent.availableMetricSpace(20, message));
        assertTrue(FormatUtils.displayWidth(message) > message.length(),
            "the fixture must expose the UTF-16-length bug");
    }

    @Test
    void leaderCompletionKeepsSpinnerUntilRunningTeammatesExit() throws Exception {
        SpinnerComponent spinner = new SpinnerComponent();
        AtomicReference<List<SpinnerComponent.TeammateMetric>> metrics =
            new AtomicReference<>(List.of(new SpinnerComponent.TeammateMetric(
                "task-1", "researcher", false, "Researching", 1L, 0L, 12L)));
        CountDownLatch finished = new CountDownLatch(1);
        spinner.setRunningTeammateMetricsSupplier(metrics::get);
        spinner.setTeammateSwarmFinishedListener(finished::countDown);
        try {
            spinner.startTurn();
            spinner.finishTurnClock();
            spinner.finishLeaderTurn();
            assertTrue(spinner.isSpinning());

            metrics.set(List.of());
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            assertFalse(spinner.isSpinning());
        } finally {
            spinner.stop();
        }
    }
}

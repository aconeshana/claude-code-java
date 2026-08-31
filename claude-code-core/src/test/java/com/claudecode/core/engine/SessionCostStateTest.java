package com.claudecode.core.engine;

import com.claudecode.core.message.Usage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Process-level cost accumulator — the data source for {@code /cost}. Tests
 * drive the singleton after {@link SessionCostState#reset} so state from
 * other tests in the same JVM doesn't leak in.
 */
class SessionCostStateTest {

    private SessionCostState state;

    @BeforeEach
    void setUp() {
        state = SessionCostState.get();
        state.reset();
    }

    @Test
    void recordApiRequest_accumulatesDurationAndPerModelUsage() {
        state.recordApiRequest("claude-opus-4-8", new Usage(100, 50, 10, 5), 1200, 700);
        state.recordApiRequest("claude-opus-4-8", new Usage(200, 60, 0, 0), 800);
        state.recordApiRequest("claude-haiku-4-5", new Usage(30, 10, 0, 0), 300);

        assertEquals(2300, state.apiDurationMs());
        assertEquals(1800, state.apiDurationWithoutRetriesMs());
        Map<String, Usage> byModel = state.usageByModel();
        assertEquals(2, byModel.size());
        Usage opus = byModel.get("claude-opus-4-8");
        assertEquals(300, opus.inputTokens());   // 100+200
        assertEquals(110, opus.outputTokens());   // 50+60
        assertEquals(10, opus.cacheCreationInputTokens());
        assertEquals(40, byModel.get("claude-haiku-4-5").totalTokens());
    }

    @Test
    void recordApiRequest_ignoresBlankModelAndZeroDuration() {
        state.recordApiRequest(null, new Usage(1, 1, 0, 0), 0);
        state.recordApiRequest("  ", new Usage(1, 1, 0, 0), 0);
        assertTrue(state.usageByModel().isEmpty());
        assertEquals(0, state.apiDurationMs());
    }

    @Test
    void recordLinesChanged_accumulates() {
        state.recordLinesChanged(10, 3);
        state.recordLinesChanged(5, 0);
        assertEquals(15, state.totalLinesAdded());
        assertEquals(3, state.totalLinesRemoved());
    }

    @Test
    void recordToolDurationAccumulatesEachInvocation() {
        state.recordToolDuration(1200);
        state.recordToolDuration(800);
        state.recordToolDuration(0);

        assertEquals(2000, state.toolDurationMs());
    }

    @Test
    void wallDuration_isNonNegativeAndMovesForward() throws InterruptedException {
        long a = state.wallDurationMs();
        Thread.sleep(15);
        long b = state.wallDurationMs();
        assertTrue(a >= 0);
        assertTrue(b >= a);
    }

    @Test
    void reset_clearsEverything() {
        state.recordApiRequest("m", new Usage(1, 1, 0, 0), 100);
        state.recordLinesChanged(5, 5);
        state.reset();
        assertTrue(state.isEmpty());
        assertEquals(0, state.apiDurationMs());
        assertEquals(0, state.apiDurationWithoutRetriesMs());
        assertEquals(0, state.toolDurationMs());
        assertEquals(0, state.totalLinesAdded());
        assertEquals(0, state.totalLinesRemoved());
    }

    @Test
    void webSearchRequests_surviveAccumulation() {
        Usage withSearch = new Usage(10, 5, 0, 0,
            new Usage.ServerToolUse(3, 0));
        state.recordApiRequest("claude-opus-4-8", withSearch, 100);
        state.recordApiRequest("claude-opus-4-8", withSearch, 100);
        assertEquals(6, state.usageByModel().get("claude-opus-4-8").webSearchRequests());
    }

    @Test
    void liveUsageViewTracksRequestsRecordedAfterAResultSnapshot() {
        state.recordApiRequest("claude-sonnet-4-6", new Usage(1, 1, 0, 0), 10);
        Map<String, Usage> heldResultView = state.liveUsageByModel();

        state.recordApiRequest("claude-sonnet-4-6", new Usage(2, 2, 0, 0), 10);

        assertEquals(3, heldResultView.get("claude-sonnet-4-6").inputTokens());
        assertEquals(3, heldResultView.get("claude-sonnet-4-6").outputTokens());
    }

    @Test
    void costAccumulationPreservesReleasedPerRequestFloatingPointOrder() {
        Usage main = new Usage(12_000, 1, 0, 0);
        Usage failedCompact = new Usage(1, 0, 0, 0);
        state.recordApiRequest("claude-sonnet-4-6", main, 1);
        state.recordApiRequest("claude-sonnet-4-6", main, 1);
        state.recordApiRequest("claude-sonnet-4-6", failedCompact, 1);
        state.recordApiRequest("claude-sonnet-4-6", main, 1);

        CostCalculator calculator = CostCalculator.forModel("claude-sonnet-4-6");
        double expected = calculator.calculateCost(main)
            + calculator.calculateCost(main)
            + calculator.calculateCost(failedCompact)
            + calculator.calculateCost(main);

        assertEquals(Double.doubleToLongBits(expected),
            Double.doubleToLongBits(state.totalCostUsd()));
        assertEquals(Double.doubleToLongBits(expected), Double.doubleToLongBits(
            state.liveCostByModel().get("claude-sonnet-4-6")));
    }

    @Test
    void snapshotThenRestore_roundTripsAllFields() {
        state.recordApiRequest("m1", new Usage(100, 50, 0, 0), 1000, 600);
        state.recordToolDuration(250);
        state.recordLinesChanged(7, 2);
        SessionCostState.Snapshot snap = state.snapshot();

        state.reset();
        assertTrue(state.isEmpty());

        state.restore(snap);
        assertEquals(1000, state.apiDurationMs());
        assertEquals(600, state.apiDurationWithoutRetriesMs());
        assertEquals(250, state.toolDurationMs());
        assertEquals(7, state.totalLinesAdded());
        assertEquals(2, state.totalLinesRemoved());
        assertEquals(150, state.usageByModel().get("m1").totalTokens());
    }

    @Test
    void restorePreservesPersistedPerModelAndTotalCostsWithoutRepricing() {
        double modelCost = 0.123456789012345;
        double totalCost = 0.223456789012345;
        state.restore(new SessionCostState.Snapshot(
            100, 90, 10, 1_000, 2, 1,
            Map.of("custom-model", new Usage(7, 3, 0, 0)),
            Map.of("custom-model", modelCost),
            totalCost));

        assertEquals(Double.doubleToLongBits(modelCost), Double.doubleToLongBits(
            state.costByModel().get("custom-model")));
        assertEquals(Double.doubleToLongBits(totalCost),
            Double.doubleToLongBits(state.totalCostUsd()));
    }

    @Test
    void restore_continuesWallClockFromSavedValue() {
        // A snapshot claiming 60s of wall time should make wallDurationMs read

        state.restore(new SessionCostState.Snapshot(0, 60_000, 0, 0, Map.of()));
        long wall = state.wallDurationMs();
        assertTrue(wall >= 59_000 && wall <= 62_000, "wall should resume near 60s; got " + wall);
    }

    @Test
    void restoreWithZeroDurationKeepsExistingWallClockOrigin() throws Exception {
        Thread.sleep(20L);
        long before = state.wallDurationMs();

        state.restore(new SessionCostState.Snapshot(0, 0, 0, 0, Map.of()));

        assertTrue(state.wallDurationMs() >= before,
            "released 2.1.197 only adjusts startTime when lastDuration is truthy");
    }
}

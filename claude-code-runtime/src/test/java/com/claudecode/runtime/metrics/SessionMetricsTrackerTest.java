package com.claudecode.runtime.metrics;

import com.claudecode.core.engine.TranscriptSink;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.Usage;
import com.claudecode.core.metrics.SessionMetricsEvent;
import com.claudecode.core.metrics.SessionMetricsSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionMetricsTrackerTest {
    @Test
    void foldsWholeStepWithRetryToolTimingAndDisjointUsage() {
        AtomicLong now = new AtomicLong();
        RecordingSink sink = new RecordingSink();
        SessionMetricsTracker tracker = new SessionMetricsTracker("s", () -> sink, now::get);

        tracker.startFresh("s");
        now.set(100); tracker.beginTurn("user-1");
        now.set(1_000); tracker.beginStep();
        tracker.beginStep(); // provider retry: same logical step
        now.set(1_800); tracker.firstToken();
        now.set(2_500); tracker.usage(new Usage(10, 24, 2, 88));
        now.set(3_000); tracker.assistantMessage();
        now.set(3_100); tracker.toolCall("call-1");
        now.set(3_600); tracker.toolResult("call-1");
        now.set(3_700); tracker.endStep();
        now.set(3_800); tracker.endTurn();

        SessionMetricsSnapshot value = tracker.snapshot();
        assertTrue(value.complete());
        assertEquals(1, value.turns());
        assertEquals(1, value.steps());
        assertEquals(2_000, value.llmMs());
        assertEquals(500, value.toolMs());
        assertEquals(800, value.ttftMs());
        assertEquals(1, value.ttftSteps());
        assertEquals(1_200, value.decodeMs());
        assertEquals(24, value.decodeTokens());
        assertEquals(100, value.billedInputTokens());
        assertEquals(20.0, value.tokensPerSecond());
        assertEquals(sink.events.size(), sink.events.getLast().seq() + 1);
    }

    @Test
    void legacyOrSequenceGapIsIncomplete() {
        SessionMetricsTracker tracker = new SessionMetricsTracker("old", () -> null,
            System::currentTimeMillis);
        tracker.restore("old", List.of(), List.of("u"));
        assertFalse(tracker.snapshot().complete());

        SessionMetricsEvent start = new SessionMetricsEvent(1, 0, 1, "old",
            SessionMetricsEvent.Kind.SESSION_START, null, 0, 0, null,
            0, 0, 0, 0, false);
        SessionMetricsEvent gap = new SessionMetricsEvent(1, 2, 2, "old",
            SessionMetricsEvent.Kind.TURN_START, "u", 1, 0, null,
            0, 0, 0, 0, false);
        tracker.restore("old", List.of(start, gap), List.of("u"));
        assertFalse(tracker.snapshot().complete());
    }

    @Test
    void officialAppendedPromptWithoutMetricTurnInvalidatesCoverage() {
        SessionMetricsEvent start = new SessionMetricsEvent(1, 0, 1, "s",
            SessionMetricsEvent.Kind.SESSION_START, null, 0, 0, null,
            0, 0, 0, 0, false);
        SessionMetricsTracker tracker = new SessionMetricsTracker("s", () -> null,
            System::currentTimeMillis);

        tracker.restore("s", List.of(start), List.of("official-user"));

        assertFalse(tracker.snapshot().complete());
    }

    private static final class RecordingSink implements TranscriptSink {
        private final List<SessionMetricsEvent> events = new ArrayList<>();
        @Override public void record(String sessionId, Message message) {}
        @Override public void recordSessionMetrics(String sessionId, SessionMetricsEvent event) {
            events.add(event);
        }
    }
}

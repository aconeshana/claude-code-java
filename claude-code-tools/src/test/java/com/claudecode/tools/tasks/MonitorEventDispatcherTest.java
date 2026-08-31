package com.claudecode.tools.tasks;

import org.apache.commons.lang3.Strings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class MonitorEventDispatcherTest {

    @Test
    void batchesLinesAndBuildsOfficialHyphenatedNotification() {
        List<String> emitted = new ArrayList<>();
        MonitorEventDispatcher dispatcher = MonitorEventDispatcher.forTest(
            "bm123", "deploy <events>", emitted::add, () -> 0L, () -> {});

        dispatcher.accept("one & done");
        dispatcher.accept("two");
        dispatcher.flushNow();

        assertEquals(List.of("""
            <task-notification>
            <task-id>bm123</task-id>
            <summary>Monitor event: "deploy &lt;events&gt;"</summary>
            <event>one &amp; done
            two</event>
            </task-notification>"""), emitted);
        dispatcher.close();
    }

    @Test
    void trimsLinesDropsEmptyOnesAndKeepsMultilineFramesInOneBatch() {
        List<String> emitted = new ArrayList<>();
        MonitorEventDispatcher dispatcher = MonitorEventDispatcher.forTest(
            "bm123", "frames", emitted::add, () -> 0L, () -> {});

        dispatcher.accept("  one  \n\n  two\r  ");
        dispatcher.flushNow();

        assertTrue(Strings.CS.contains(emitted.getFirst(), "<event>one\ntwo</event>"));
        dispatcher.close();
    }

    @Test
    void truncatesEachLineAndWholeBatchAtOfficialLimits() {
        List<String> emitted = new ArrayList<>();
        MonitorEventDispatcher dispatcher = MonitorEventDispatcher.forTest(
            "bm123", "volume", emitted::add, () -> 0L, () -> {});

        dispatcher.accept("x".repeat(600));
        for (int i = 0; i < 8; i++) dispatcher.accept("y".repeat(500));
        dispatcher.flushNow();

        String event = emitted.getFirst();
        assertTrue(Strings.CS.contains(event, "x".repeat(500) + "...(truncated)"));
        assertTrue(Strings.CS.contains(event, "\n...(truncated)</event>"));
        assertFalse(Strings.CS.contains(event, "x".repeat(501)));
        dispatcher.close();
    }

    @Test
    void rateLimiterStopsAThirtySecondFirehose() {
        List<String> emitted = new ArrayList<>();
        AtomicLong now = new AtomicLong();
        AtomicBoolean stopped = new AtomicBoolean();
        MonitorEventDispatcher dispatcher = MonitorEventDispatcher.forTest(
            "bm123", "firehose", emitted::add, now::get, () -> stopped.set(true));

        for (int i = 0; i < 10; i++) {
            dispatcher.accept("event-" + i);
            dispatcher.flushNow();
        }
        dispatcher.accept("suppressed-1");
        dispatcher.flushNow();
        for (int second = 2; second <= 32; second += 2) {
            now.set(second * 1_000L);
            dispatcher.accept("refill-" + second);
            dispatcher.flushNow();
            dispatcher.accept("suppressed-" + second);
            dispatcher.flushNow();
        }

        assertTrue(stopped.get());
        assertTrue(Strings.CS.contains(emitted.getLast(), 
            "[Monitor stopped — too much output (1 events suppressed over 32s). Restart with a more selective source.]"));
        dispatcher.close();
    }

    @Test
    void reportsSuppressedEventsWhenARefillTokenBecomesAvailable() {
        List<String> emitted = new ArrayList<>();
        AtomicLong now = new AtomicLong();
        MonitorEventDispatcher dispatcher = MonitorEventDispatcher.forTest(
            "bm123", "firehose", emitted::add, now::get, () -> {});

        for (int i = 0; i < 10; i++) {
            dispatcher.accept("event-" + i);
            dispatcher.flushNow();
        }
        dispatcher.accept("suppressed");
        dispatcher.flushNow();
        now.set(2_000);
        dispatcher.accept("recovered");
        dispatcher.flushNow();

        assertTrue(Strings.CS.contains(emitted.get(10), 
            "[1 events suppressed — output rate too high. Consider using TaskStop to restart this monitor with a more selective filter.]"));
        assertTrue(Strings.CS.contains(emitted.get(11), "<event>recovered</event>"));
        dispatcher.close();
    }
}

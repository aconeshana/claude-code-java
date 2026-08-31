package com.claudecode.ui.lanterna.repl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;

class UiStreamDeltaBatcherTest {

    @Test
    void adjacentDeltasBecomeOneUiTaskAndStayAheadOfFollowingEvent() {
        List<Runnable> uiQueue = new ArrayList<>();
        List<String> rendered = new ArrayList<>();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            UiStreamDeltaBatcher batcher = new UiStreamDeltaBatcher(
                uiQueue::add, rendered::add, scheduler, 60_000L);

            batcher.append("hel");
            batcher.append("lo");
            batcher.runAfterPending(() -> rendered.add("stop"));

            assertEquals(2, uiQueue.size());
            uiQueue.forEach(Runnable::run);
            assertEquals(List.of("hello", "stop"), rendered);
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void displayHookTransformsOnlyRenderedDeltaAndReceivesFinalMarker() {
        List<Runnable> uiQueue = new ArrayList<>();
        List<String> rendered = new ArrayList<>();
        List<Boolean> finalMarkers = new ArrayList<>();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            UiStreamDeltaBatcher batcher = new UiStreamDeltaBatcher(
                uiQueue::add, rendered::add,
                (text, finalDelta) -> {
                    finalMarkers.add(finalDelta);
                    return text.toUpperCase();
                }, scheduler, 60_000L);

            batcher.append("hello");
            batcher.runAfterPending(true, () -> rendered.add("assistant"));

            uiQueue.forEach(Runnable::run);
            assertEquals(List.of("HELLO", "assistant"), rendered);
            assertEquals(List.of(true), finalMarkers);
        } finally {
            scheduler.shutdownNow();
        }
    }
}

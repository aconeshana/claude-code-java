package com.claudecode.ui.lanterna.repl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ReplStartupPreparationTest {

    @Test
    void preparationRunsOffTheCallerAndDoesNotSerializeSceneAssembly() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean virtualThread = new AtomicBoolean();

        var prepared = ReplStartupPreparation.startForTest(
            () -> {
                virtualThread.set(Thread.currentThread().isVirtual());
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
                return true;
            },
            () -> false,
            () -> true,
            () -> 7,
            () -> new SessionController.RestoredSessionBadge("agent", "blue"));

        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertFalse(prepared.isDone(), "the caller must be free to assemble the scene in parallel");
        assertTrue(virtualThread.get());

        release.countDown();
        ReplStartupPreparation.Prepared state = prepared.get(1, TimeUnit.SECONDS);
        assertTrue(state.copyOnSelect());
        assertFalse(state.spinnerTipsEnabled());
        assertTrue(state.vimModeEnabled());
        assertEquals(7, state.btwUseCount());
        assertEquals("agent", state.sessionBadge().name());
        assertNull(state.sessionCustomTitle());
    }

    @Test
    void optionalPreparationFailuresDegradeToDocumentedDefaults() throws Exception {
        var prepared = ReplStartupPreparation.startForTest(
            () -> { throw new IllegalStateException("copy"); },
            () -> { throw new IllegalStateException("spinner"); },
            () -> { throw new IllegalStateException("vim"); },
            () -> { throw new IllegalStateException("btw"); },
            () -> { throw new IllegalStateException("badge"); });

        ReplStartupPreparation.Prepared state = prepared.get(1, TimeUnit.SECONDS);
        assertTrue(state.copyOnSelect());
        assertTrue(state.spinnerTipsEnabled());
        assertFalse(state.vimModeEnabled());
        assertEquals(0, state.btwUseCount());
        assertNull(state.sessionBadge().name());
        assertNull(state.sessionBadge().color());
        assertNull(state.sessionCustomTitle());
    }

    @Test
    void oneMetadataSnapshotFeedsBothBadgeAndSessionHostTitle() {
        AtomicInteger scans = new AtomicInteger();
        InteractiveSessionPort sessions = new InteractiveSessionPort() {
            @Override public java.nio.file.Path sessionFile(String cwd, String sessionId) {
                return java.nio.file.Path.of("transcript.jsonl");
            }
            @Override public MetadataSnapshot scanMetadata(java.nio.file.Path transcript) {
                scans.incrementAndGet();
                return new MetadataSnapshot("custom title", "agent", "green", null);
            }
        };

        ReplStartupPreparation.SessionMetadata metadata =
            ReplStartupPreparation.loadSessionMetadata("session", sessions);

        assertEquals(1, scans.get());
        assertEquals("custom title", metadata.customTitle());
        assertEquals("agent", metadata.badge().name());
        assertEquals("green", metadata.badge().color());
    }
}

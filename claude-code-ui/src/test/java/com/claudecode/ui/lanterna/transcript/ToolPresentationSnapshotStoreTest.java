package com.claudecode.ui.lanterna.transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.ui.lanterna.dialog.RejectedFileChangePreview;
import org.junit.jupiter.api.Test;

class ToolPresentationSnapshotStoreTest {

    @Test
    void resetRejectsLatePublicationFromThePreviousTurn() {
        ToolPresentationSnapshotStore store = new ToolPresentationSnapshotStore();
        var ticket = store.ticket("toolu_old");

        store.resetTurn();

        assertFalse(store.publishFilePreview(ticket,
            RejectedFileChangePreview.source("write", "/tmp/old.txt", "old", "old.txt")));
        assertNull(store.consumeFilePreview("toolu_old"));
    }

    @Test
    void fileAndPlanSnapshotsArePublishedAndConsumedAtomically() {
        ToolPresentationSnapshotStore store = new ToolPresentationSnapshotStore();
        var fileTicket = store.ticket("toolu_file");
        var planTicket = store.ticket("toolu_plan");
        var preview = RejectedFileChangePreview.source(
            "write", "/tmp/new.txt", "new content", "new.txt");

        assertTrue(store.publishFilePreview(fileTicket, preview));
        assertTrue(store.publishPlan(planTicket, "# Plan"));

        assertEquals(preview, store.consumeFilePreview("toolu_file"));
        assertNull(store.consumeFilePreview("toolu_file"));
        assertEquals("# Plan", store.consumePlan("toolu_plan"));
        assertNull(store.consumePlan("toolu_plan"));
    }

    @Test
    void concurrentFileAndPlanPublicationPreservesBothSnapshots() throws Exception {
        ToolPresentationSnapshotStore store = new ToolPresentationSnapshotStore();
        var ticket = store.ticket("toolu_shared");
        var preview = RejectedFileChangePreview.source(
            "write", "/tmp/new.txt", "new content", "new.txt");
        Thread filePublisher = Thread.ofVirtual().start(
            () -> store.publishFilePreview(ticket, preview));
        Thread planPublisher = Thread.ofVirtual().start(
            () -> store.publishPlan(ticket, "# Concurrent plan"));

        filePublisher.join();
        planPublisher.join();

        ToolPresentationSnapshotStore.Snapshot snapshot = store.consume("toolu_shared");
        assertEquals(preview, snapshot.filePreview());
        assertEquals("# Concurrent plan", snapshot.plan());
        assertNull(store.consumeFilePreview("toolu_shared"));
        assertNull(store.consumePlan("toolu_shared"));
    }
}

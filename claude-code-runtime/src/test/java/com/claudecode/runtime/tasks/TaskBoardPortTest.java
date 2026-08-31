package com.claudecode.runtime.tasks;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskBoardPortTest {

    @Test
    void snapshotDefensivelyCopiesAllCollections() {
        List<String> blocks = new ArrayList<>(List.of("2"));
        List<String> blockedBy = new ArrayList<>(List.of("3"));
        TaskBoardPort.TaskItem item = new TaskBoardPort.TaskItem(
            "1", "Ship", "Finish it", "Shipping", "worker",
            TaskBoardPort.Status.IN_PROGRESS, blocks, blockedBy);
        List<TaskBoardPort.TaskItem> tasks = new ArrayList<>(List.of(item));

        TaskBoardPort.Snapshot snapshot = new TaskBoardPort.Snapshot(
            "session-a", 1L, tasks, false);
        blocks.add("4");
        blockedBy.clear();
        tasks.clear();

        assertEquals(List.of("2"), snapshot.tasks().getFirst().blocks());
        assertEquals(List.of("3"), snapshot.tasks().getFirst().blockedBy());
        assertThrows(UnsupportedOperationException.class,
            () -> snapshot.tasks().add(item));
        assertThrows(UnsupportedOperationException.class,
            () -> snapshot.tasks().getFirst().blocks().add("5"));
    }

    @Test
    void noneReturnsEmptySnapshotAndSafeSubscriptions() throws Exception {
        TaskBoardPort port = TaskBoardPort.none();
        AtomicInteger snapshots = new AtomicInteger();
        AtomicInteger intents = new AtomicInteger();

        assertEquals(TaskBoardPort.Snapshot.EMPTY, port.snapshot());
        AutoCloseable snapshotSubscription = port.subscribe(_ -> snapshots.incrementAndGet());
        AutoCloseable intentSubscription = port.subscribeIntents(_ -> intents.incrementAndGet());

        assertDoesNotThrow(snapshotSubscription::close);
        assertDoesNotThrow(intentSubscription::close);
        assertEquals(0, snapshots.get());
        assertEquals(0, intents.get());
    }
}

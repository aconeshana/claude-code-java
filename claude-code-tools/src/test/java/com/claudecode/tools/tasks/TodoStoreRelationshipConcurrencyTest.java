package com.claudecode.tools.tasks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TodoStoreRelationshipConcurrencyTest {

    @Test
    void addingBlockRelationDoesNotOverwriteConcurrentFieldUpdate(@TempDir Path tasksBase) {
        TodoStore seed = new TodoStore(tasksBase, "session");
        Task from = seed.create("original", "from", null, Map.of());
        Task to = seed.create("target", "to", null, Map.of());
        TodoStore staleRelationshipWriter = new TodoStore(tasksBase, "session");
        TodoStore fieldWriter = new TodoStore(tasksBase, "session");

        Task current = fieldWriter.get(from.id()).orElseThrow();
        fieldWriter.update(from.id(), current.withSubject("concurrent subject"));
        assertTrue(staleRelationshipWriter.block(from.id(), to.id()));

        TodoStore persisted = new TodoStore(tasksBase, "session");
        Task result = persisted.get(from.id()).orElseThrow();
        assertEquals("concurrent subject", result.subject());
        assertEquals(List.of(to.id()), result.blocks());
    }

    @Test
    void deleteCleanupDoesNotOverwriteConcurrentFieldUpdate(@TempDir Path tasksBase) {
        TodoStore seed = new TodoStore(tasksBase, "session");
        Task blocker = seed.create("blocker", "remove me", null, Map.of());
        Task blocked = seed.create("original", "keep me", null, Map.of());
        assertTrue(seed.block(blocker.id(), blocked.id()));
        TodoStore staleDeleteWriter = new TodoStore(tasksBase, "session");
        TodoStore fieldWriter = new TodoStore(tasksBase, "session");

        Task current = fieldWriter.get(blocked.id()).orElseThrow();
        fieldWriter.update(blocked.id(), current.withSubject("concurrent subject"));
        assertTrue(staleDeleteWriter.delete(blocker.id()));

        TodoStore persisted = new TodoStore(tasksBase, "session");
        Task result = persisted.get(blocked.id()).orElseThrow();
        assertEquals("concurrent subject", result.subject());
        assertTrue(result.blockedBy().isEmpty());
    }

    @Test
    void claimTreatsAnExplicitEmptyOwnerAsUnownedLikeReleased197(@TempDir Path tasksBase) {
        TodoStore store = new TodoStore(tasksBase, "session");
        Task task = store.create("available", "", null, Map.of());
        store.update(task.id(), task.withOwner(""));

        Optional<Task> claimed = store.claim(task.id(), "reviewer");

        assertEquals("reviewer", claimed.orElseThrow().owner().orElseThrow());
        assertEquals(TodoStatus.PENDING, claimed.orElseThrow().status(),
            "released claim writes owner before the separate in_progress update");
    }

    @Test
    void claimRechecksCurrentBlockersInsteadOfUsingAStaleSnapshot(@TempDir Path tasksBase) {
        TodoStore seed = new TodoStore(tasksBase, "session");
        Task blocker = seed.create("blocker", "", null, Map.of());
        Task blocked = seed.create("blocked", "", null, Map.of());
        assertTrue(seed.block(blocker.id(), blocked.id()));
        seed.update(blocker.id(), blocker.withStatus(TodoStatus.COMPLETED));
        TodoStore staleClaimer = new TodoStore(tasksBase, "session");
        TodoStore concurrentWriter = new TodoStore(tasksBase, "session");

        Task latestBlocker = concurrentWriter.get(blocker.id()).orElseThrow();
        concurrentWriter.update(blocker.id(), latestBlocker.withStatus(TodoStatus.PENDING));

        assertTrue(staleClaimer.claim(blocked.id(), "reviewer").isEmpty());
        assertTrue(new TodoStore(tasksBase, "session").get(blocked.id())
            .orElseThrow().owner().isEmpty());
    }

    @Test
    void concurrentClaimersCannotBothClaimTheSameTask(@TempDir Path tasksBase)
            throws Exception {
        TodoStore seed = new TodoStore(tasksBase, "session");
        Task task = seed.create("one owner", "", null, Map.of());
        TodoStore first = new TodoStore(tasksBase, "session");
        TodoStore second = new TodoStore(tasksBase, "session");
        CyclicBarrier start = new CyclicBarrier(2);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Optional<Task>> firstClaim = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return first.claim(task.id(), "alpha");
            });
            Future<Optional<Task>> secondClaim = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return second.claim(task.id(), "beta");
            });

            boolean alphaWon = firstClaim.get(5, TimeUnit.SECONDS).isPresent();
            boolean betaWon = secondClaim.get(5, TimeUnit.SECONDS).isPresent();
            assertTrue(alphaWon ^ betaWon);
        }

        String owner = new TodoStore(tasksBase, "session").get(task.id())
            .orElseThrow().owner().orElseThrow();
        assertTrue(owner.equals("alpha") || owner.equals("beta"));
        assertFalse(owner.isEmpty());
    }
}

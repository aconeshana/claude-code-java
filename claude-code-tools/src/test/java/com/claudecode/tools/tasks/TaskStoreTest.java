package com.claudecode.tools.tasks;

import org.apache.commons.lang3.Strings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskStoreTest {

    @Test
    void localAgentType_isRetainedAsRuntimeMetadataAndRemovedWithTask() {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.LOCAL_AGENT, "inspect repository");

        store.updateAgentType(task.id(), "general-purpose");

        assertEquals("general-purpose", store.agentType(task.id()).orElseThrow());
        store.remove(task.id());
        assertTrue(store.agentType(task.id()).isEmpty());
    }

    @Test
    void evictAfter_isStoredClearedAndRemovedWithTask() {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.LOCAL_AGENT, "explore repo");

        assertTrue(store.evictAfter(task.id()).isEmpty(),
            "a fresh task has no eviction deadline scheduled");

        Instant deadline = Instant.now().plusSeconds(30);
        store.setEvictAfter(task.id(), deadline);
        assertEquals(deadline, store.evictAfter(task.id()).orElseThrow());

        // Clearing (null) restores the not-scheduled state — how a retained
        // viewed task avoids removal.
        store.setEvictAfter(task.id(), null);
        assertTrue(store.evictAfter(task.id()).isEmpty());

        store.setEvictAfter(task.id(), deadline);
        store.remove(task.id());
        assertTrue(store.evictAfter(task.id()).isEmpty(),
            "removing a task also drops its eviction deadline");
    }

    @Test
    void persistedTasks_surviveAcrossInstances(@TempDir Path baseDir) {
        TaskStore first = new TaskStore(baseDir, "test-list");
        TaskState a = first.create(TaskType.LOCAL_BASH, "echo hi");
        TaskState b = first.create(TaskType.LOCAL_AGENT, "review file");

        // New instance pointed at the same base dir + list id should re-hydrate.
        TaskStore second = new TaskStore(baseDir, "test-list");

        assertEquals(2, second.size(), "second instance should rehydrate persisted tasks");
        assertTrue(second.get(a.id()).isPresent());
        assertEquals("echo hi", second.get(a.id()).get().description());
        assertEquals(TaskType.LOCAL_AGENT, second.get(b.id()).get().type());
    }

    @Test
    void statusUpdates_persistImmediately(@TempDir Path baseDir) {
        TaskStore store = new TaskStore(baseDir, "list");
        TaskState t = store.create(TaskType.LOCAL_BASH, "build");
        store.updateStatus(t.id(), TaskStatus.RUNNING);

        TaskStore reloaded = new TaskStore(baseDir, "list");
        assertEquals(TaskStatus.RUNNING, reloaded.get(t.id()).get().status());
    }

    @Test
    void sanitizesTaskListId_intoFilesystemSafePath(@TempDir Path baseDir) {
        TaskStore store = new TaskStore(baseDir, "team/with/slashes & spaces");
        TaskState t = store.create(TaskType.LOCAL_BASH, "x");

        // The dir should contain only sanitized chars.
        Path expectedDir = baseDir.resolve("team-with-slashes---spaces");
        assertTrue(Files.isDirectory(expectedDir),
            "expected sanitized dir at " + expectedDir);
        assertTrue(Files.isRegularFile(expectedDir.resolve(t.id() + ".json")));
    }

    @Test
    void inMemoryFactory_doesNotTouchFilesystem(@TempDir Path baseDir) {
        TaskStore store = TaskStore.inMemory();
        TaskState t = store.create(TaskType.LOCAL_AGENT, "ephemeral");
        assertEquals(1, store.size());
        // No files written anywhere under baseDir.
        try (var stream = Files.list(baseDir)) {
            assertEquals(0L, stream.count(), "in-memory store must not write files");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    // ── H-1: updateStatus atomicity + lost-race semantics ────────────────

    @Test
    void updateStatus_killVsComplete_race_singleWinner_noThrow_oneNotification() throws Exception {
        // Regression: updateStatus used to be get→validate→put (non-atomic).
        // A kill racing a completion could last-write-win (KILLED overwritten
        // by COMPLETED, listener fired twice) or throw IllegalStateException
        // up the loser's stack — which on the kill side propagated into the
// Lanterna GUI thread. compute serializes per task.
        for (int i = 0; i < 100; i++) {
            TaskStore store = TaskStore.inMemory();
            AtomicInteger notifications = new AtomicInteger();
            store.onCompletion(_ -> notifications.incrementAndGet());
            TaskState task = store.create(TaskType.LOCAL_BASH, "race");
            store.updateStatus(task.id(), TaskStatus.RUNNING);

            CyclicBarrier barrier = new CyclicBarrier(2);
            List<TaskStatus> observed = Collections.synchronizedList(new ArrayList<>());
            List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
            Runnable killer = () -> {
                try {
                    barrier.await();
                    observed.add(store.updateStatus(task.id(), TaskStatus.KILLED).status());
                } catch (Throwable t) { failures.add(t); }
            };
            Runnable completer = () -> {
                try {
                    barrier.await();
                    observed.add(store.updateStatus(task.id(), TaskStatus.COMPLETED).status());
                } catch (Throwable t) { failures.add(t); }
            };
            Thread a = new Thread(killer);
            Thread b = new Thread(completer);
            a.start(); b.start(); a.join(5000); b.join(5000);

            assertTrue(failures.isEmpty(), "no racer may throw, got: " + failures);
            TaskStatus finalStatus = store.get(task.id()).get().status();
            assertTrue(finalStatus == TaskStatus.KILLED || finalStatus == TaskStatus.COMPLETED);
            // Both racers observed the single winner — the loser saw the
            // winner's state, never its own.
            assertEquals(2, observed.size());
            assertEquals(finalStatus, observed.getFirst());
            assertEquals(finalStatus, observed.get(1));
            assertEquals(1, notifications.get(), "completion listener must fire exactly once");
        }
    }

    @Test
    void updateStatus_onTerminalTask_returnsCurrentStateWithoutThrowing() {
        TaskStore store = TaskStore.inMemory();
        TaskState t = store.create(TaskType.LOCAL_BASH, "x");
        store.updateStatus(t.id(), TaskStatus.RUNNING);
        store.updateStatus(t.id(), TaskStatus.COMPLETED);

        TaskState result = store.updateStatus(t.id(), TaskStatus.KILLED);

        assertEquals(TaskStatus.COMPLETED, result.status(), "lost race returns the winner's state");
        assertEquals(TaskStatus.COMPLETED, store.get(t.id()).get().status());
    }

    @Test
    void updateStatus_invalidTransitionFromNonTerminal_stillThrows() {
        TaskStore store = TaskStore.inMemory();
        TaskState t = store.create(TaskType.LOCAL_BASH, "x");
        // PENDING → COMPLETED is a programming error, not a benign race.
        assertThrows(IllegalStateException.class,
            () -> store.updateStatus(t.id(), TaskStatus.COMPLETED));
    }

    // ── event-driven terminal waiting ───────────────────────────────────

    @Test
    void awaitTerminal_unblocksWhenTaskCompletes() throws Exception {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.LOCAL_BASH, "wait");
        store.updateStatus(task.id(), TaskStatus.RUNNING);

        CompletableFuture<Optional<TaskState>> waiting = CompletableFuture.supplyAsync(() -> {
            try {
                return store.awaitTerminal(task.id(), Duration.ofSeconds(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        });

        assertFalse(waiting.isDone(), "running task should keep the waiter pending");
        store.updateStatus(task.id(), TaskStatus.COMPLETED);

        assertEquals(TaskStatus.COMPLETED,
            waiting.get(1, TimeUnit.SECONDS).orElseThrow().status());
    }

    @Test
    void awaitTerminal_returnsLatestRunningStateAtTimeout() throws Exception {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.LOCAL_BASH, "wait");
        store.updateStatus(task.id(), TaskStatus.RUNNING);

        Optional<TaskState> result = store.awaitTerminal(task.id(), Duration.ZERO);

        assertEquals(TaskStatus.RUNNING, result.orElseThrow().status());
    }

    @Test
    void awaitTerminal_returnsEmptyWhenTaskDisappears() throws Exception {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.LOCAL_BASH, "wait");
        store.updateStatus(task.id(), TaskStatus.RUNNING);

        CompletableFuture<Optional<TaskState>> waiting = CompletableFuture.supplyAsync(() -> {
            try {
                return store.awaitTerminal(task.id(), Duration.ofSeconds(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        });
        store.remove(task.id());

        assertTrue(waiting.get(1, TimeUnit.SECONDS).isEmpty());
    }

    // ── H-5/H-6: disjoint ID spaces + concurrent create ──────────────────

    @Test
    void inMemoryStore_assignsTypePrefixedRandomIds_neverNumeric() {
        // Regression: background tasks used to get sequential "1","2",... ids
        // colliding with the persistent task-list tools' ID space — TaskStop(1)
        // from a BashTool-returned id could kill an unrelated to-do entry.
        TaskStore store = TaskStore.inMemory();
        TaskState shell = store.create(TaskType.LOCAL_BASH, "bg shell");
        TaskState agent = store.create(TaskType.LOCAL_AGENT, "bg agent");

        assertTrue(Strings.CS.startsWith(shell.id(), "b"), shell.id());
        assertTrue(Strings.CS.startsWith(agent.id(), "a"), agent.id());
        assertEquals(9, shell.id().length());
        assertFalse(shell.id().chars().allMatch(Character::isDigit));
    }

    @Test
    void persistentStore_keepsSequentialIds(@TempDir Path baseDir) {
        TaskStore store = new TaskStore(baseDir, "list");
        assertEquals("1", store.create(TaskType.LOCAL_BASH, "a").id());
        assertEquals("2", store.create(TaskType.LOCAL_BASH, "b").id());
    }

    @Test
    void inMemoryStore_concurrentCreates_produceUniqueIds() throws Exception {
        TaskStore store = TaskStore.inMemory();
        int threads = 8, perThread = 50;
        Set<String> ids = ConcurrentHashMap.newKeySet();
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Thread> pool = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread th = new Thread(() -> {
                try {
                    barrier.await();
                    for (int i = 0; i < perThread; i++) {
                        ids.add(store.create(TaskType.LOCAL_BASH, "concurrent").id());
                    }
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            pool.add(th);
            th.start();
        }
        for (Thread th : pool) th.join(10_000);

        assertEquals(threads * perThread, ids.size(), "every create must yield a distinct id");
        assertEquals(threads * perThread, store.size(), "no create may overwrite another");
    }

    // ── remove (M-3 support) ──────────────────────────────────────────────

    @Test
    void remove_evictsTask_inMemoryAndOnDisk(@TempDir Path baseDir) {
        TaskStore store = new TaskStore(baseDir, "list");
        TaskState t = store.create(TaskType.LOCAL_BASH, "doomed");
        Path file = baseDir.resolve("list").resolve(t.id() + ".json");
        assertTrue(Files.isRegularFile(file));

        assertTrue(store.remove(t.id()).isPresent());

        assertTrue(store.get(t.id()).isEmpty());
        assertFalse(Files.exists(file));
        assertTrue(store.remove(t.id()).isEmpty(), "second remove is a no-op");
    }

    @Test
    void listByStatus_filtersCorrectly(@TempDir Path baseDir) {
        TaskStore store = new TaskStore(baseDir, "list");
        TaskState a = store.create(TaskType.LOCAL_BASH, "a");
        TaskState b = store.create(TaskType.LOCAL_BASH, "b");
        store.updateStatus(b.id(), TaskStatus.RUNNING);

        List<TaskState> pending = store.listByStatus(TaskStatus.PENDING);
        List<TaskState> running = store.listByStatus(TaskStatus.RUNNING);
        assertEquals(1, pending.size());
        assertEquals(a.id(), pending.getFirst().id());
        assertEquals(1, running.size());
        assertEquals(b.id(), running.getFirst().id());
    }
}

package com.claudecode.tools.tasks;

import com.claudecode.core.engine.SessionIdentity;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.runtime.tasks.TaskBoardPort;
import com.claudecode.tools.tasks.teammate.TeammateContext;
import com.claudecode.tools.tasks.teammate.TeammateContextHolder;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskBoardServiceTest {

    @AfterEach
    void clearRegistry() {
        TeamTaskListRegistry.instance().clearForTest();
        TeammateContextHolder.clear();
        SubprocessEnvironment.clearRuntimeOverrides();
    }

    @Test
    void taskListEnvironmentOverrideWinsOverTeamAndSessionSelection(@TempDir Path tasksBase) {
        SubprocessEnvironment.updateRuntime(Map.of(
            "CLAUDE_CODE_TASK_LIST_ID", "forced/list"));
        TodoStore fallback = new TodoStore(tasksBase, "session-a");
        TodoStore team = new TodoStore(tasksBase, "team-alpha");
        team.create("team task", "must not be selected", null, null);
        TeamTaskListRegistry.instance().register("alpha", team);
        TeamTaskListRegistry.instance().bindSession("session-a", "alpha");
        new TodoStore(tasksBase, "forced/list")
            .create("forced task", "selected by the override", null, null);
        TaskBoardService service = new TaskBoardService(fallback, () -> "session-a");

        TaskBoardPort.Snapshot snapshot = service.snapshot();

        assertEquals("forced-list", snapshot.listId());
        assertEquals(List.of("forced task"), snapshot.tasks().stream()
            .map(TaskBoardPort.TaskItem::subject).toList());
        service.close();
    }

    @Test
    void snapshotUsesTheSessionEffectiveTeamStore() {
        TodoStore fallback = TodoStore.inMemory();
        fallback.create("local", "fallback", null, null);
        TodoStore team = TodoStore.inMemory();
        team.create("shared", "team", null, null);
        TeamTaskListRegistry.instance().register("alpha", team);
        TeamTaskListRegistry.instance().bindSession("session-a", "alpha");
        TaskBoardService service = new TaskBoardService(fallback, () -> "session-a");

        TaskBoardPort.Snapshot snapshot = service.snapshot();
        team.create("later", "new", null, null);

        assertEquals(List.of("shared"), snapshot.tasks().stream()
            .map(TaskBoardPort.TaskItem::subject).toList());
        assertThrows(UnsupportedOperationException.class,
            () -> snapshot.tasks().add(snapshot.tasks().getFirst()));
    }

    @Test
    void activeTeammateContextWinsOverAnUnboundSession() {
        TodoStore fallback = TodoStore.inMemory();
        fallback.create("local", "fallback", null, null);
        TodoStore team = TodoStore.inMemory();
        team.create("shared", "team", null, null);
        TeamTaskListRegistry.instance().register("alpha", team);
        TeammateContextHolder.set(TeammateContext.builder()
            .agentId("agent-a")
            .teamId("alpha")
            .name("reviewer")
            .build());
        TaskBoardService service = new TaskBoardService(fallback, () -> "teammate-session");

        TaskBoardPort.Snapshot snapshot = service.snapshot();

        assertEquals(List.of("shared"), snapshot.tasks().stream()
            .map(TaskBoardPort.TaskItem::subject).toList());
        service.close();
    }

    @Test
    void internalMetadataUsesJavaScriptTruthiness() {
        TodoStore store = TodoStore.inMemory();
        store.create("hidden string", "", null, Map.of("_internal", "yes"));
        store.create("visible empty string", "", null, Map.of("_internal", ""));
        store.create("visible zero", "", null, Map.of("_internal", 0));
        TaskBoardService service = new TaskBoardService(store, () -> "session-a");

        assertEquals(List.of("visible empty string", "visible zero"),
            service.snapshot().tasks().stream().map(TaskBoardPort.TaskItem::subject).toList());
        service.close();
    }

    @Test
    void taskListNumberSortKeepsFileOrderWhenAnIdIsNotANumber(
            @TempDir Path tasksBase) throws IOException {
        Path taskDir = tasksBase.resolve("session-a");
        Files.createDirectories(taskDir);
        writeTask(taskDir.resolve("2stale.json"), "2stale", "first on disk");
        writeTask(taskDir.resolve("10.json"), "10", "second on disk");
        TodoStore store = new TodoStore(tasksBase, "session-a");
        TaskBoardService service = new TaskBoardService(store, () -> "session-a");

        assertEquals(List.of("2stale", "10"), service.snapshot().tasks().stream()
            .map(TaskBoardPort.TaskItem::id).toList());
        service.close();
    }

    @Test
    void taskListNumberSortAcceptsJavascriptHexNumbers(@TempDir Path tasksBase)
            throws IOException {
        Path taskDir = tasksBase.resolve("session-a");
        Files.createDirectories(taskDir);
        writeTask(taskDir.resolve("0x10.json"), "0x10", "sixteen");
        writeTask(taskDir.resolve("3.json"), "3", "three");
        TodoStore store = new TodoStore(tasksBase, "session-a");
        TaskBoardService service = new TaskBoardService(store, () -> "session-a");

        assertEquals(List.of("3", "0x10"), service.snapshot().tasks().stream()
            .map(TaskBoardPort.TaskItem::id).toList());
        service.close();
    }

    @Test
    void listenerFailureDoesNotPreventOtherListeners() throws Exception {
        TodoStore store = TodoStore.inMemory();
        TaskBoardService service = new TaskBoardService(store, () -> "session-a");
        AtomicInteger healthyCalls = new AtomicInteger();
        AutoCloseable broken = service.subscribe(_ -> { throw new IllegalStateException("boom"); });
        AutoCloseable healthy = service.subscribe(_ -> healthyCalls.incrementAndGet());

        store.create("task", "work", null, null);
        service.publishChanged("session-a");

        assertEquals(1, healthyCalls.get());
        broken.close();
        healthy.close();
    }

    @Test
    void expandIntentIsIndependentFromSnapshotChanges() throws Exception {
        TaskBoardService service = new TaskBoardService(TodoStore.inMemory(), () -> "session-a");
        AtomicInteger intents = new AtomicInteger();
        AtomicInteger snapshots = new AtomicInteger();
        AutoCloseable intentSubscription = service.subscribeIntents(_ -> intents.incrementAndGet());
        AutoCloseable snapshotSubscription = service.subscribe(_ -> snapshots.incrementAndGet());

        service.publishExpand("session-a");

        assertEquals(1, intents.get());
        assertEquals(0, snapshots.get());
        intentSubscription.close();
        snapshotSubscription.close();
    }

    @Test
    void sessionIdentitySwitchPublishesTheNewListImmediately(@TempDir Path tasksBase)
            throws Exception {
        SessionIdentity identity = SessionIdentity.of("session-a");
        TodoStore first = new TodoStore(tasksBase, "session-a");
        first.create("first", "", null, null);
        TodoStore second = new TodoStore(tasksBase, "session-b");
        second.create("second", "", null, null);
        TaskBoardService service = new TaskBoardService(first, identity, () -> true);
        AtomicReference<TaskBoardPort.Snapshot> observed = new AtomicReference<>();
        CountDownLatch changed = new CountDownLatch(1);
        AutoCloseable subscription = service.subscribe(snapshot -> {
            if (Strings.CS.equals("session-b", snapshot.listId())) {
                observed.set(snapshot);
                changed.countDown();
            }
        });

        identity.set("session-b");

        assertTrue(changed.await(1, TimeUnit.SECONDS));
        assertEquals(List.of("second"), observed.get().tasks().stream()
            .map(TaskBoardPort.TaskItem::subject).toList());
        subscription.close();
        service.close();
    }

    @Test
    void filesystemChangesWakeAnEmptyBoardWithoutWaitingForFallbackPoll(
            @TempDir Path tasksBase) throws Exception {
        TodoStore current = new TodoStore(tasksBase, "session-a");
        TaskBoardService service = new TaskBoardService(
            current, () -> "session-a", () -> true);
        CountDownLatch changed = new CountDownLatch(1);
        AtomicReference<TaskBoardPort.Snapshot> observed = new AtomicReference<>();
        AutoCloseable subscription = service.subscribe(snapshot -> {
            if (!snapshot.tasks().isEmpty()) {
                observed.set(snapshot);
                changed.countDown();
            }
        });

        new TodoStore(tasksBase, "session-a").create("external", "", null, null);

        assertTrue(changed.await(4, TimeUnit.SECONDS));
        assertEquals("external", observed.get().tasks().getFirst().subject());
        subscription.close();
        service.close();
    }

    @Test
    void subscribingDoesNotSilentlyConsumeAChangeAfterTheInitialSnapshot() throws Exception {
        TodoStore store = TodoStore.inMemory();
        store.create("initial", "", null, null);
        TaskBoardService service = new TaskBoardService(
            store, () -> "session-a", () -> true);
        service.snapshot();
        store.create("between snapshot and subscribe", "", null, null);
        AtomicReference<TaskBoardPort.Snapshot> observed = new AtomicReference<>();
        AutoCloseable subscription = service.subscribe(observed::set);

        service.publishChanged("session-a");

        assertEquals(List.of("initial", "between snapshot and subscribe"),
            observed.get().tasks().stream().map(TaskBoardPort.TaskItem::subject).toList());
        subscription.close();
        service.close();
    }

    private static void writeTask(Path path, String id, String subject) throws IOException {
        Files.writeString(path, """
            {
              "id": "%s",
              "subject": "%s",
              "description": "",
              "status": "pending",
              "blocks": [],
              "blockedBy": []
            }
            """.formatted(id, subject));
    }
}

package com.claudecode.tools.workflows;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.queue.MessageQueueManager;
import com.claudecode.core.queue.QueuePriority;
import com.claudecode.core.queue.QueuedCommand;
import com.claudecode.tools.tasks.TaskNotificationBridge;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskState;
import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.tools.tasks.TaskStore;
import com.claudecode.tools.tasks.TaskType;
import com.claudecode.core.serialization.JsonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowTaskTest {

    @TempDir Path temp;

    @Test
    void completionWritesSnapshotAndOutputButDropsWorkflowLogProgress() throws Exception {
        WorkflowTaskFixture fixture = fixture(_ -> WorkflowAgentResult.of("done"),
            "log('hello'); return await agent('work');");

        fixture.task().start();
        awaitStatus(fixture.registry(), fixture.state().id(), TaskStatus.COMPLETED);

        assertTrue(Files.isRegularFile(fixture.run().runFile()));
        assertTrue(Files.isRegularFile(fixture.run().outputFile()));
        var snapshot = JsonUtils.parseTree(Files.readString(fixture.run().runFile()));
        var output = JsonUtils.parseTree(Files.readString(fixture.run().outputFile()));
        assertEquals("completed", snapshot.path("status").asText());
        assertFalse(snapshot.has("phases"),
            "2.1.197 omits the phases property when metadata declares no phases");
        assertTrue(snapshot.path("timestamp").asText()
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"),
            "2.1.197 persists workflow timestamps at JavaScript Date millisecond precision");
        assertEquals(0, snapshot.path("workflowProgress").findValuesAsText("type").stream()
            .filter("workflow_log"::equals).count());
        assertEquals(0, output.path("workflowProgress").findValuesAsText("type").stream()
            .filter("workflow_log"::equals).count());
        assertEquals(1, output.path("logs").size());
        assertEquals("hello", output.path("logs").get(0).asText());
    }

    @Test
    void failureWritesSnapshotButNoTaskOutput() throws Exception {
        WorkflowTaskFixture fixture = fixture(_ -> {
            throw new IllegalStateException("boom");
        }, "return await agent('work');");

        fixture.task().start();
        awaitStatus(fixture.registry(), fixture.state().id(), TaskStatus.FAILED);

        assertTrue(Files.isRegularFile(fixture.run().runFile()));
        assertFalse(Files.exists(fixture.run().outputFile()));
        assertEquals("failed", JsonUtils.parseTree(Files.readString(fixture.run().runFile()))
            .path("status").asText());
    }

    @Test
    void terminalPersistenceFailureDoesNotLeaveTheTaskRunning() throws Exception {
        Path blockedParent = temp.resolve("blocked-workflow-parent");
        Files.writeString(blockedParent, "not a directory");
        WorkflowTaskFixture fixture = fixture(_ -> WorkflowAgentResult.of("done"),
            "return await agent('work');", blockedParent.resolve("wf_test-run.json"));

        fixture.task().start();
        awaitStatus(fixture.registry(), fixture.state().id(), TaskStatus.COMPLETED, 2);

        assertEquals(TaskStatus.COMPLETED,
            fixture.store().get(fixture.run().runId()).orElseThrow().status());
        assertFalse(Files.exists(fixture.run().runFile()));
    }

    @Test
    void workflowCompletionNotificationCarriesFailuresUsageAndNextPriority() throws Exception {
        WorkflowTaskFixture fixture = fixture(request ->
            Strings.CS.equals("bad", request.prompt())
                ? WorkflowAgentResult.apiError("rate limited", 3, 0, 4, 2)
                : WorkflowAgentResult.of("done", 5, 1, 6, 3), """
                    const failed = await agent('bad');
                    const completed = await agent('good');
                    return {failed, completed};
                    """);
        MessageQueueManager queue = new MessageQueueManager();
        new TaskNotificationBridge(queue, fixture.registry()).register();

        fixture.task().start();
        awaitStatus(fixture.registry(), fixture.state().id(), TaskStatus.COMPLETED);
        awaitNotified(fixture.registry(), fixture.state().id());

        QueuedCommand notification = queue.peek();
        assertEquals(QueuePriority.NEXT, notification.priority());
        assertTrue(Strings.CS.contains(notification.text(), "<agent_count>2</agent_count>"),
            notification.text());
        assertTrue(Strings.CS.contains(notification.text(),
            "<failures>[bad] failed: rate limited</failures>"), notification.text());
    }

    @Test
    void killWritesSnapshotButNoTaskOutput() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        WorkflowTaskFixture fixture = fixture(request -> {
            entered.countDown();
            while (!request.parentContext().abortController().isAborted()) {
                Thread.sleep(5);
            }
            throw new InterruptedException("stopped");
        }, "return await agent('work');");

        fixture.task().start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertTrue(fixture.task().kill());

        assertTrue(Files.isRegularFile(fixture.run().runFile()));
        assertFalse(Files.exists(fixture.run().outputFile()));
        assertEquals(TaskStatus.KILLED,
            fixture.registry().store().get(fixture.state().id()).orElseThrow().status());
    }

    @Test
    void pauseKeepsOnlyLiveMemoryStateAndWritesNoSnapshotOrOutput() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        WorkflowTaskFixture fixture = fixture(request -> {
            entered.countDown();
            while (!request.parentContext().abortController().isAborted()) {
                Thread.sleep(5);
            }
            throw new InterruptedException("paused");
        }, "return await agent('work');");

        fixture.task().start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertTrue(fixture.task().pause());

        assertEquals(TaskStatus.PAUSED,
            fixture.registry().store().get(fixture.state().id()).orElseThrow().status());
        assertEquals(TaskStatus.PAUSED,
            fixture.store().get(fixture.run().runId()).orElseThrow().status());
        assertFalse(Files.exists(fixture.run().runFile()));
        assertFalse(Files.exists(fixture.run().outputFile()));
    }


    @Test
    void pauseIsTheUsersOwnActionAndIsNeverAnnouncedToTheModel() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        WorkflowTaskFixture fixture = fixture(request -> {
            entered.countDown();
            while (!request.parentContext().abortController().isAborted()) {
                Thread.sleep(5);
            }
            throw new InterruptedException("paused");
        }, "return await agent('work');");
        MessageQueueManager queue = new MessageQueueManager();
        new TaskNotificationBridge(queue, fixture.registry()).register();

        fixture.task().start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertTrue(fixture.task().pause());

        assertTrue(fixture.registry().store().get(fixture.state().id()).orElseThrow().notified(),
            "released parks the run as already-announced in the same update");
        assertEquals(List.of(), queue.snapshot().stream().map(QueuedCommand::mode).toList());
    }

    @Test
    void killIsTheUsersOwnActionAndIsNeverAnnouncedToTheModel() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        WorkflowTaskFixture fixture = fixture(request -> {
            entered.countDown();
            while (!request.parentContext().abortController().isAborted()) {
                Thread.sleep(5);
            }
            throw new InterruptedException("stopped");
        }, "return await agent('work');");
        MessageQueueManager queue = new MessageQueueManager();
        new TaskNotificationBridge(queue, fixture.registry()).register();

        fixture.task().start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertTrue(fixture.task().kill());

        assertTrue(fixture.registry().store().get(fixture.state().id()).orElseThrow().notified());
        assertEquals(List.of(), queue.snapshot().stream().map(QueuedCommand::mode).toList());
    }

    private WorkflowTaskFixture fixture(WorkflowAgentExecutor executor, String body) {
        return fixture(executor, body, null);
    }

    private WorkflowTaskFixture fixture(WorkflowAgentExecutor executor, String body,
                                         Path runFileOverride) {
        WorkflowCatalog catalog = new WorkflowCatalog(temp.resolve("user"), List.of(), List::of);
        WorkflowRuntime runtime = new WorkflowRuntime(executor, catalog, 2);
        WorkflowDefinition definition = new WorkflowDefinition(
            new WorkflowMetadata("test", "Test", "Test workflow", null, List.of()),
            body, body, WorkflowSource.USER, temp.resolve("test.js"), null, false, false);
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        TaskState state = registry.store().create(TaskType.LOCAL_WORKFLOW, "Test workflow");
        WorkflowRun run = WorkflowRun.builder("wf_test-run", state.id(), TaskStatus.PENDING)
            .workflowName("test")
            .summary("Test workflow")
            .script(body)
            .scriptPath(temp.resolve("test.js"))
            .transcriptDir(temp.resolve("transcripts"))
            .outputFile(temp.resolve("outputs").resolve(state.id() + ".json"))
            .runFile(runFileOverride == null
                ? temp.resolve("workflows").resolve("wf_test-run.json")
                : runFileOverride)
            .timestamp(Instant.now())
            .startTime(System.currentTimeMillis())
            .defaultModel("sonnet")
            .title("Test")
            .build();
        WorkflowRunStore store = new WorkflowRunStore();
        store.put(run);
        ToolExecutionContext context = ToolExecutionContext.builder(new AbortController(), "session")
            .workingDirectory(temp.toString()).progressSink(_ -> {}).build();
        WorkflowTask task = new WorkflowTask(runtime, definition, null, context, registry, store, run);
        registry.registerWorkflow(task);
        return new WorkflowTaskFixture(task, registry, store, state, run);
    }

    private static void awaitStatus(TaskRegistry registry, String taskId, TaskStatus expected)
            throws InterruptedException {
        awaitStatus(registry, taskId, expected, 15);
    }

    private static void awaitStatus(TaskRegistry registry, String taskId, TaskStatus expected,
                                    long timeoutSeconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            if (registry.store().get(taskId).orElseThrow().status() == expected) return;
            Thread.sleep(10);
        }
        assertEquals(expected, registry.store().get(taskId).orElseThrow().status());
    }

    private static void awaitNotified(TaskRegistry registry, String taskId)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (registry.store().get(taskId).orElseThrow().notified()) return;
            Thread.sleep(10);
        }
        assertTrue(registry.store().get(taskId).orElseThrow().notified());
    }

    private record WorkflowTaskFixture(WorkflowTask task, TaskRegistry registry,
                                       WorkflowRunStore store, TaskState state,
                                       WorkflowRun run) {}
}

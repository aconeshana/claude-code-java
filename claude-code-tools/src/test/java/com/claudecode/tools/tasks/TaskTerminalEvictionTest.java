package com.claudecode.tools.tasks;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.queue.MessageQueueManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.Strings;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskTerminalEvictionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void reset() {
        TaskOutputPaths.resetForTest();
        TaskRegistry.resetGlobalForTest();
    }

    @Test
    void notificationReleasesHeavyHandleButKeepsTaskOutputReadableDuringGrace(@TempDir Path dir)
            throws Exception {
        TaskOutputPaths.configureForTest(dir, "session", dir.resolve("project"));
        TaskStore store = TaskStore.inMemory();
        TaskRegistry registry = new TaskRegistry(store);
        MessageQueueManager queue = new MessageQueueManager();
        new TaskNotificationBridge(queue, registry).register();

        TaskState task = store.create(TaskType.LOCAL_BASH, "large background build");
        Path output = TaskOutputPaths.outputPath(task.id());
        Files.createDirectories(output.getParent());
        Files.writeString(output, "completed output\n");
        registry.registerShell(new LocalShellTask(task, "build", store, output));

        store.updateStatus(task.id(), TaskStatus.RUNNING);
        store.updateStatus(task.id(), TaskStatus.COMPLETED);

        assertTrue(store.get(task.id()).orElseThrow().notified(),
            "successful delivery must atomically record notification ownership");
        assertTrue(registry.getShellHandle(task.id()).isEmpty(),
            "terminal Process handles should be released immediately");
        assertTrue(store.get(task.id()).isPresent(),
            "lightweight task metadata must survive the TaskOutput grace period");

        ObjectNode input = MAPPER.createObjectNode()
            .put("task_id", task.id())
            .put("block", false)
            .put("timeout", 0);
        String result = new TaskOutputTool(store).call(
            input, ToolExecutionContext.of(new AbortController(), "test"));
        assertTrue(Strings.CS.contains(result, "completed output"), result);

        forceEvictionSweep(registry, Long.MAX_VALUE);

        assertTrue(store.get(task.id()).isEmpty(),
            "terminal notified metadata must be evicted after its grace deadline");
        assertTrue(Files.exists(output),
            "heap eviction must not delete the output path advertised to the model");
    }

    @Test
    void unnotifiedTerminalTaskIsNeverEvicted(@TempDir Path dir) throws Exception {
        TaskOutputPaths.configureForTest(dir, "session", dir.resolve("project"));
        TaskStore store = TaskStore.inMemory();
        TaskRegistry registry = new TaskRegistry(store);
        TaskState task = store.create(TaskType.LOCAL_BASH, "not-yet-delivered");
        store.updateStatus(task.id(), TaskStatus.RUNNING);
        store.updateStatus(task.id(), TaskStatus.COMPLETED);

        forceEvictionSweep(registry, Long.MAX_VALUE);

        assertFalse(store.get(task.id()).isEmpty(),
            "notification loss must never be hidden by terminal-task eviction");
    }


    @Test
    void pausedWorkflowSurvivesEvictionBecauseItIsTheOnlyResumeHandle(@TempDir Path dir)
            throws Exception {
        TaskOutputPaths.configureForTest(dir, "session", dir.resolve("project"));
        TaskStore store = TaskStore.inMemory();
        TaskRegistry registry = new TaskRegistry(store);
        new TaskNotificationBridge(new MessageQueueManager(), registry).register();
        TaskState task = store.create(TaskType.LOCAL_WORKFLOW, "paused workflow");
        store.updateStatus(task.id(), TaskStatus.RUNNING);
        store.updateStatusAndMarkNotified(task.id(), TaskStatus.PAUSED);

        forceEvictionSweep(registry, Long.MAX_VALUE);

        assertTrue(store.get(task.id()).isPresent(),
            "evicting a paused run would silently destroy its only resume handle");
    }

    private static void forceEvictionSweep(TaskRegistry registry, long nowMillis) throws Exception {
        Method sweep = TaskRegistry.class.getDeclaredMethod(
            "evictEligibleTerminalTasks", long.class);
        sweep.setAccessible(true);
        sweep.invoke(registry, nowMillis);
    }
}

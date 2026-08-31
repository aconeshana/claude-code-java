package com.claudecode.tools.tasks;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.tools.agent.NoOpSubAgentFactory;
import com.claudecode.tools.agent.SubAgentRequest;
import com.claudecode.tools.tasks.teammate.TeammateContext;
import com.claudecode.tools.tasks.teammate.TeammateMailbox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the in-process teammate handle registry in {@link TaskRegistry}
 * ({@code registerTeammate} / {@code getTeammateHandle} / {@code killTeammate}).
 *
 * <p>matches the existing {@code LocalAgentTask} registry tests: a teammate is
 * just another live handle type. {@code killTeammate} must be a no-op (returning
 * {@code false}) when there is no handle, and must delegate to the handle's
 * {@code kill} — which itself returns {@code false} unless the task is
 * currently {@code RUNNING} (matching {@code killAgent} / {@code killAsyncAgent}).
 */
class TaskRegistryTeammateTest {

    private static ToolExecutionContext testContext() {
        return ToolExecutionContext.of(new AbortController(), "test-session");
    }

    private static TeammateContext teammateContext(String agentId) {
        return TeammateContext.builder()
            .agentId(agentId)
            .abortController(new AbortController())
            .build();
    }

    private static InProcessTeammateTask newHandle(TaskStore store, TaskState task) {
        SubAgentRequest req = SubAgentRequest.builder().prompt("x").parentContext(testContext()).build();
        return new InProcessTeammateTask(task, store, new NoOpSubAgentFactory(), req, teammateContext(task.id()));
    }

    @AfterEach
    void resetMailbox() {
        TeammateMailbox.instance().clearAll();
    }

    @Test
    void unknownTeammateKillIsNoOp() {
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        assertFalse(registry.killTeammate("does-not-exist"));
        assertFalse(registry.getTeammateHandle("does-not-exist").isPresent());
    }

    @Test
    void registerAndGetTeammateHandle() {
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.IN_PROCESS_TEAMMATE, "Teammate");
        InProcessTeammateTask handle = newHandle(store, task);

        registry.registerTeammate(handle);
        assertEquals(handle, registry.getTeammateHandle(task.id()).orElseThrow());
    }

    @Test
    void injectsScheduledPromptOnlyIntoActiveTeammate() throws Exception {
        TaskStore store = TaskStore.inMemory();
        TaskRegistry registry = new TaskRegistry(store);
        TaskState task = store.create(TaskType.IN_PROCESS_TEAMMATE, "Teammate");
        InProcessTeammateTask handle = newHandle(store, task);
        registry.registerTeammate(handle);

        assertFalse(registry.injectUserMessageToActiveTeammate(task.id(), "before start"));

        handle.start();
        try {
            assertTrue(registry.injectUserMessageToActiveTeammate(task.id(), "scheduled prompt"));
            assertFalse(registry.injectUserMessageToActiveTeammate("missing-agent", "scheduled prompt"));
        } finally {
            handle.stop();
            Thread runner = handle.runnerThreadForTest();
            if (runner != null) runner.join(2_000);
        }
    }

    @Test
    void killRunningTeammateTransitionsToKilled() {
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.IN_PROCESS_TEAMMATE, "Teammate");
        store.updateStatus(task.id(), TaskStatus.RUNNING);
        InProcessTeammateTask handle = newHandle(store, task);
        registry.registerTeammate(handle);

        assertTrue(registry.killTeammate(task.id()));
        assertEquals(TaskStatus.KILLED, store.get(task.id()).get().status());
    }

    @Test
    void killNonRunningTeammateIsNoOp() {
        // A teammate whose task was created but never started (still PENDING)
// must not be force-killed — kill reflects the not-RUNNING guard.
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.IN_PROCESS_TEAMMATE, "Teammate");
        // PENDING, not RUNNING.
        InProcessTeammateTask handle = newHandle(store, task);
        registry.registerTeammate(handle);

        assertFalse(registry.killTeammate(task.id()),
            "killTeammate on a non-running task must be a no-op (false)");
        assertEquals(TaskStatus.PENDING, store.get(task.id()).get().status());
    }
}

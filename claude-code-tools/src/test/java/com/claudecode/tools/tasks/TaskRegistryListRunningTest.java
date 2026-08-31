package com.claudecode.tools.tasks;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.tools.agent.NoOpSubAgentFactory;
import com.claudecode.tools.agent.SubAgentRequest;
import com.claudecode.tools.tasks.teammate.TeammateContext;
import com.claudecode.tools.tasks.teammate.TeammateMailbox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link TaskRegistry#listRunningTeammates} — the method the REPL teammate-view
 * navigation uses to enumerate active teammates in spinner-tree order (sorted by display name then
 * task id).
 */
@Timeout(20)
class TaskRegistryListRunningTest {

    private final TeammateMailbox mailbox = TeammateMailbox.instance();

    @AfterEach
    void resetMailbox() {
        mailbox.clearAll();
    }

    private static ToolExecutionContext testContext() {
        return ToolExecutionContext.of(new AbortController(), "test-session");
    }

    private static TeammateContext context(String agentId, String name) {
        return TeammateContext.builder()
            .agentId(agentId)
            .teamId("team-list")
            .abortController(new AbortController())
            .name(name)
            .build();
    }

    private static InProcessTeammateTask started(TaskRegistry registry, TaskStore store, String name) {
        TaskState task = store.create(TaskType.IN_PROCESS_TEAMMATE, "Teammate");
        SubAgentRequest req = SubAgentRequest.builder().prompt("x").parentContext(testContext()).build();
        InProcessTeammateTask handle =
            new InProcessTeammateTask(task, store, new NoOpSubAgentFactory(), req, context(task.id(), name));
        registry.registerTeammate(handle);
        handle.start();
        return handle;
    }

    @Test
    void excludesNonStartedHandles() {
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.IN_PROCESS_TEAMMATE, "Teammate");
        SubAgentRequest req = SubAgentRequest.builder().prompt("x").parentContext(testContext()).build();
        InProcessTeammateTask handle =
            new InProcessTeammateTask(task, store, new NoOpSubAgentFactory(), req, context(task.id(), "alpha"));
        registry.registerTeammate(handle);

// Not started → runnerThread is null → isActive is false → excluded.
        assertTrue(registry.listRunningTeammates().isEmpty());
    }

    @Test
    void returnsActiveHandlesSortedByName() throws Exception {
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        TaskStore store = TaskStore.inMemory();

        InProcessTeammateTask zulu = started(registry, store, "zulu");
        InProcessTeammateTask alpha = started(registry, store, "alpha");

        try {
            List<InProcessTeammateTask> running = registry.listRunningTeammates();
            assertEquals(2, running.size(), "both started teammates should be listed");
            // Sorted alphabetically by display name: alpha before zulu.
            assertEquals("alpha", running.getFirst().name());
            assertEquals("zulu", running.get(1).name());
        } finally {
            zulu.stop();
            alpha.stop();
        }
    }
}

package com.claudecode.ui.lanterna.repl;

import com.claudecode.tools.tasks.LocalAgentTask;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskState;
import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.tools.tasks.TaskStore;
import com.claudecode.tools.tasks.TaskType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalAgentInputRouterTest {

    @Test
    void runningAgentInputIsShownImmediatelyAndQueuedOnlyForThatAgent() {
        TaskStore store = TaskStore.inMemory();
        TaskRegistry registry = new TaskRegistry(store);
        TaskState state = store.create(TaskType.LOCAL_AGENT, "investigate");
        store.updateStatus(state.id(), TaskStatus.RUNNING);
        LocalAgentTask handle = new LocalAgentTask(state, store);
        registry.registerAgent(handle);
        List<String> displayed = new ArrayList<>();

        LocalAgentInputRouter router = new LocalAgentInputRouter(
            registry, (_, _, _, _) -> { }, () -> null,
            (taskId, text) -> displayed.add(taskId + ":" + text), _ -> { });

        assertTrue(router.submit(state.id(), "继续"));
        assertEquals(List.of(state.id() + ":继续"), displayed);
        assertEquals(List.of("继续"), registry.drainAgentMessages(state.id()));
    }

    @Test
    void terminalAgentInputUsesUserInitiatedResume() {
        TaskStore store = TaskStore.inMemory();
        TaskRegistry registry = new TaskRegistry(store);
        TaskState state = store.create(TaskType.LOCAL_AGENT, "investigate");
        store.updateStatus(state.id(), TaskStatus.KILLED);
        AtomicReference<String> resumed = new AtomicReference<>();

        LocalAgentInputRouter router = new LocalAgentInputRouter(
            registry,
            (agentId, prompt, _, userInitiated) -> {
                assertTrue(userInitiated);
                resumed.set(agentId + ":" + prompt);
            },
            () -> null, (_, _) -> { }, _ -> { });

        assertTrue(router.submit(state.id(), "继续执行"));
        assertEquals(state.id() + ":继续执行", resumed.get());
    }

    @Test
    void missingAgentDoesNotStealLeaderInput() {
        LocalAgentInputRouter router = new LocalAgentInputRouter(
            new TaskRegistry(TaskStore.inMemory()), (_, _, _, _) -> { }, () -> null,
            (_, _) -> { }, _ -> { });

        assertFalse(router.submit("missing", "hello"));
    }
}

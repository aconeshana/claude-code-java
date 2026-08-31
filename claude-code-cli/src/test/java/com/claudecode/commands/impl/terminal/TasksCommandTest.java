package com.claudecode.commands.impl.terminal;


import org.apache.commons.lang3.Strings;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.core.message.Usage;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.tools.tasks.TaskStore;
import com.claudecode.tools.tasks.TaskType;
import com.claudecode.commands.testing.ProviderTestCommandPorts;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link TasksCommand}. matches the launcher-present /
 * launcher-absent split established by {@code AgentsCommandTest} /
 * {@code PermissionsCommandTest}. Uses an in-memory {@link TaskStore} via
 * {@link TaskRegistry} so no test ever touches the real {@code ~/.claude/tasks}.
 */
class TasksCommandTest {

    private static CommandContext ctxWithTasksDialogLauncher(
            Runnable tasksDialogLauncher, TaskRegistry registry) {
        return CommandContext.builder(
            "m", List::of, () -> {}, _ -> {},
            () -> Usage.EMPTY, _ -> 0.0, ".", false)
            .currentSessionId(() -> null)
            .toolingCommands(ProviderTestCommandPorts.tasks(registry))
            .tasksDialogLauncher(tasksDialogLauncher)
            .build();
    }

    @Test
    void execute_withLauncher_delegatesAndSkips() {
        AtomicBoolean called = new AtomicBoolean(false);
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        CommandContext ctx = ctxWithTasksDialogLauncher(() -> called.set(true), registry);

        CommandResult r = new TasksCommand().execute(ctx, "");

        assertTrue(called.get());
        assertEquals("", r.output());
        assertTrue(r.silent());
    }

    @Test
    void execute_withoutLauncher_emptyStore_showsEmptyMessage() {
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        CommandContext ctx = ctxWithTasksDialogLauncher(null, registry);

        CommandResult r = new TasksCommand().execute(ctx, "");

        assertTrue(Strings.CS.contains(r.output(), "No background tasks in this session"), r.output());
    }

    @Test
    void execute_withoutLauncher_killedGroupIsListed() {
        TaskStore store = TaskStore.inMemory();
        var t = store.create(TaskType.LOCAL_BASH, "doomed command");
        store.updateStatus(t.id(), TaskStatus.RUNNING);
        store.updateStatus(t.id(), TaskStatus.KILLED);
        TaskRegistry registry = new TaskRegistry(store);
        CommandContext ctx = ctxWithTasksDialogLauncher(null, registry);

        CommandResult r = new TasksCommand().execute(ctx, "");

        assertTrue(Strings.CS.contains(r.output(), "Killed"),
            "KILLED tasks count into the total and must be listed: " + r.output());
        assertTrue(Strings.CS.contains(r.output(), "doomed command"), r.output());
    }

    @Test
    void execute_withoutLauncher_listsFromRegistry() {
        TaskStore store = TaskStore.inMemory();
        store.create(TaskType.LOCAL_BASH, "npm run build");
        TaskRegistry registry = new TaskRegistry(store);
        CommandContext ctx = ctxWithTasksDialogLauncher(null, registry);

        CommandResult r = new TasksCommand().execute(ctx, "");

        assertTrue(Strings.CS.contains(r.output(), "npm run build"), r.output());
        assertTrue(Strings.CS.contains(r.output(), "Pending"), r.output());
    }

    @Test
    void nameAndAliases_matchTsMetadata() {
        TasksCommand cmd = new TasksCommand();
        assertEquals("tasks", cmd.name());
        assertEquals(List.of("bashes"), cmd.aliases());
        assertEquals("List and manage background tasks", cmd.description());
    }
}

package com.claudecode.commands.impl.terminal;

import com.claudecode.commands.impl.session.RewindCommand;
import com.claudecode.core.message.Usage;

import org.apache.commons.lang3.Strings;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.tools.tasks.TaskStore;
import com.claudecode.tools.tasks.TaskType;
import com.claudecode.commands.testing.ProviderTestCommandPorts;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SmallCommandsTest {

    @Test
    void commandContextUsesLiveModelWhenSupplied() {
        AtomicReference<String> liveModel = new AtomicReference<>("sonnet");
        CommandContext context = CommandContext.builder(
            "fallback", List::of, () -> {}, _ -> {},
            () -> Usage.EMPTY, _ -> 0.0, ".", false)
            .modelSupplier(liveModel::get)
            .build();

        assertEquals("sonnet", context.session().model());
        liveModel.set("opus");
        assertEquals("opus", context.session().model());
    }

    // ── /rewind ─────────────────────────────────────────────────────────────

    @Test
    void rewind_withoutLauncher_isStillSilent() {

        // openMessageSelector; a missing launcher must not invent output.
        CommandResult r = new RewindCommand().execute(CommandContext.minimal(), "");
        assertTrue(r.silent());
        assertEquals("", r.output());
    }

    @Test
    void rewind_withLauncher_invokesItAndSkips() {
        // When openMessageSelector is wired (interactive REPL path) the command


        // {type: 'skip'} so nothing lands in the transcript.
        AtomicInteger fired = new AtomicInteger();
        CommandContext base = CommandContext.minimal();
        CommandContext wired = CommandContext.builder(
            base.session().model(), base.session().messagesSupplier(), base.session().clearMessages(),
            base.session().setModel(), base.session().usageSupplier(), base.session().costCalculator(),
            base.session().workingDirectory(), base.session().remoteMode())
            .openMessageSelector(fired::incrementAndGet)
            .build();
        CommandResult r = new RewindCommand().execute(wired, "");
        assertEquals(1, fired.get(), "launcher should have been invoked exactly once");
        assertTrue(r.silent(), "result should be silent so transcript stays untouched");
    }

    // ── /tasks ──────────────────────────────────────────────────────────────

    @Test
    void tasks_emptyStore_returnsFriendlyMessage(@TempDir Path baseDir) {
        TaskStore store = new TaskStore(baseDir, "test");
        CommandContext context = withTasks(new TaskRegistry(store));
        CommandResult r = new TasksCommand().execute(context, "");
        assertTrue(Strings.CS.contains(r.output(), "No background tasks"));
    }

    @Test
    void tasks_groupsByStatusAndShowsAge(@TempDir Path baseDir) {
        TaskStore store = new TaskStore(baseDir, "test");
        var a = store.create(TaskType.LOCAL_BASH, "echo hello world");
        var b = store.create(TaskType.LOCAL_AGENT, "do research");
        store.updateStatus(b.id(), TaskStatus.RUNNING);

        CommandResult r = new TasksCommand().execute(withTasks(new TaskRegistry(store)), "");
        assertTrue(Strings.CS.contains(r.output(), "Running"));
        assertTrue(Strings.CS.contains(r.output(), "Pending"));
        assertTrue(Strings.CS.contains(r.output(), a.id()));
        assertTrue(Strings.CS.contains(r.output(), b.id()));
        assertTrue(Strings.CS.contains(r.output(), "echo hello world"));
    }

    // ── /keybindings ────────────────────────────────────────────────────────

    private static CommandContext withTasks(TaskRegistry registry) {
        CommandContext base = CommandContext.minimal();
        return CommandContext.builder(base.session().model(), base.session().messagesSupplier(),
            base.session().clearMessages(), base.session().setModel(), base.session().usageSupplier(),
            base.session().costCalculator(), base.session().workingDirectory(), base.session().remoteMode())
            .toolingCommands(ProviderTestCommandPorts.tasks(registry))
            .build();
    }

    @Test
    void keybindings_enabledByDefault(@TempDir Path dir) {
        KeybindingsCommand cmd = new KeybindingsCommand(dir.resolve("kb.json"));
        assertTrue(cmd.isAvailable(CommandContext.minimal()));

        CommandResult r = cmd.execute(CommandContext.minimal(), "");

        assertTrue(Strings.CS.contains(r.output(), "Created"), r.output());
        assertTrue(Files.isRegularFile(dir.resolve("kb.json")),
            "the released command creates the editable template on first use");
    }
}

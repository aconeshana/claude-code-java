package com.claudecode.commands.impl.agents;


import org.apache.commons.lang3.Strings;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.core.message.Usage;
import com.claudecode.commands.testing.TestCommandPorts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link AgentsCommand}. matches the launcher-present /
 * launcher-absent split established by {@code PermissionsCommandTest}.
 */
class AgentsCommandTest {

    private static CommandContext ctxWithAgentsDialogLauncher(Runnable agentsDialogLauncher) {
        return CommandContext.builder(
            "m", List::of, () -> {}, _ -> {},
            () -> Usage.EMPTY, _ -> 0.0, ".", false)
            .currentSessionId(() -> null)
            .toolingCommands(TestCommandPorts.markdownResources())
            .agentsDialogLauncher(agentsDialogLauncher)
            .build();
    }

    @Test
    void execute_withLauncher_delegatesAndSkips() {
        AtomicBoolean called = new AtomicBoolean(false);
        CommandContext ctx = ctxWithAgentsDialogLauncher(() -> called.set(true));

        CommandResult r = new AgentsCommand().execute(ctx, "");

        assertTrue(called.get());
        assertEquals("", r.output());
    }

    @Test
    void execute_withoutLauncher_fallsBackToTextListing(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("my-agent.md"), "content");
        CommandContext ctx = ctxWithAgentsDialogLauncher(null);

        CommandResult r = new AgentsCommand(tmp).execute(ctx, "");

        assertTrue(Strings.CS.contains(r.output(), "my-agent"), r.output());
    }

    @Test
    void execute_withoutLauncher_missingDir_showsCreateHint(@TempDir Path tmp) {
        Path missing = tmp.resolve("does-not-exist");
        CommandContext ctx = ctxWithAgentsDialogLauncher(null);

        CommandResult r = new AgentsCommand(missing).execute(ctx, "");

        assertTrue(Strings.CS.contains(r.output(), "No agent definitions found"), r.output());
    }
}

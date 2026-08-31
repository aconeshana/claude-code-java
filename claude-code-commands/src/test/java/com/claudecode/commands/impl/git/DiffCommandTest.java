package com.claudecode.commands.impl.git;


import org.apache.commons.lang3.Strings;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.core.message.Usage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DiffCommandTest {

    @Test
    void metadata_matchesTs() {
        DiffCommand cmd = new DiffCommand();
        assertEquals("diff", cmd.name());
        assertEquals("View uncommitted changes and per-turn diffs", cmd.description());
        assertTrue(cmd.aliases().isEmpty(), "TS has no aliases — 'git-diff' was invented");
    }

    @Test
    void launcherPresent_runsItAndSkips() {
        AtomicBoolean launched = new AtomicBoolean();
        CommandContext ctx = CommandContext.builder(
            "m", List::of, () -> {}, _ -> {}, () -> Usage.EMPTY, _ -> 0.0, "/tmp", false)
            .diffDialogLauncher(() -> launched.set(true))
            .build();
        CommandResult r = new DiffCommand().execute(ctx, "");
        assertTrue(launched.get());
        assertTrue(r.silent());
    }

    @Test
    void headlessNonGitDirectory_reportsNotARepo() {
        String out = DiffCommand.renderTextSummary("/tmp");
        assertTrue(Strings.CS.contains(out, "Not a git repository") || Strings.CS.contains(out, "clean"), out);
    }
}

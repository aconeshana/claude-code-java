package com.claudecode.commands.impl.session;


import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.core.message.Usage;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


class RewindCommandTest {

    private final RewindCommand cmd = new RewindCommand();

    @Test
    void name_isRewind() {
        assertEquals("rewind", cmd.name());
    }

    @Test
    void aliases_includeCheckpoint() {
        assertTrue(cmd.aliases().contains("checkpoint"));
    }

    @Test
    void execute_withLauncher_invokesItAndReturnsSkip() {
        boolean[] called = {false};
        CommandContext ctx = CommandContext.builder(
                "claude-sonnet-4-20250514",
                List::of,
                () -> {},
                _ -> {},
                () -> Usage.EMPTY,
                _ -> 0.0,
                System.getProperty("user.dir"),
                false)
            .openMessageSelector(() -> called[0] = true)
            .build();

        CommandResult r = cmd.execute(ctx, "");

        assertTrue(called[0], "openMessageSelector launcher must be invoked");
        assertTrue(r.silent(), "mirrors TS {type:'skip'} — REPL must not echo/render anything");
        assertEquals("", r.output());
    }

    @Test
    void execute_withoutLauncher_stillReturnsSkipLikeTs() {
        CommandResult r = cmd.execute(CommandContext.minimal(), "");

        assertTrue(r.silent());
        assertEquals("", r.output());
    }
}

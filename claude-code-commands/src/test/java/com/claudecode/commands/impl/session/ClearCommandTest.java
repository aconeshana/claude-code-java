package com.claudecode.commands.impl.session;


import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.CommandResultDisplay;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@link ClearCommand}'s metadata and that {@code execute} invokes
 * the {@code clearMessages} callback — the real reset logic lives behind
 * that callback (wired through {@code ReplCommandUiBridge} to the UI session feature in
 * production; see {@link ClearCommand}'s class Javadoc).
 */
class ClearCommandTest {

    /** Builds a CommandContext wiring only {@code clearMessages}. */
    private static CommandContext ctx(Runnable clearMessages) {
        return CommandContext.builder(
                "claude-sonnet-4-6", List::of, clearMessages, _ -> {},
                null, _ -> 0.0, ".", false)
            .currentSessionId(() -> null)
            .build();
    }

    private static final ClearCommand CMD = new ClearCommand();

    @Test
    void name_isClear() {
        assertEquals("clear", CMD.name());
    }

    @Test
    void aliases_matchTsResetAndNew() {
        assertEquals(List.of("reset", "new"), CMD.aliases());
    }

    @Test
    void description_matchesTsVerbatim() {
        assertEquals("Clear conversation history and free up context", CMD.description());
    }

    @Test
    void execute_invokesClearMessagesCallback() {
        AtomicBoolean cleared = new AtomicBoolean(false);
        CommandResult result = CMD.execute(ctx(() -> cleared.set(true)), "");
        assertTrue(cleared.get(), "execute() must invoke the clearMessages callback");
        assertEquals("", result.output());
        assertEquals(CommandResultDisplay.LOCAL, result.display(),
            "TS /clear is a local command, so its input is echoed after the reset");
    }
}

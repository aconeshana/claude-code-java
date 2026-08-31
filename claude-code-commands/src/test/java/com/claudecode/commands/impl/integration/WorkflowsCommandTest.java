package com.claudecode.commands.impl.integration;


import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.core.message.Usage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowsCommandTest {

    @Test
    void launchesInteractiveDialogAndSkipsTranscript() {
        AtomicBoolean launched = new AtomicBoolean();
        CommandContext context = CommandContext.builder("sonnet", List::of, () -> {}, _ -> {},
                () -> Usage.EMPTY, _ -> 0, ".", false)
            .workflowsDialogLauncher(() -> launched.set(true))
            .build();

        CommandResult result = new WorkflowsCommand().execute(context, "");

        assertTrue(launched.get());
        assertTrue(result.silent());
    }

    @Test
    void usesOfficialEmptyFallbackOutsideInteractiveUi() {
        WorkflowsCommand command = new WorkflowsCommand();

        assertEquals("No dynamic workflows in this session.",
            command.execute(CommandContext.minimal(), "").output());
        assertEquals("Browse running and completed workflows", command.description());
        assertTrue(command.isImmediate());
    }
}

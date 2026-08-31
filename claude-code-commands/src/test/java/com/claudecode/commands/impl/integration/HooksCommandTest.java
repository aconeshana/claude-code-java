package com.claudecode.commands.impl.integration;


import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.core.message.Usage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HooksCommandTest {

    @Test
    void argumentsDoNotBypassTheOriginalInteractiveBrowser() {
        AtomicInteger opened = new AtomicInteger();
        CommandContext context = CommandContext.builder(
                "sonnet", List::of, () -> {}, _ -> {},
                () -> Usage.EMPTY, _ -> 0.0, System.getProperty("user.dir"), false)
            .hooksDialogLauncher(opened::incrementAndGet)
            .build();

        CommandResult result = new HooksCommand().execute(context, "events");

        assertEquals(1, opened.get());
        assertTrue(result.silent());
    }
}

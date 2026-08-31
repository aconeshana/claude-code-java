package com.claudecode.commands.impl.context;


import com.claudecode.commands.CommandContext;
import com.claudecode.core.message.Usage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryCommandInteractiveTest {

    @Test
    void argumentsDoNotBypassTheOriginalInteractiveSelector() {
        AtomicInteger opened = new AtomicInteger();
        CommandContext context = CommandContext.builder(
                "sonnet", List::of, () -> {}, _ -> {},
                () -> Usage.EMPTY, _ -> 0.0, System.getProperty("user.dir"), false)
            .memoryDialogLauncher(opened::incrementAndGet)
            .build();

        var result = new MemoryCommand().execute(context, "global");

        assertEquals(1, opened.get());
        assertTrue(result.silent());
    }
}

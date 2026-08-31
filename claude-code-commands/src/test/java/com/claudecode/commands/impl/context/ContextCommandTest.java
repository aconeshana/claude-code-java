package com.claudecode.commands.impl.context;


import org.apache.commons.lang3.Strings;

import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.CommandResultDisplay;
import com.claudecode.commands.context.ContextData;
import com.claudecode.core.message.Usage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ContextCommandTest {

    private static ContextData sampleData() {
        return new ContextData(
            List.of(new ContextData.Category("Messages", 5_000, ContextData.ContextColor.PURPLE),
                new ContextData.Category(ContextData.FREE_SPACE, 195_000,
                    ContextData.ContextColor.PROMPT_BORDER)),
            5_000, 200_000, 3, "claude-opus-4-8",
            List.of(), List.of(), List.of(), null, 187_000L, true, null, null);
    }

    private static CommandContext.Builder base() {
        return CommandContext.builder(
            "m", List::of, () -> {}, _ -> {}, () -> Usage.EMPTY, _ -> 0.0, "/tmp", false);
    }

    @Test
    void metadata() {
        ContextCommand cmd = new ContextCommand();
        assertEquals("context", cmd.name());
        assertEquals("Visualize current context usage as a colored grid", cmd.description());
        assertTrue(cmd.aliases().isEmpty());
    }

    @Test
    void launcherPresent_runsItAndSkips() {
        AtomicBoolean launched = new AtomicBoolean();
        CommandContext ctx = base()
            .contextVisualizerLauncher(() -> launched.set(true))
            .contextDataCollector(ContextCommandTest::sampleData)
            .build();
        CommandResult r = new ContextCommand().execute(ctx, "");
        assertTrue(launched.get());
        assertTrue(r.silent(), "interactive path must be silent — the UI owns rendering");
    }

    @Test
    void noLauncher_rendersMarkdownFallback() {
        CommandContext ctx = base()
            .contextDataCollector(ContextCommandTest::sampleData)
            .build();
        CommandResult r = new ContextCommand().execute(ctx, "");
        assertTrue(Strings.CS.startsWith(r.output(), "## Context Usage"));
        assertTrue(Strings.CS.contains(r.output(), "**Model:** claude-opus-4-8"));
        assertFalse(r.shouldQuery());
        assertEquals(CommandResultDisplay.LOCAL, r.display(),
            "headless /context uses the TS type:'local' registration");
    }

    @Test
    void noCollector_reportsUnavailable() {
        CommandResult r = new ContextCommand().execute(CommandContext.minimal(), "");
        assertEquals("Context usage data is unavailable in this session.", r.output());
        assertEquals(CommandResultDisplay.LOCAL, r.display());
    }
}

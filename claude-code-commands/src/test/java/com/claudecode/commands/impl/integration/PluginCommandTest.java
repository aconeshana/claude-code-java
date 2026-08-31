package com.claudecode.commands.impl.integration;


import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.core.message.Usage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * /plugin routing tests. The former BuiltinPluginRegistry-based tests were
 * retired 2026-07-10 with the marketplace rewrite - the panel and its
 * services have their own suites (PluginSettingsPanelTest and the
 * services.plugins.marketplace tests).
 */
class PluginCommandTest {

    @Test
    void metadata_matchesTs() {
        PluginCommand cmd = new PluginCommand();
        assertEquals("plugin", cmd.name());
        assertEquals("Manage Claude Code plugins", cmd.description());
        assertEquals(List.of("plugins", "marketplace"), cmd.aliases());
        assertTrue(cmd.isImmediate());
    }

    @Test
    void launcherPresent_receivesRawArgsAndSkips() {
        AtomicReference<String> received = new AtomicReference<>();
        CommandContext ctx = CommandContext.builder(
            "m", List::of, () -> {}, _ -> {}, () -> Usage.EMPTY, _ -> 0.0, "/tmp", false)
            .pluginDialogLauncher(received::set)
            .build();
        CommandResult r = new PluginCommand().execute(ctx, "  install foo@bar  ");
        assertTrue(r.silent());
        assertEquals("install foo@bar", received.get());
    }

    @Test
    void headless_noRuntime_reportsUninitialized() {
        CommandResult r = new PluginCommand().execute(CommandContext.minimal(), "");
        assertEquals("Plugin system is not initialized in this session.", r.output());
    }
}

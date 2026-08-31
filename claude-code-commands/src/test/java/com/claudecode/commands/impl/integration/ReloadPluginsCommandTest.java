package com.claudecode.commands.impl.integration;

import org.apache.commons.lang3.Strings;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.plugins.PluginRuntimePort;
import com.claudecode.core.message.Usage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReloadPluginsCommandTest {

    @Test
    void missingSessionRuntimeIsReported() {
        String output = new ReloadPluginsCommand()
            .execute(CommandContext.minimal(), "").output();
        assertTrue(Strings.CS.contains(output, "not initialized"));
    }

    @Test
    void formatsSessionRefreshCounters() {
        PluginRuntimePort runtime = new PluginRuntimePort() {
            @Override public Summary summary() { return new Summary(0, 0, 0, 0, 0); }
            @Override public RefreshResult refresh() {
                return new RefreshResult(1, 2, 3, 4, 5, 6, 7, 1);
            }
        };
        CommandContext context = CommandContext.builder(
                "m", List::of, () -> { }, _ -> { }, () -> Usage.EMPTY,
                _ -> 0.0, "/tmp", false)
            .pluginRuntime(runtime)
            .build();

        String output = new ReloadPluginsCommand().execute(context, "").output();

        assertEquals("""
            Reloaded: 1 plugin · 3 skills · 4 agents · 5 hooks · \
            6 plugin MCP servers · 7 plugin LSP servers
            1 error during load. Run /doctor for details.""", output);
    }
}

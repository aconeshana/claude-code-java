package com.claudecode.commands.metadata;

import com.claudecode.commands.Command;
import com.claudecode.commands.CommandRegistry;

import com.claudecode.commands.bootstrap.CommandFactory;

import com.claudecode.commands.impl.git.BranchCommand;
import com.claudecode.commands.impl.session.ClearCommand;
import com.claudecode.commands.impl.config.ConfigCommand;
import com.claudecode.commands.impl.session.ExitCommand;
import com.claudecode.commands.impl.integration.McpCommand;
import com.claudecode.commands.impl.config.PermissionsCommand;
import com.claudecode.commands.impl.integration.PluginCommand;
import com.claudecode.commands.impl.session.ResumeCommand;
import com.claudecode.commands.impl.session.RewindCommand;
import com.claudecode.commands.impl.terminal.TasksCommand;
import com.claudecode.runtime.mcp.McpManagementPort;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


class CommandAliasParityTest {

    @Test
    void directCommandAliasesMatchTheTsDefinitions() {
        assertEquals(List.of("fork"), new BranchCommand().aliases());
        assertEquals(List.of("reset", "new"), new ClearCommand().aliases());
        assertEquals(List.of("settings"), new ConfigCommand().aliases());
        assertEquals(List.of("quit"), new ExitCommand().aliases());
        assertEquals(List.of("allowed-tools"), new PermissionsCommand().aliases());
        assertEquals(List.of("plugins", "marketplace"), new PluginCommand().aliases());
        assertEquals(List.of("continue"), new ResumeCommand().aliases());
        assertEquals(List.of("checkpoint"), new RewindCommand().aliases());
        assertEquals(List.of("bashes"), new TasksCommand().aliases());

        McpCommand mcp = new McpCommand(McpManagementPort.none());
        assertTrue(mcp.aliases().isEmpty());
    }

    @Test
    void stubCommandAliasesMatchTheTsDefinitions() {
        CommandRegistry registry = CommandFactory.createDefault();

        assertAlias(registry, "rc", "remote-control");
        assertAlias(registry, "app", "desktop");
        assertAlias(registry, "remote", "session");
        assertAlias(registry, "bug", "feedback");
        assertAlias(registry, "ios", "mobile");
        assertAlias(registry, "android", "mobile");
        assertTrue(registry.find("signout").isEmpty(),
            "TS logout/index.ts declares no aliases");
    }

    private static void assertAlias(CommandRegistry registry, String alias, String commandName) {
        Command command = registry.find(alias).orElseThrow(
            () -> new AssertionError("Missing alias /" + alias));
        assertEquals(commandName, command.name());
    }
}

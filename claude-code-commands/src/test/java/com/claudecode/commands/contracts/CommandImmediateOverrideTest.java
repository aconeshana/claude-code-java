package com.claudecode.commands.contracts;

import com.claudecode.commands.Command;
import com.claudecode.commands.CommandRegistry;

import com.claudecode.commands.bootstrap.CommandFactory;

import com.claudecode.commands.impl.terminal.BtwCommand;
import com.claudecode.commands.impl.session.ClearCommand;
import com.claudecode.commands.impl.config.ColorCommand;
import com.claudecode.commands.impl.context.CompactCommand;
import com.claudecode.commands.impl.config.ConfigCommand;
import com.claudecode.commands.impl.session.ExitCommand;
import com.claudecode.commands.impl.info.HelpCommand;
import com.claudecode.commands.impl.integration.HooksCommand;
import com.claudecode.commands.impl.integration.McpCommand;
import com.claudecode.commands.impl.integration.PluginCommand;
import com.claudecode.commands.impl.session.RenameCommand;
import com.claudecode.commands.impl.info.StatusCommand;
import com.claudecode.runtime.mcp.McpManagementPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;


class CommandImmediateOverrideTest {

    // -----------------------------------------------------------------------
    // Unconditionally immediate commands
    // -----------------------------------------------------------------------

    @Test
    void btw_isImmediate() {
        assertTrue(new BtwCommand().isImmediate(),
            "BtwCommand (TS btw) must be immediate");
    }

    @Test
    void color_isImmediate() {
        assertTrue(new ColorCommand().isImmediate(),
            "ColorCommand (TS color) must be immediate");
    }

    @Test
    void exit_isImmediate() {
        assertTrue(new ExitCommand().isImmediate(),
            "ExitCommand (TS exit) must be immediate");
    }

    @Test
    void hooks_isImmediate() {
        assertTrue(new HooksCommand().isImmediate(),
            "HooksCommand (TS hooks) must be immediate");
    }

    @Test
    void mcp_isImmediate() {
        // McpCommand requires a live McpClientManager since M1.9 (no-arg
        // ctor removed to make wire-order failures compile-time errors).
        // For a metadata assertion we can pass a plain instance — the ctor
// only null-checks it, and the isImmediate answer is static.
        assertTrue(new McpCommand(McpManagementPort.none()).isImmediate(),
            "McpCommand (TS mcp) must be immediate");
    }

    @Test
    void plugin_isImmediate() {
        assertTrue(new PluginCommand().isImmediate(),
            "PluginCommand (TS plugin) must be immediate");
    }

    @Test
    void rename_isImmediate() {
        assertTrue(new RenameCommand().isImmediate(),
            "RenameCommand (TS rename) must be immediate");
    }

    @Test
    void status_isImmediate() {
        assertTrue(new StatusCommand().isImmediate(),
            "StatusCommand (TS status) must be immediate");
    }

    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------

    @Test
    void nonImmediateCommandsReturnFalse() {

        List<Command> nonImmediate = List.of(
            new ClearCommand(),
            new HelpCommand(new CommandRegistry()),
            new CompactCommand(),
            new ConfigCommand()
        );
        for (Command cmd : nonImmediate) {
            assertFalse(cmd.isImmediate(),
                cmd.name() + " must NOT be immediate — it is not in the TS immediate list");
        }
    }

    // -----------------------------------------------------------------------
    // Registry-level: verify the default registry contains all immediate commands
    // -----------------------------------------------------------------------

    @Test
    void defaultRegistryContainsAllImmediateCommands() {
// /mcp is intentionally *not* in CommandFactory.createDefault — it
        // requires a live McpClientManager that only exists after MCP init,
        // so the CLI startup path adds it to the registry itself. The
// metadata assertion for /mcp lives in mcp_isImmediate above.
        Set<String> expectedImmediate = Set.of(
            "btw", "color", "exit", "hooks", "plugin", "rename", "status",
            "model", "fast", "effort"
        );

        CommandRegistry registry = CommandFactory.createDefault();
        Map<String, Boolean> immediateByName = registry.getAll().stream()
            .collect(Collectors.toMap(Command::name, Command::isImmediate,
                (a, b) -> a || b)); // merge duplicates when name+alias collide

        Set<String> missing = expectedImmediate.stream()
            .filter(name -> !immediateByName.containsKey(name))
            .collect(Collectors.toSet());

        assertTrue(missing.isEmpty(),
            "These immediate commands are not registered in CommandFactory: " + missing);

        Set<String> notMarked = expectedImmediate.stream()
            .filter(immediateByName::containsKey)
            .filter(name -> !immediateByName.get(name))
            // model/fast/effort may return false outside ant env — exclude them
            .filter(name -> !Set.of("model", "fast", "effort").contains(name))
            .collect(Collectors.toSet());

        assertTrue(notMarked.isEmpty(),
            "These commands are registered but isImmediate() returns false: " + notMarked);
    }
}

package com.claudecode.commands.plugins;

import com.claudecode.commands.Command;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.metadata.CommandMetadata;
import com.claudecode.commands.CommandRegistry;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.impl.integration.PluginMarkdownCommand;
import com.claudecode.runtime.plugins.PluginCommandDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginCommandSyncTest {

    private static PluginCommandDefinition def(String name) {
        return PluginCommandDefinition.builder(name, "prompt", "p")
            .description("d").hasUserSpecifiedDescription(true).build();
    }

    /** Minimal stand-in for a built-in command that must survive syncs. */
    private static Command builtIn(String name) {
        return new Command() {
            @Override public CommandMetadata metadata() {
                return new CommandMetadata(name, "built-in");
            }
            @Override public CommandResult execute(CommandContext c, String a) {
                return CommandResult.of("ok");
            }
        };
    }

    @Test
    void syncRegistersPluginCommands() {
        CommandRegistry registry = new CommandRegistry();
        PluginCommandSync sync = new PluginCommandSync();

        int count = sync.sync(registry, List.of(def("p:one"), def("p:sub:two")));
        assertEquals(2, count);
        assertInstanceOf(PluginMarkdownCommand.class, registry.find("p:one").orElseThrow());
        assertTrue(registry.find("p:sub:two").isPresent());
    }

    @Test
    void resyncRemovesPreviousGeneration() {
        CommandRegistry registry = new CommandRegistry();
        PluginCommandSync sync = new PluginCommandSync();

        sync.sync(registry, List.of(def("p:old-a"), def("p:old-b")));
        sync.sync(registry, List.of(def("p:new")));

        assertTrue(registry.find("p:old-a").isEmpty());
        assertTrue(registry.find("p:old-b").isEmpty());
        assertTrue(registry.find("p:new").isPresent());
    }

    @Test
    void builtInCommandsSurviveSync() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(builtIn("help"));
        PluginCommandSync sync = new PluginCommandSync();

        sync.sync(registry, List.of(def("p:cmd")));
        sync.sync(registry, List.of());

        assertTrue(registry.find("help").isPresent(), "non-plugin commands must never be evicted");
        assertTrue(registry.find("p:cmd").isEmpty());
    }

    @Test
    void colonNamedNonPluginCommandsAreNotEvicted() {
        CommandRegistry registry = new CommandRegistry();
        // e.g. an MCP prompt command that also contains ':' in its name
        registry.register(builtIn("mcp:prompt"));
        PluginCommandSync sync = new PluginCommandSync();

        sync.sync(registry, List.of(def("p:cmd")));
        sync.sync(registry, List.of());

        assertTrue(registry.find("mcp:prompt").isPresent(),
            "only names this sync registered may be removed");
    }

    @Test
    void emptySyncOnFreshRegistryIsNoOp() {
        CommandRegistry registry = new CommandRegistry();
        assertEquals(0, new PluginCommandSync().sync(registry, List.of()));
        assertTrue(registry.getAll().isEmpty());
    }
}

package com.claudecode.commands.registry;

import com.claudecode.commands.Command;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandRegistry;
import com.claudecode.commands.CommandResult;

import com.claudecode.commands.metadata.CommandMetadata;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandRegistryBuiltInTest {

    @Test
    void builtInNamesIncludeAliasesAndSurviveDynamicReplacement() {
        CommandRegistry registry = new CommandRegistry();
        registry.registerBuiltIn(command("resume", List.of("continue")));

        assertTrue(registry.isBuiltInCommandName("resume"));
        assertTrue(registry.isBuiltInCommandName("CONTINUE"));

        registry.register(command("resume", List.of()));
        assertTrue(registry.isBuiltInCommandName("resume"),
            "TS builtInCommandNames is a stable name catalogue, not instance provenance");
    }

    @Test
    void ordinaryDynamicRegistrationIsNotBuiltIn() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(command("plugin:review", List.of()));

        assertFalse(registry.isBuiltInCommandName("plugin:review"));
    }

    private static Command command(String name, List<String> aliases) {
        return new Command() {
            @Override public CommandMetadata metadata() {
                return new CommandMetadata(name, name);
            }
            @Override public List<String> aliases() { return aliases; }
            @Override public CommandResult execute(CommandContext context, String args) {
                return CommandResult.skip();
            }
        };
    }
}

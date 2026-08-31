package com.claudecode.commands.metadata;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.Command;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;

import com.claudecode.commands.bootstrap.CommandFactory;

import org.apache.commons.lang3.Strings;
import com.claudecode.commands.impl.git.InitCommand;
import com.claudecode.commands.impl.config.ModelCommand;
import com.claudecode.commands.impl.config.SandboxToggleCommand;
import com.claudecode.commands.impl.info.StubCommand;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandMetadataContractTest {

    @Test
    void annotatedCommandReadsClassLevelMetadata() {
        Command command = new StaticTestCommand();

        assertEquals("static-test", command.name());
        assertEquals("Static test command", command.description());
        assertEquals(List.of("st", "static"), command.aliases());
        assertEquals(
            new CommandMetadata("static-test", "Static test command", List.of("st", "static")),
            command.metadata()
        );
    }

    @Test
    void annotatedCommandFailsClearlyWhenAnnotationIsMissing() {
        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> new MissingAnnotationCommand().metadata()
        );

        assertTrue(Strings.CS.contains(error.getMessage(), MissingAnnotationCommand.class.getName()));
        assertTrue(Strings.CS.contains(error.getMessage(), "@SlashCommand"));
    }

    @Test
    void annotatedCommandRejectsBlankDescription() {
        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> new BlankDescriptionCommand().metadata()
        );

        assertTrue(Strings.CS.contains(error.getMessage(), "blank @SlashCommand description"));
    }

    @Test
    void commandMetadataRejectsInvalidNamesAndAliases() {
        assertThrows(IllegalArgumentException.class, () -> new CommandMetadata(" ", "description"));
        assertThrows(IllegalArgumentException.class, () -> new CommandMetadata("/help", "description"));
        assertThrows(IllegalArgumentException.class,
            () -> new CommandMetadata("help", "description", List.of(" ")));
        assertThrows(IllegalArgumentException.class,
            () -> new CommandMetadata("help", "description", List.of("/h")));
    }

    @Test
    void defaultFactoryUsesAnnotationsExceptForInstanceMetadataCommands() {
        Set<Class<?>> instanceMetadataCommands = Set.of(
            InitCommand.class,
            ModelCommand.class,
            SandboxToggleCommand.class,
            StubCommand.class
        );

        for (Command command : CommandFactory.createDefault().getAll()) {
            if (instanceMetadataCommands.contains(command.getClass())) {
                assertInstanceOf(Command.class, command);
                continue;
            }

            assertInstanceOf(AnnotatedCommand.class, command,
                () -> command.getClass().getName() + " should declare static command metadata");
            SlashCommand annotation = command.getClass().getAnnotation(SlashCommand.class);
            assertEquals(command.name(), annotation.name());
            assertEquals(command.description(), annotation.description());
            assertEquals(command.aliases(), List.of(annotation.aliases()));
        }
    }

    @Test
    void instanceMetadataCarriesStubAliases() {
        Command command = new StubCommand("dynamic", "Dynamic command", List.of("d", "dyn"));

        assertEquals(List.of("d", "dyn"), command.metadata().aliases());
        assertEquals(command.metadata().aliases(), command.aliases());
    }

    @SlashCommand(
        name = "static-test",
        description = "Static test command",
        aliases = {"st", "static"}
    )
    private static final class StaticTestCommand implements AnnotatedCommand {
        @Override
        public CommandResult execute(CommandContext context, String args) {
            return CommandResult.skip();
        }
    }

    private static final class MissingAnnotationCommand implements AnnotatedCommand {
        @Override
        public CommandResult execute(CommandContext context, String args) {
            return CommandResult.skip();
        }
    }

    @SlashCommand(name = "blank-description", description = " ")
    private static final class BlankDescriptionCommand implements AnnotatedCommand {
        @Override
        public CommandResult execute(CommandContext context, String args) {
            return CommandResult.skip();
        }
    }
}

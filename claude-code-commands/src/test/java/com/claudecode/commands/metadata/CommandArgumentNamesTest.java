package com.claudecode.commands.metadata;

import com.claudecode.commands.Command;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;


import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;


class CommandArgumentNamesTest {

    @Test
    void commandProvidesAnEmptyDefaultArgumentNamesList() {
        Command command = new Command() {
            @Override public CommandMetadata metadata() {
                return new CommandMetadata("plain", "plain command");
            }
            @Override public CommandResult execute(CommandContext context, String args) {
                return CommandResult.skip();
            }
        };

        assertEquals(List.of(), command.argumentNames());
    }
}

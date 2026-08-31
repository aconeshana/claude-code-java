package com.claudecode.commands.impl.session;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.metadata.SlashCommand;

@SlashCommand(
    name = "clear",
    description = "Clear conversation history and free up context",
    aliases = {"reset", "new"}
)
public class ClearCommand implements AnnotatedCommand {

    @Override
    public CommandResult execute(CommandContext context, String args) {
        context.session().clearMessages().run();
        return CommandResult.local("");
    }

}

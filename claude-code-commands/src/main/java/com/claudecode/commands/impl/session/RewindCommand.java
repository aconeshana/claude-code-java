package com.claudecode.commands.impl.session;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.metadata.SlashCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;

/**
 * /rewind — restore the conversation to an earlier user message.
 */
@SlashCommand(
    name = "rewind",
    description = "Restore the code and/or conversation to a previous point",
    aliases = "checkpoint"
)
public class RewindCommand implements AnnotatedCommand {

    @Override
    public CommandResult execute(CommandContext context, String args) {
        Runnable open = context.presentation().openMessageSelector();
        if (open != null) open.run();

        return CommandResult.skip();
    }
}

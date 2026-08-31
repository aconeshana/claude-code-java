package com.claudecode.commands.impl.info;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.metadata.SlashCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;


/**
 * /usage — show plan usage limits.
 */
@SlashCommand(
    name = "usage",
    description = "Show plan usage limits"
)
public class UsageCommand implements AnnotatedCommand {

    @Override
    public boolean isAvailable(CommandContext context) {
        return true;
    }

    @Override
    public CommandResult execute(CommandContext context, String args) {
        if (context.presentation().usageDialogLauncher() != null) {
            context.presentation().usageDialogLauncher().run();
            return CommandResult.skip();
        }
        return CommandResult.local(CostCommand.sessionSummary());
    }
}

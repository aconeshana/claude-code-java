package com.claudecode.commands.impl.integration;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.metadata.SlashCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;

/**
 * {@code /workflows} — opens the running/completed dynamic-workflow browser.
 */
@SlashCommand(
    name = "workflows",
    description = "Browse running and completed workflows"
)
public final class WorkflowsCommand implements AnnotatedCommand {

    @Override public boolean isImmediate() { return true; }

    @Override
    public CommandResult execute(CommandContext context, String args) {
        if (context != null && context.presentation().workflowsDialogLauncher() != null) {
            context.presentation().workflowsDialogLauncher().run();
            return CommandResult.skip();
        }
        return CommandResult.of("No dynamic workflows in this session.");
    }
}

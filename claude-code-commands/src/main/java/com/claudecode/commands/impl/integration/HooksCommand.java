package com.claudecode.commands.impl.integration;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.metadata.SlashCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;


/**
 * /hooks — opens the read-only hook configuration browser.
 */
@SlashCommand(
    name = "hooks",
    description = "View hook configurations for tool events"
)
public class HooksCommand implements AnnotatedCommand {

    @Override public boolean isImmediate() { return true; }

    @Override
    public CommandResult execute(CommandContext context, String args) {
        if (context.presentation().hooksDialogLauncher() != null) {
            context.presentation().hooksDialogLauncher().run();
            return CommandResult.skip();
        }
        return CommandResult.of("""
            Hooks are configured in settings.json.

            Run /hooks in an interactive session to open the hook browser.
            """);
    }
}

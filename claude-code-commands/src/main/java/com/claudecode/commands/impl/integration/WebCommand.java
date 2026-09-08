package com.claudecode.commands.impl.integration;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.metadata.SlashCommand;

/**
 * /web — starts the in-process web gateway and reports its authenticated URL.
 *
 * <p>The launcher receives the gateway URL (token embedded) and presents it
 * through the local UI; in non-interactive sessions the command explains
 * that the gateway is an interactive-session feature.
 */
@SlashCommand(
    name = "web",
    description = "Start the web gateway and show its URL"
)
public class WebCommand implements AnnotatedCommand {

    @Override public boolean isImmediate() { return true; }

    @Override
    public CommandResult execute(CommandContext context, String args) {
        if (context.presentation().gatewayLauncher() != null) {
            context.presentation().gatewayLauncher().accept(null);
            return CommandResult.skip();
        }
        return CommandResult.of("""
            The web gateway is available in interactive sessions.

            Run /web in an interactive session to start it.
            """);
    }
}

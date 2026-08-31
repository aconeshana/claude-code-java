package com.claudecode.commands.impl.agents;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.metadata.SlashCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;

/**
 * /dream — manually trigger a memory-consolidation pass over your auto-memory files.
 */
@SlashCommand(
    name = "dream",
    description = "Consolidate and improve your memory files"
)
public class DreamCommand implements AnnotatedCommand {

    /** Filename of the command, without the leading slash. */
    static final String COMMAND_NAME = "dream";

    @Override
    public boolean supportsNonInteractive() { return true; }

    @Override
    public boolean isAvailable(CommandContext context) {

        return context.application().dream() != null && context.application().dream().available();
    }

    @Override
    public CommandResult execute(CommandContext context, String args) {

        if (context.application().dream() == null) {
            return CommandResult.of("Dream is not available in this context.");
        }
        return CommandResult.forQuery(context.application().dream().buildPrompt(context.session().workingDirectory()));
    }
}

package com.claudecode.commands.impl.context;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.metadata.SlashCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.context.ContextMarkdownFormatter;


/**
 * {@code /context} — visualize how much of the model's context window is in use, category by
 * category.
 */
@SlashCommand(
    name = "context",
    description = "Visualize current context usage as a colored grid"
)
public class ContextCommand implements AnnotatedCommand {

    @Override public boolean supportsNonInteractive() { return true; }

    @Override
    public CommandResult execute(CommandContext context, String args) {
        if (context.presentation().contextVisualizerLauncher() != null) {
            context.presentation().contextVisualizerLauncher().run();
            return CommandResult.skip();
        }
        if (context.session().contextDataCollector() != null) {
            return CommandResult.local(
                ContextMarkdownFormatter.format(context.session().contextDataCollector().get()));
        }
        return CommandResult.local("Context usage data is unavailable in this session.");
    }
}

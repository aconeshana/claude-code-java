package com.claudecode.commands.impl.info;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.Command;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandRegistry;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.metadata.SlashCommand;

import java.util.List;

@SlashCommand(
    name = "help",
    description = "Show help and available commands"
)
public class HelpCommand implements AnnotatedCommand {

    private final CommandRegistry registry;

    public HelpCommand(CommandRegistry registry) {
        this.registry = registry;
    }

    @Override
    public CommandResult execute(CommandContext context, String args) {
        if (context.presentation().helpDialogLauncher() != null) {
            context.presentation().helpDialogLauncher().run();
            return CommandResult.skip();
        }
        List<Command> available = registry.getAvailable(context).stream()
            .filter(c -> !c.isHidden())
            .toList();
        StringBuilder sb = new StringBuilder("Available commands:\n");
        for (Command cmd : available) {
            sb.append("  /").append(cmd.name());
            if (!cmd.aliases().isEmpty()) {
                sb.append(" (").append(String.join(", ", cmd.aliases())).append(")");
            }
            sb.append(" — ").append(cmd.description()).append("\n");
        }
        return CommandResult.of(sb.toString().stripTrailing());
    }

}

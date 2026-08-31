package com.claudecode.ui.lanterna.features.help;

import com.claudecode.commands.Command;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.metadata.CommandDescriptionFormatter;
import com.claudecode.commands.CommandRegistry;

import java.util.Comparator;
import java.util.List;

/**
 * Builds the two command lists consumed by {@link HelpPanel}.
 */
public final class HelpCommandCatalog {

    public record Catalog(List<HelpPanel.CommandEntry> builtin,
                   List<HelpPanel.CommandEntry> custom) {}

    private HelpCommandCatalog() {}

    public static Catalog build(CommandRegistry registry, CommandContext context) {
        List<Command> visible = registry.getAvailable(context).stream()
            .filter(command -> !command.isHidden())
            .sorted(Comparator.comparing(Command::name))
            .toList();
        return new Catalog(entries(visible, registry, true),
            entries(visible, registry, false));
    }

    private static List<HelpPanel.CommandEntry> entries(
            List<Command> commands, CommandRegistry registry, boolean builtIn) {
        return commands.stream()
            .filter(command -> registry.isBuiltInCommandName(command.name()) == builtIn)
            .map(command -> new HelpPanel.CommandEntry(command.name(),
                CommandDescriptionFormatter.formatWithSource(command)))
            .toList();
    }
}

package com.claudecode.commands.impl.terminal;

import java.util.Locale;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.metadata.SlashCommand;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.tooling.ToolingCommandPorts;

import java.util.Comparator;
import java.util.List;

/**
 * {@code /tasks} (alias {@code /bashes}) — list and manage background tasks.
 */
@SlashCommand(
    name = "tasks",
    description = "List and manage background tasks",
    aliases = "bashes"
)
public class TasksCommand implements AnnotatedCommand {

    public TasksCommand() { }

    @Override
    public CommandResult execute(CommandContext context, String args) {
        if (context.presentation().tasksDialogLauncher() != null) {
            context.presentation().tasksDialogLauncher().run();
            return CommandResult.skip();
        }

        List<ToolingCommandPorts.Tasks.Snapshot> all = context.application().tooling().tasks().list();
        if (all.isEmpty()) {
// Background tasks are in-memory only.
            return CommandResult.of("No background tasks in this session.");
        }
        all = all.stream()
            .sorted(Comparator.comparing(ToolingCommandPorts.Tasks.Snapshot::startedAt).reversed())
            .toList();
        StringBuilder sb = new StringBuilder();
        sb.append("Background tasks (").append(all.size()).append(" total):\n\n");

        appendGroup(sb, "Running", all, ToolingCommandPorts.Tasks.Status.RUNNING);
        appendGroup(sb, "Pending", all, ToolingCommandPorts.Tasks.Status.PENDING);
        appendGroup(sb, "Completed", all, ToolingCommandPorts.Tasks.Status.COMPLETED);
        appendGroup(sb, "Failed", all, ToolingCommandPorts.Tasks.Status.FAILED);
        appendGroup(sb, "Killed", all, ToolingCommandPorts.Tasks.Status.KILLED);

        return CommandResult.of(sb.toString().trim());
    }

    private static void appendGroup(StringBuilder sb, String label,
                                    List<ToolingCommandPorts.Tasks.Snapshot> all,
                                    ToolingCommandPorts.Tasks.Status status) {
        List<ToolingCommandPorts.Tasks.Snapshot> filtered = all.stream()
            .filter(t -> t.status() == status).toList();
        if (filtered.isEmpty()) return;
        sb.append("● ").append(label).append(" (").append(filtered.size()).append(")\n");
        for (ToolingCommandPorts.Tasks.Snapshot t : filtered) {
            sb.append("  ")
              .append(t.id())
              .append("  ")
              .append(t.type().toLowerCase(Locale.ROOT))
              .append("  ")
              .append(FormatUtils.truncate(t.description(), 60));
            String age = FormatUtils.formatRelativeTimeAgo(t.startedAt(), FormatUtils.RelativeTimeStyle.PARENTHESIZED);
            if (age != null) sb.append("  ").append(age);
            sb.append('\n');
        }
        sb.append('\n');
    }

}

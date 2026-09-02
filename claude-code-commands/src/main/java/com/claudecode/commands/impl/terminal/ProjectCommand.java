package com.claudecode.commands.impl.terminal;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.metadata.SlashCommand;

/**
 * {@code /project} — toggles the left-docked project-management drawer
 * (projects → sessions: resume, delete, preview). A Java-side extension with
 * no 197 counterpart, so the released binary has no description string to
 * mirror; the wording follows the established short-verb style.
 */
@SlashCommand(
    name = "project",
    description = "Toggle the projects panel"
)
public class ProjectCommand implements AnnotatedCommand {

    public ProjectCommand() { }

    @Override
    public CommandResult execute(CommandContext context, String args) {
        if (context.presentation().projectPanelLauncher() != null) {
            context.presentation().projectPanelLauncher().run();
            return CommandResult.skip();
        }
        return CommandResult.of("The projects panel is only available in the interactive TUI.");
    }
}

package com.claudecode.commands.impl.agents;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.metadata.SlashCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.io.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * /agents — opens the interactive agent-management panel ({@code AgentsPanel}
 * in claude-code-ui: list/detail/create-wizard/quick-edit) when a dialog
 * launcher is wired; otherwise falls back to a plain-text listing of
 * discoverable agents (bridge / headless / tests).
 * Ports.
 */
@SlashCommand(
    name = "agents",
    description = "Manage agent configurations"
)
public class AgentsCommand implements AnnotatedCommand {

    private final Path agentsDir;

    public AgentsCommand() {
        this(ClaudePaths.AGENTS_DIR);
    }

    public AgentsCommand(Path agentsDir) {
        this.agentsDir = agentsDir;
    }

    @Override
    public CommandResult execute(CommandContext context, String args) {
        if (context.presentation().agentsDialogLauncher() != null) {
            context.presentation().agentsDialogLauncher().run();
            return CommandResult.skip();
        }

        if (!Files.isDirectory(agentsDir)) {
            return CommandResult.of(
                "No agent definitions found.\n"
              + "Create one at " + agentsDir + "/<name>.md\n"
              + "See https://docs.claude.com/en/docs/claude-code/sub-agents for the format.");
        }
        List<Path> definitions;
        try {
            definitions = new ArrayList<>(context.application().tooling().resources()
                .markdownFiles(agentsDir));
        } catch (IOException | InterruptedException e) {
            return CommandResult.of("Failed to read agents directory: " + e.getMessage());
        }
        if (definitions.isEmpty()) {
            return CommandResult.of("No agent definitions in " + agentsDir + ".");
        }
        definitions.sort(Comparator.comparing(p -> p.getFileName().toString()));
        StringBuilder sb = new StringBuilder("Agent definitions in ").append(agentsDir).append(":\n\n");
        for (Path p : definitions) {
            sb.append("  ").append(FileUtils.stripExtension(p.getFileName().toString())).append("\n");
        }
        sb.append("\nEdit ").append(agentsDir).append("/<name>.md to modify.");
        return CommandResult.of(sb.toString());
    }
}

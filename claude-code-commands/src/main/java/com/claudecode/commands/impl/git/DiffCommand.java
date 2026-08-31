package com.claudecode.commands.impl.git;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.metadata.SlashCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.diff.DiffData;
import com.claudecode.commands.diff.GitDiffCollector;


/**
 * {@code /diff} — view uncommitted changes and per-turn diffs.
 */
@SlashCommand(
    name = "diff",
    description = "View uncommitted changes and per-turn diffs"
)
public class DiffCommand implements AnnotatedCommand {

    @Override
    public CommandResult execute(CommandContext context, String args) {
        if (context.presentation().diffDialogLauncher() != null) {
            context.presentation().diffDialogLauncher().run();
            return CommandResult.skip();
        }
        return CommandResult.of(renderTextSummary(context.session().workingDirectory()));
    }

    /** Headless fallback — same data source as the dialog, plain text. */
    static String renderTextSummary(String workingDirectory) {
        DiffData data = new GitDiffCollector(workingDirectory).collect();
        if (data == null) {
            return "Not a git repository (or git unavailable).";
        }
        if (data.stats() == null || data.stats().filesCount() == 0) {
            return "Working tree is clean";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(data.stats().filesCount())
            .append(data.stats().filesCount() == 1 ? " file" : " files")
            .append(" changed");
        if (data.stats().linesAdded() > 0) sb.append(" +").append(data.stats().linesAdded());
        if (data.stats().linesRemoved() > 0) sb.append(" -").append(data.stats().linesRemoved());
        sb.append('\n');
        if (data.files().isEmpty()) {
            sb.append("Too many files to display details");
            return sb.toString();
        }
        for (DiffData.DiffFile file : data.files()) {
            sb.append("  ").append(file.path());
            if (file.isUntracked()) {
                sb.append("  untracked");
            } else if (file.isBinary()) {
                sb.append("  Binary file");
            } else if (file.isLargeFile()) {
                sb.append("  Large file modified");
            } else {
                if (file.linesAdded() > 0) sb.append("  +").append(file.linesAdded());
                if (file.linesRemoved() > 0) sb.append(" -").append(file.linesRemoved());
                if (file.isTruncated()) sb.append(" (truncated)");
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }
}

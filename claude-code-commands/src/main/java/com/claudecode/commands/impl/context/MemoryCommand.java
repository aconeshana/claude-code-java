package com.claudecode.commands.impl.context;

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.metadata.SlashCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.core.process.ExternalEditorDefaults;
import com.claudecode.core.config.ClaudePaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Lists and edits user, project, local, and rule-scoped memory files. */
@SlashCommand(
    name = "memory",
    description = "Edit Claude memory files"
)
public class MemoryCommand implements AnnotatedCommand {

    @Override
    public CommandResult execute(CommandContext context, String args) {
        String arg = args != null ? args.trim() : "";

        // Interactive → always hand off to the picker dialog (Lanterna), even

        // interpret args, so /memory global must not become a separate UI path.
        if (context.presentation().memoryDialogLauncher() != null) {
            context.presentation().memoryDialogLauncher().run();
            return CommandResult.skip();
        }


// x which returns a
        // <MemoryFileSelector> JSX component immediately.




        String cwd = context.session().workingDirectory() != null ? context.session().workingDirectory() : System.getProperty("user.dir");

        Path globalClaude = userMemoryPath();
        Path projectClaude = Path.of(cwd, "CLAUDE.md");
        Path localClaude = Path.of(cwd, ".claude", "CLAUDE.md");

        if (arg.isEmpty()) {
            return listMemoryFiles(context, globalClaude, projectClaude, localClaude, cwd);
        }

        Path target;
        switch (arg.toLowerCase(Locale.ROOT)) {
            case "global"  -> target = globalClaude;
            case "project" -> target = projectClaude;
            case "local"   -> target = localClaude;
            default        -> target = Path.of(arg);
        }

        // Interactive TUI: route through the Lanterna pause/resume launcher
        // so vi doesn't paint on top of the alt-screen and corrupt state on
        // :q. Headless mode has no launcher and no Lanterna, so the direct
// ProcessBuilder in openInEditor is safe.
        if (context.presentation().openEditor() != null) {
            context.presentation().openEditor().accept(target);
            // The launcher channel is caption-less (shared with /plan open and
            // the agents wizard) — this command owns its transcript line.
            return CommandResult.of("Opened memory file at " + shortenPath(target));
        }
        return openInEditor(target);
    }

    static Path userMemoryPath() {
        return ClaudePaths.CLAUDE_MD;
    }

    private CommandResult listMemoryFiles(CommandContext context, Path global, Path project,
                                          Path local, String cwd) {
        List<String> files = new ArrayList<>();
        if (Files.exists(global))  files.add("global  → " + global);
        if (Files.exists(project)) files.add("project → " + project);
        if (Files.exists(local))   files.add("local   → " + local);

// Scan project-scoped Markdown rules.
        Path rulesDir = Path.of(cwd, ".claude", "rules");
        if (Files.isDirectory(rulesDir)) {
            try {
                for (Path rule : context.application().tooling().resources().markdownFiles(rulesDir)) {
                    files.add("rule    → " + rule);
                }
            } catch (IOException | InterruptedException _) {}
        }

        if (files.isEmpty()) {
            return CommandResult.of(
                """
                No memory files found.

                Use /memory global  — open ~/.claude/CLAUDE.md
                Use /memory project — open <cwd>/CLAUDE.md

                Learn more: https://code.claude.com/docs/en/memory""");
        }

        StringBuilder result = new StringBuilder("Memory files:\n\n");
        for (String file : files) result.append("  ").append(file).append('\n');
        result.append("\nUse /memory global|project|local|<path> to open a file in your editor.\n");
        result.append("To change editor, set $EDITOR or $VISUAL environment variable.");
        return CommandResult.of(result.toString());
    }

    private static CommandResult openInEditor(Path file) {
// Create file if it doesn't exist.
        if (!Files.exists(file)) {
            try {
                Files.createDirectories(file.getParent() != null ? file.getParent() : Path.of("."));
                Files.createFile(file);
            } catch (IOException e) {
                return CommandResult.of("Failed to create file: " + file + " — " + e.getMessage());
            }
        }

        String editor = SubprocessEnvironment.get("VISUAL");
        String editorSource = "$VISUAL";
        if (StringUtils.isBlank(editor)) {
            editor = SubprocessEnvironment.get("EDITOR");
            editorSource = "$EDITOR";
        }
        if (StringUtils.isBlank(editor)) {
            editor = ExternalEditorDefaults.defaultCommand();
            editorSource = "default";
        }

        try {
            Process p = new ProcessBuilder(editor, file.toString())
                .inheritIO()
                .start();
            p.waitFor();
        } catch (Exception e) {
            return CommandResult.of("Failed to open editor (" + editor + "): " + e.getMessage());
        }

        String editorHint = Strings.CS.equals("default", editorSource)
            ? "> To use a different editor, set the $EDITOR or $VISUAL environment variable."
            : "> Using " + editorSource + "=\"" + editor + "\". To change editor, set $EDITOR or $VISUAL environment variable.";
        return CommandResult.of("Opened memory file at " + shortenPath(file) + "\n\n" + editorHint);
    }

    /**
     * Shorten an absolute path for display: prefer {@code ~/} (HOME) or {@code./} (cwd) if either
     * applies and is shorter than the absolute form.
     */
    static String shortenPath(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        String s = abs.toString();
        String home = System.getProperty("user.home");
        String cwd = System.getProperty("user.dir");
        String toHome = Strings.CS.startsWith(s, home) ? "~" + s.substring(home.length()) : null;
        String toCwd  = Strings.CS.startsWith(s, cwd)  ? "./" + s.substring(cwd.length() + 1) : null;
        if (toHome != null && toCwd != null) {
            return toHome.length() <= toCwd.length() ? toHome : toCwd;
        }
        return toHome != null ? toHome : (toCwd != null ? toCwd : s);
    }
}

package com.claudecode.commands.impl.config;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.metadata.SlashCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.core.io.PathUtils;

import java.nio.file.Path;
import java.util.List;

/**
 * /add-dir — add a working directory to the active session's permission context, optionally
 * persisting it to local settings.
 */
@SlashCommand(
    name = "add-dir",
    description = "Add a new working directory"
)
public class AddDirCommand implements AnnotatedCommand {

    @Override
    public String argumentHint() { return "<path>"; }

    @Override
    public CommandResult execute(CommandContext context, String args) {
        String raw = args == null ? "" : args.trim();
        var permissionSnapshot = context.application().permissions().snapshot();

        if (raw.isEmpty()) {
            if (context.presentation().addDirDialogLauncher() != null) {
                context.presentation().addDirDialogLauncher().accept(null);
                return CommandResult.skip();
            }
            return CommandResult.of(
                """
              Usage: /add-dir <path>
              Adds the given directory to the active permission context so tools may read or \
              modify files inside it.""");
        }

        if (!permissionSnapshot.wired()) {
            return CommandResult.of(
                "Permission gate not wired in this REPL — cannot add directories at runtime.\n"
              + "Resolved path: " + resolveAndExpand(raw, context.session().workingDirectory()));
        }

        CommandContext.AddDirValidationOutcome outcome = validate(raw,
            context.session().workingDirectory(), permissionSnapshot.workingDirectories());
        if (!outcome.isValid()) {
            return CommandResult.of(outcome.errorMessage());
        }

        if (context.presentation().addDirDialogLauncher() != null) {

            context.presentation().addDirDialogLauncher().accept(outcome.resolvedPath());
            return CommandResult.skip();
        }

        return applyAddDirectory(context, outcome.resolvedPath(), false);
    }

    /**
     * Validates {@code path} for {@code /add-dir}, returning the public
     * {@link CommandContext.AddDirValidationOutcome} bridge — the entry
     * point {@code claude-code-ui}'s {@code AddDirDialog} calls (via
     * {@link CommandPresentationPorts#addDirValidator()}) since it can't reference the
     * package-private {@link AddDirValidation} directly. Bound to
     * {@link CommandPresentationPorts#addDirValidator()} at wiring time (closed over
     * the live {@code ToolPermissionContext}).
     */
    public static CommandContext.AddDirValidationOutcome validate(
            String path, String workingDirectory, List<Path> accessibleDirectories) {
        AddDirValidation.AddDirectoryResult result = AddDirValidation.validateDirectoryForWorkspace(
            path, workingDirectory, accessibleDirectories);
        if (result instanceof AddDirValidation.AddDirectoryResult.Success(var absolutePath)) {
            return new CommandContext.AddDirValidationOutcome(absolutePath, null);
        }
        return new CommandContext.AddDirValidationOutcome(null, AddDirValidation.addDirHelpMessage(result));
    }

    /**
     * Applies a directory chosen from the {@code /add-dir} dialog (or the headless argument fallback):
     * adds it to the session's {@link PermissionGate}, and when {@code remember} is true also persists
     * it to.
     */
    public CommandResult applyAddDirectory(CommandContext context, String absolutePath, boolean remember) {
        Path path = Path.of(absolutePath);
        context.application().permissions().addDirectory(path);
        String activeSessionId = context.session().currentSessionId() == null
            ? null : context.session().currentSessionId().get();
        context.application().sessions().recordSessionAlias(path, activeSessionId);

        String message;
        if (remember) {
            try {
                context.application().settings().sandbox()
                    .saveAdditionalDirectory(context.session().workingDirectory(), absolutePath);
                message = "Added " + absolutePath + " as a working directory and saved to local settings";
            } catch (RuntimeException e) {
                message = "Added " + absolutePath + " as a working directory. Failed to save to local settings: "
                    + e.getMessage();
            }
        } else {
            message = "Added " + absolutePath + " as a working directory for this session";
        }
        return CommandResult.of(message + " · /permissions to manage");
    }

    /**
     * Resolves a possibly-relative or {@code ~}-prefixed path against the
     * REPL's working directory. Returns an absolute normalized path.
     */
    static Path resolveAndExpand(String raw, String workingDirectory) {
        String expanded = PathUtils.expandTilde(raw);
        Path p = Path.of(expanded);
        if (!p.isAbsolute()) {
            String base = workingDirectory != null
                ? workingDirectory : System.getProperty("user.dir");
            p = Path.of(base).resolve(p);
        }
        return p.toAbsolutePath().normalize();
    }
}

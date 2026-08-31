package com.claudecode.tools.bash;

import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.WorkingDirectoryController;
import com.claudecode.permissions.WorkingDirectoryPaths;
import com.claudecode.tools.tasks.TaskOutputPaths;
import com.claudecode.core.config.EnvUtils;
import com.claudecode.core.io.FileUtils;
import com.claudecode.core.io.PathUtils;
import com.claudecode.core.platform.Platform;
import com.claudecode.core.process.SubprocessEnvironment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

/**
 * Captures a successful foreground Bash command's physical {@code pwd -P} and
 * publishes the resulting session cwd before the caller continues.
 *
 * <ul>
 *   <li>appends
 *       {@code pwd -P} to a Claude temp file.</li>
 *   <li>consumes the file synchronously after the
 *       command and updates only foreground/main-session cwd state.</li>
 *   <li>restores
 *       original cwd for maintain mode or when the observed cwd is outside all
 *       allowed working directories.</li>
 * </ul>
 */
public final class ShellWorkingDirectoryTracker {

    private final Path previous;
    private final Path trackingFile;
    private final WorkingDirectoryController controller;
    private final Function<String, String> envLookup;

    private ShellWorkingDirectoryTracker(Path previous, Path trackingFile,
                                         WorkingDirectoryController controller,
                                         Function<String, String> envLookup) {
        this.previous = previous;
        this.trackingFile = trackingFile;
        this.controller = controller;
        this.envLookup = envLookup;
    }

    public static ShellWorkingDirectoryTracker start(
            ToolExecutionContext context, Function<String, String> envLookup) throws IOException {
        return start(Path.of(context.workingDirectory()),
            context.workingDirectoryController(), envLookup);
    }

    public static ShellWorkingDirectoryTracker start(
            Path cwd, WorkingDirectoryController controller,
            Function<String, String> envLookup) throws IOException {
        WorkingDirectoryController actual = controller != null
            ? controller : WorkingDirectoryController.NOOP;
        if (!actual.mutable()) {
            return new ShellWorkingDirectoryTracker(cwd, null, actual, envLookup);
        }
        Path dir = TaskOutputPaths.claudeTempDir();
        Files.createDirectories(dir);
        Path file = Files.createTempFile(dir, "claude-pwd-", ".tmp");
        return new ShellWorkingDirectoryTracker(cwd.toAbsolutePath().normalize(), file,
            actual, envLookup != null ? envLookup : SubprocessEnvironment::get);
    }

    /** Returns the command with cwd capture appended, or the original command when disabled. */
    public String wrap(String command) {
        if (trackingFile == null) return command;
        String shellPath = Platform.IS_WINDOWS
            ? PathUtils.windowsPathToPosixPath(trackingFile.toString())
            : trackingFile.toString();
        return command + " && pwd -P >| " + shellQuote(shellPath);
    }

    /**
     * Applies the final cwd and returns the user-visible reset warning, if any.
     * Always removes the tracking file.
     */
    public String finish() {
        if (trackingFile == null) return null;
        try {
            String raw = Files.readString(trackingFile, StandardCharsets.UTF_8).trim();
            if (raw.isEmpty()) return null;
            if (Platform.IS_WINDOWS) raw = PathUtils.posixPathToWindowsPath(raw);
            Path observed = Path.of(raw).toRealPath();
            if (observed.equals(previous)) return null;

            boolean maintain = EnvUtils.isEnvTruthy(
                envLookup.apply("CLAUDE_BASH_MAINTAIN_PROJECT_WORKING_DIR"));
            boolean outsideAllowed = !isWithinAllowedDirectories(
                observed, controller.allowedDirectories());
            Path original = controller.originalDirectory();
            if (original == null) original = previous;
            original = original.toAbsolutePath().normalize();
            Path finalDirectory = maintain || outsideAllowed ? original : observed;
            controller.update(previous, finalDirectory);
            return outsideAllowed && !maintain
                ? "Shell cwd was reset to " + original
                : null;
        } catch (IOException | RuntimeException _) {
            return null;
        } finally {
            discard();
        }
    }

    /** Removes the tracking file without publishing a cwd (timeout/spawn failure). */
    public void discard() {
        if (trackingFile == null) return;
        try {
            Files.deleteIfExists(trackingFile);
        } catch (IOException _) {
            // Best-effort cleanup only.
        }
    }

    private static boolean isWithinAllowedDirectories(Path candidate, List<Path> allowed) {
        if (allowed == null || allowed.isEmpty()) return false;
        for (Path checked : FileUtils.pathsForPermissionCheck(candidate)) {
            boolean contained = false;
            for (Path root : allowed) {
                if (root != null && WorkingDirectoryPaths.isWithin(checked, root)) {
                    contained = true;
                    break;
                }
            }
            if (!contained) return false;
        }
        return true;
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}

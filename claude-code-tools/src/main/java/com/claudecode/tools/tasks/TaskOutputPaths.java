package com.claudecode.tools.tasks;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import com.claudecode.core.engine.SessionIdentity;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.session.SessionManager;
import com.claudecode.core.state.CwdState;
import com.sun.security.auth.module.UnixSystem;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Resolves the session-isolated files used by background task output.
 */
public final class TaskOutputPaths {

    private static final Pattern TASK_ID = Pattern.compile("[a-zA-Z0-9_-]{1,20}");

    private static volatile Supplier<String> sessionIdSupplier = () -> "default";
    private static volatile Supplier<Path> originalCwdSupplier = TaskOutputPaths::currentOriginalCwd;
    private static volatile Path claudeTempDirOverride;
    private static volatile boolean explicitlyConfigured;
    private static volatile Path memoizedOutputDir;

    private TaskOutputPaths() {}

    /** Wires the process session identity; call once from the composition root. */
    public static synchronized void configure(SessionIdentity sessionIdentity) {
        Objects.requireNonNull(sessionIdentity, "sessionIdentity");
        sessionIdSupplier = sessionIdentity::get;
        originalCwdSupplier = TaskOutputPaths::currentOriginalCwd;
        explicitlyConfigured = true;
    }

    /** Context-aware producer path for SDK/tests that do not use the CLI composition root. */
    public static Path outputPath(String taskId, ToolExecutionContext context) {
        initialize(context);
        return outputPath(taskId);
    }

    static void initialize(ToolExecutionContext context) {
        if (!explicitlyConfigured && memoizedOutputDir == null && context != null) {
            synchronized (TaskOutputPaths.class) {
                if (!explicitlyConfigured && memoizedOutputDir == null) {
                    sessionIdSupplier = context::sessionId;
                    originalCwdSupplier = () -> {
                        Path original = CwdState.getOriginalCwd();
                        return original != null ? original : Path.of(context.workingDirectory());
                    };
                }
            }
        }
    }

    /** Returns {@code <project-temp>/<captured-session>/tasks/<taskId>.output}. */
    public static Path outputPath(String taskId) {
        return outputDirectory().resolve(taskId + ".output");
    }

    /** Directory auto-allowed for Read and background-task writes. */
    public static Path outputDirectory() {
        Path resolved = memoizedOutputDir;
        if (resolved != null) return resolved;
        synchronized (TaskOutputPaths.class) {
            if (memoizedOutputDir == null) {
                Path cwd = originalCwdSupplier.get().toAbsolutePath().normalize();
                memoizedOutputDir = claudeTempDir()
                    .resolve(SessionManager.sanitizePath(cwd.toString()))
                    .resolve(sessionIdSupplier.get())
                    .resolve("tasks");
            }
            return memoizedOutputDir;
        }
    }


    public static String agentOutputTaskId(String filePath) {
        if (StringUtils.isBlank(filePath)) return null;
        try {
            Path path = Path.of(filePath).toAbsolutePath().normalize();
            Path outputDir = outputDirectory().toAbsolutePath().normalize();
            if (!outputDir.equals(path.getParent())) return null;
            String fileName = path.getFileName().toString();
            if (!Strings.CS.endsWith(fileName, ".output")) return null;
            String taskId = fileName.substring(0, fileName.length() - ".output".length());
            return TASK_ID.matcher(taskId).matches() ? taskId : null;
        } catch (RuntimeException _) {
            return null;
        }
    }


    public static Path claudeTempDir() {
        Path override = claudeTempDirOverride;
        if (override != null) return override;

        boolean windows = Strings.CI.contains(System.getProperty("os.name", ""), "win");
        String configuredTmp = SubprocessEnvironment.get("CLAUDE_CODE_TMPDIR");
        Path base = StringUtils.isNotBlank(configuredTmp)
            ? Path.of(configuredTmp)
            : Path.of(windows ? System.getProperty("java.io.tmpdir") : "/tmp");
        try {
            base = base.toRealPath();
        } catch (IOException _) {
            base = base.toAbsolutePath().normalize();
        }

        String name = windows ? "claude" : "claude-" + unixUid();
        return base.resolve(name);
    }

    private static long unixUid() {
        try {
            return new UnixSystem().getUid();
        } catch (RuntimeException | LinkageError _) {
            return 0L;
        }
    }

    private static Path currentOriginalCwd() {
        Path original = CwdState.getOriginalCwd();
        return original != null ? original : Path.of(System.getProperty("user.dir"));
    }

    static synchronized void configureForTest(Path claudeTempDir, String sessionId, Path originalCwd) {
        claudeTempDirOverride = claudeTempDir;
        sessionIdSupplier = () -> sessionId;
        originalCwdSupplier = () -> originalCwd;
        explicitlyConfigured = true;
    }

    static synchronized void resetForTest() {
        sessionIdSupplier = () -> "default";
        originalCwdSupplier = TaskOutputPaths::currentOriginalCwd;
        claudeTempDirOverride = null;
        explicitlyConfigured = false;
        memoizedOutputDir = null;
    }
}

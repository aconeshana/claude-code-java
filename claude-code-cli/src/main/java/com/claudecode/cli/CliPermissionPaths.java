package com.claudecode.cli;

import com.claudecode.core.memdir.AutoMemoryPrompt;
import com.claudecode.permissions.PermissionPaths;
import com.claudecode.session.SessionManager;
import com.claudecode.tools.plan.PlanFiles;
import com.claudecode.core.config.ClaudePaths;
import com.claudecode.tools.tasks.TaskOutputPaths;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * CLI-side {@link PermissionPaths} implementation.
 *
 * <p>Two of the roots — the session-scoped tool-results directory and the git-root-based
 * auto-memory directory — depend on state that changes while the process runs: every resume
 * swaps the session id, and a cross-project resume swaps the project root. They are therefore
 * resolved from live suppliers and memoized per {@code (cwd, sessionId)} pair rather than
 * frozen at construction.
 */
public final class CliPermissionPaths implements PermissionPaths {

    private final Supplier<Path> workingDirectory;
    private final Supplier<String> sessionId;
    private volatile Snapshot snapshot;

    private record Snapshot(Path workingDirectory, String sessionId, Set<Path> paths) {}

    public CliPermissionPaths(Supplier<Path> workingDirectory, Supplier<String> sessionId) {
        this.workingDirectory = workingDirectory;
        this.sessionId = sessionId;
    }

    /** Fixed-scope variant for tests and embedders with a single immutable session. */
    public CliPermissionPaths(Path workingDirectory, String sessionId) {
        this(() -> workingDirectory, () -> sessionId);
    }

    private Set<Path> resolve() {
        Path cwd = workingDirectory.get();
        String session = sessionId.get();
        Snapshot cached = snapshot;
        if (cached != null && cached.workingDirectory().equals(cwd)
                && Objects.equals(cached.sessionId(), session)) {
            return cached.paths();
        }
        Set<Path> roots = new LinkedHashSet<>();
        // User-level ~/.claude internals.
        roots.add(PlanFiles.getPlansDirectory());
        roots.add(ClaudePaths.TASKS_DIR);
        roots.add(ClaudePaths.SKILLS_DIR);
        roots.add(TaskOutputPaths.outputDirectory());
        // Session-scoped persisted tool results.
        roots.add(new SessionManager(cwd.toString()).getToolResultsDir(session));
        // Session memory (git-root based, env-overridable).
        roots.add(AutoMemoryPrompt.resolveAutoMemPath(cwd));
        Set<Path> paths = Set.copyOf(roots);
        snapshot = new Snapshot(cwd, session, paths);
        return paths;
    }

    @Override
    public Set<Path> internalEditablePaths() {
        return resolve();
    }

    @Override
    public Set<Path> internalReadablePaths() {
        return resolve();
    }
}

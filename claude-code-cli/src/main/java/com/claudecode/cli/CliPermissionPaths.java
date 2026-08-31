package com.claudecode.cli;

import com.claudecode.core.memdir.AutoMemoryPrompt;
import com.claudecode.permissions.PermissionPaths;
import com.claudecode.session.SessionManager;
import com.claudecode.tools.plan.PlanFiles;
import com.claudecode.core.config.ClaudePaths;
import com.claudecode.tools.tasks.TaskOutputPaths;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * CLI-side {@link PermissionPaths} implementation.
 */
public final class CliPermissionPaths implements PermissionPaths {

    private final Set<Path> paths;

    public CliPermissionPaths(Path workingDirectory, String sessionId) {
        Set<Path> roots = new LinkedHashSet<>();
        // User-level ~/.claude internals.
        roots.add(PlanFiles.getPlansDirectory());
        roots.add(ClaudePaths.TASKS_DIR);
        roots.add(ClaudePaths.SKILLS_DIR);
        roots.add(TaskOutputPaths.outputDirectory());
        // Session-scoped persisted tool results.
        roots.add(new SessionManager(workingDirectory.toString()).getToolResultsDir(sessionId));
        // Session memory (git-root based, env-overridable).
        roots.add(AutoMemoryPrompt.resolveAutoMemPath(workingDirectory));
        this.paths = Set.copyOf(roots);
    }

    @Override
    public Set<Path> internalEditablePaths() {
        return paths;
    }

    @Override
    public Set<Path> internalReadablePaths() {
        return paths;
    }
}

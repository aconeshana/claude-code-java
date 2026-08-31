package com.claudecode.core.git;

import com.claudecode.core.process.ExecutableFinder;
import com.claudecode.core.process.ProcessResult;
import com.claudecode.core.process.ProcessRunner;
import org.apache.commons.lang3.Strings;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Git helpers shared across modules (core / tools / services) so project-scoped state resolves
 * through worktrees to the same canonical root.
 */
public final class GitUtils {

    private GitUtils() {}

    /**
     * Resolves the canonical (main-repository) git root for {@code startPath}, walking through any git
     * worktree so all worktrees of the same repo map to the same root.
     */
    public static Path findCanonicalGitRoot(Path startPath) {
        List<String> out = runGit(startPath, "rev-parse", "--git-common-dir");
        if (out.isEmpty()) return null;
        Path commonDir = Path.of(out.getFirst().trim());
        if (!commonDir.isAbsolute()) {
            commonDir = startPath.toAbsolutePath().resolve(commonDir).normalize();
        }
        Path root = Strings.CS.equals(".git", String.valueOf(commonDir.getFileName()))
            ? commonDir.getParent() : commonDir;
        if (root == null) return startPath.toAbsolutePath();
        try {
            return root.toRealPath();
        } catch (IOException _) {
            return root;
        }
    }




    public static String currentBranch(Path cwd) {
// `rev-parse --abbrev-ref HEAD` fails before the first commit.
        List<String> symbolic = runGit(cwd, "symbolic-ref", "--short", "HEAD");
        if (!symbolic.isEmpty() && !symbolic.getFirst().trim().isEmpty()) {
            return symbolic.getFirst().trim();
        }
        List<String> out = runGit(cwd, "rev-parse", "--abbrev-ref", "HEAD");
        if (out.isEmpty()) return "HEAD";
        String branch = out.getFirst().trim();
        return branch.isEmpty() ? "HEAD" : branch;
    }

    /** Runs git in {@code cwd}; returns stdout lines on exit 0, else an empty list. */
    private static List<String> runGit(Path cwd, String... args) {
        List<String> command = new ArrayList<>();
        command.add(ExecutableFinder.find("git").map(Path::toString).orElse("git"));
        Collections.addAll(command, args);
        ProcessResult result = ProcessRunner.run(command, cwd, Duration.ofSeconds(30));
        return result.succeeded() ? result.stdoutLines() : List.of();
    }
}

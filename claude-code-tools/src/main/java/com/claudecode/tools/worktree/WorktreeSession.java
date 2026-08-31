package com.claudecode.tools.worktree;

import org.apache.commons.lang3.StringUtils;
/**
 * Snapshot of the current REPL's active git-worktree session.
 */
public record WorktreeSession(
    String originalCwd,
    String worktreePath,
    String worktreeName,
    String worktreeBranch,
    String originalBranch,
    String originalHeadCommit,
    String sessionId,
    String tmuxSessionName,
    boolean hookBased,
    long creationDurationMs,
    boolean usedSparsePaths,
    boolean projectRootMoved,
    boolean enteredExisting
) {
    /** Compatibility constructor for persisted/pre-path-support call sites. */
    public WorktreeSession(String originalCwd, String worktreePath, String worktreeName,
                           String worktreeBranch, String originalBranch, String originalHeadCommit,
                           String sessionId, String tmuxSessionName, boolean hookBased,
                           long creationDurationMs, boolean usedSparsePaths,
                           boolean projectRootMoved) {
        this(originalCwd, worktreePath, worktreeName, worktreeBranch, originalBranch,
            originalHeadCommit, sessionId, tmuxSessionName, hookBased, creationDurationMs,
            usedSparsePaths, projectRootMoved, false);
    }

    /** Convenience constructor for the common mid-session case (projectRootMoved=false). */
    public WorktreeSession(String originalCwd, String worktreePath, String worktreeName,
                           String worktreeBranch, String originalBranch, String originalHeadCommit,
                           String sessionId, String tmuxSessionName, boolean hookBased,
                           long creationDurationMs, boolean usedSparsePaths) {
        this(originalCwd, worktreePath, worktreeName, worktreeBranch, originalBranch,
            originalHeadCommit, sessionId, tmuxSessionName, hookBased, creationDurationMs,
            usedSparsePaths, false, false);
    }
    /** Whether this session carries a tmux name (drives dialog option layout). */
    public boolean hasTmuxSession() {
        return StringUtils.isNotBlank(tmuxSessionName);
    }
}

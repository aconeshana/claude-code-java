package com.claudecode.tools.worktree;


/**
 * Worktree metadata attached to a successful agent execution.
 *
 * <ul>
 *   <li>worktree metadata is
 *       returned as execution context instead of being encoded as optional
 *       fields on the agent error/result payload.</li>
 *   <li>the
 *       worktree path and optional branch are the values surfaced to callers.</li>
 * </ul>
 */
public record WorktreeInfo(String path, String branch) {
}

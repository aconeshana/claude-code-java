package com.claudecode.tools.worktree;

import java.util.Optional;

/**
 * Dependency-inversion seam letting {@link WorktreeService} (claude-code-tools) trigger
 * user-configured {@code WorktreeCreate}/{@code WorktreeRemove} hooks without depending on {@code
 * claude-code-services} (which owns {@code HookEngine} and depends on tools, so the reverse import
 * is impossible).
 */
public interface WorktreeHooks {

    /** Whether any {@code WorktreeCreate} hook is configured. */
    boolean hasCreateHook();

    /** Runs {@code WorktreeCreate} hooks; returns the created worktree path (hook stdout). */
    Optional<String> create(String slug);

    /** Runs {@code WorktreeRemove} hooks; returns whether any ran. */
    boolean remove(String worktreePath);
}

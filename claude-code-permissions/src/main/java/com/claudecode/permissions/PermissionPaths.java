package com.claudecode.permissions;

import java.nio.file.Path;
import java.util.Set;

/**
 * Provides the set of internal paths that are always editable/readable without prompting — session
 * memory, scratchpad, plan files, tool-results, tasks/teams, bundled-skills roots.
 */
public interface PermissionPaths {

    /** Roots whose contents are always editable without prompting. */
    Set<Path> internalEditablePaths();

    /** Roots whose contents are always readable without prompting. */
    Set<Path> internalReadablePaths();

    /** Conservative default: no internal carve-outs. */
    PermissionPaths EMPTY = new PermissionPaths() {
        @Override public Set<Path> internalEditablePaths() { return Set.of(); }

        @Override public Set<Path> internalReadablePaths() { return Set.of(); }
    };
}

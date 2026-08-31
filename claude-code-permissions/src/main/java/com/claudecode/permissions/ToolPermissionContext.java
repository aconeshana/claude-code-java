package com.claudecode.permissions;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Immutable context for permission checking, containing the current mode, active rules, working
 * directory, and additional directories.
 */
public record ToolPermissionContext(
    Path workingDirectory,
    PermissionMode mode,
    List<PermissionRule> rules,
    Map<Path, RuleSource> additionalDirs,
    PermissionPathContext pathContext
) {

    /** Source-compatible constructor for existing callers. */
    public ToolPermissionContext(Path workingDirectory, PermissionMode mode,
                                 List<PermissionRule> rules,
                                 Map<Path, RuleSource> additionalDirs) {
        this(workingDirectory, mode, rules, additionalDirs,
            PermissionPathContext.defaults(workingDirectory));
    }

    /**
     * Compact constructor ensuring defensive copies of mutable collections.
     */
    public ToolPermissionContext {
        rules = List.copyOf(rules);
        additionalDirs = Map.copyOf(additionalDirs);
        pathContext = pathContext == null
            ? PermissionPathContext.defaults(workingDirectory) : pathContext;
    }

    /**
     * Creates a minimal context with just a working directory and default mode.
     */
    public static ToolPermissionContext of(Path workingDirectory) {
        return new ToolPermissionContext(workingDirectory, PermissionMode.DEFAULT, List.of(), Map.of());
    }

    /**
     * Returns a new context with the given rules added.
     */
    public ToolPermissionContext addRules(List<PermissionRule> newRules) {
        List<PermissionRule> merged = new ArrayList<>(this.rules);
        merged.addAll(newRules);
        return new ToolPermissionContext(workingDirectory, mode, merged, additionalDirs, pathContext);
    }

    /**
     * Returns a new context with all rules replaced.
     */
    public ToolPermissionContext replaceRules(List<PermissionRule> newRules) {
        return new ToolPermissionContext(workingDirectory, mode, newRules, additionalDirs, pathContext);
    }

    /**
     * Returns a new context with rules matching the predicate removed.
     */
    public ToolPermissionContext removeRules(Predicate<PermissionRule> filter) {
        List<PermissionRule> remaining = this.rules.stream()
            .filter(filter.negate())
            .toList();
        return new ToolPermissionContext(workingDirectory, mode, remaining, additionalDirs, pathContext);
    }

    /**
     * Returns a new context with the mode changed.
     */
    public ToolPermissionContext setMode(PermissionMode newMode) {
        return new ToolPermissionContext(workingDirectory, newMode, rules, additionalDirs, pathContext);
    }

    /**
     * Returns a new context with additional directories added (runtime source
     * {@link RuleSource#SESSION}). matches a {@code /add-dir} performed this
     * session.
     */
    public ToolPermissionContext addDirectories(List<Path> dirs) {
        return addDirectories(dirs, RuleSource.SESSION);
    }

    /**
     * Returns a new context with additional directories added, recording {@code source} as each
     * directory's provenance.
     */
    public ToolPermissionContext addDirectories(Collection<Path> dirs, RuleSource source) {
        Map<Path, RuleSource> merged = new LinkedHashMap<>(this.additionalDirs);
        for (Path dir : dirs) {
            // Normalize the key so equivalent spellings ("./x", "x/", "x/./y")
            // de-duplicate to the same entry — matching how WorkingDirectoryPaths
            // normalizes at check time and keeping add/remove symmetric.
            merged.put(dir.normalize(), source);
        }
        return new ToolPermissionContext(workingDirectory, mode, rules, merged, pathContext);
    }

    /**
     * Returns a new context with the specified directories removed (by path).
     */
    public ToolPermissionContext removeDirectories(List<Path> dirs) {
        Map<Path, RuleSource> remaining = new LinkedHashMap<>(this.additionalDirs);
        dirs.stream().map(Path::normalize).toList().forEach(remaining.keySet()::remove);
        return new ToolPermissionContext(workingDirectory, mode, rules, remaining, pathContext);
    }

    /**
     * Builder for constructing ToolPermissionContext instances.
     */
    public static class Builder {
        private Path workingDirectory = Path.of(".");
        private PermissionMode mode = PermissionMode.DEFAULT;
        private List<PermissionRule> rules = new ArrayList<>();
        private Map<Path, RuleSource> additionalDirs = new LinkedHashMap<>();
        private PermissionPathContext pathContext;

        public Builder workingDirectory(Path workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }

        public Builder mode(PermissionMode mode) {
            this.mode = mode;
            return this;
        }

        public Builder rules(List<PermissionRule> rules) {
            this.rules = new ArrayList<>(rules);
            return this;
        }

        public Builder additionalDirs(Map<Path, RuleSource> additionalDirs) {
            this.additionalDirs = new LinkedHashMap<>(additionalDirs);
            return this;
        }

        public Builder pathContext(PermissionPathContext pathContext) {
            this.pathContext = pathContext;
            return this;
        }

        public ToolPermissionContext build() {
            return new ToolPermissionContext(workingDirectory, mode, rules, additionalDirs,
                pathContext == null ? PermissionPathContext.defaults(workingDirectory) : pathContext);
        }
    }

    /**
     * Creates a new builder.
     */
    public static Builder builder() {
        return new Builder();
    }
}

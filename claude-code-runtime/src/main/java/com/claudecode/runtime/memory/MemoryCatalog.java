package com.claudecode.runtime.memory;

import java.nio.file.Path;
import java.util.List;

/**
 * Memory settings/catalog port consumed by interactive presentation layers.
 */
public interface MemoryCatalog {

    enum Scope { USER, PROJECT, LOCAL, MANAGED }

    record File(Path path, Scope scope, Path parent) {}

    List<File> scan(Path cwd);

    default void clearCache() {}

    default boolean autoMemoryEnabled() { return false; }

    default void setAutoMemoryEnabled(boolean enabled) {}

    default Path autoMemoryDirectory(Path cwd) { return null; }

    default boolean autoDreamEnabled() { return false; }

    default void setAutoDreamEnabled(boolean enabled) {}

    default boolean autoDreamRunning() { return false; }

    default long lastDreamAtMillis(Path cwd) { return 0L; }

    default boolean teamMemoryEnabled() { return false; }

    default Path teamMemoryDirectory(Path cwd) { return null; }

    default Path agentMemoryDirectory(String agentType, String memoryScope, Path cwd) {
        return null;
    }

    static MemoryCatalog empty() {
        return _ -> List.of();
    }
}

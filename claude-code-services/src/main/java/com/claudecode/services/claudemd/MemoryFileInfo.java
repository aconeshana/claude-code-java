package com.claudecode.services.claudemd;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Information about a discovered CLAUDE.md memory file.
 */
public record MemoryFileInfo(
    Path path,
    MemoryType type,
    String content,
    List<String> globs,
    Path parent,
    boolean contentDiffersFromDisk,
    String rawContent
) {
    public MemoryFileInfo {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(content, "content");
        // Defensive copy — protects against callers mutating the list post-construction.
        // Null globs stays null (semantically distinct from empty list).
        globs = globs == null ? null : List.copyOf(globs);
    }

    /** Convenience constructor for the eager claudeMd path (no stripping occurred). */
    public MemoryFileInfo(Path path, MemoryType type, String content, List<String> globs, Path parent) {
        this(path, type, content, globs, parent, false, null);
    }

    /** @return true if this file was pulled in through an {@code @path} reference. */
    public boolean isImported() {
        return parent != null;
    }
}

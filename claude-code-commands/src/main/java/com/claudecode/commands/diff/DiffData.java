package com.claudecode.commands.diff;

import com.claudecode.core.diff.StructuredPatchHunk;

import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of the working-tree-vs-HEAD git diff, produced by {@link GitDiffCollector} and
 * consumed by the {@code /diff} UI.
 */
public record DiffData(
    Stats stats,
    List<DiffFile> files,
    Map<String, List<StructuredPatchHunk>> hunks
) {

    public DiffData {
        files = files != null ? List.copyOf(files) : List.of();
        hunks = hunks != null ? Map.copyOf(hunks) : Map.of();
    }


    public record Stats(int filesCount, int linesAdded, int linesRemoved) {}


    public record DiffFile(
        String path,
        int linesAdded,
        int linesRemoved,
        boolean isBinary,
        boolean isLargeFile,
        boolean isTruncated,
        boolean isNewFile,
        boolean isUntracked
    ) {}
}

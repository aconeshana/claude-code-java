package com.claudecode.core.diff;

import org.apache.commons.lang3.Strings;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One unified-diff hunk in structured form.
 */
public record StructuredPatchHunk(
    @JsonProperty("oldStart") int oldStart,
    @JsonProperty("oldLines") int oldLines,
    @JsonProperty("newStart") int newStart,
    @JsonProperty("newLines") int newLines,
    @JsonProperty("lines") List<String> lines
) {
    @JsonCreator
    public StructuredPatchHunk {
        lines = lines != null ? List.copyOf(lines) : List.of();
    }

    /** Count of {@code +} lines in this hunk. */
    public int addedCount() {
        return (int) lines.stream().filter(l -> Strings.CS.startsWith(l, "+")).count();
    }

    /** Count of {@code -} lines in this hunk. */
    public int removedCount() {
        return (int) lines.stream().filter(l -> Strings.CS.startsWith(l, "-")).count();
    }
}

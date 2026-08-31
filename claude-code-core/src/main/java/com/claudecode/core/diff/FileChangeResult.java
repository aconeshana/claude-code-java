package com.claudecode.core.diff;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Structured record of a file mutation performed by the Edit / Write tools — the payload carried on
 * {@code UserMessage.toolUseResult} so downstream consumers ({@code /diff}'s per-turn view) can
 * reconstruct what changed in each conversational turn without re-reading files.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FileChangeResult(
    @JsonProperty("filePath") String filePath,
    @JsonProperty("structuredPatch") List<StructuredPatchHunk> structuredPatch,
    /** {@code "create"} / {@code "update"} (Write tool) — null for Edit results. */
    @JsonProperty("type") String type,
    /** Full content written by Write for both create and update; null for Edit. */
    @JsonProperty("content") String content,
    /** Pre-mutation file text; explicitly null for a newly-created Write target. */
    @JsonProperty("originalFile")
    @JsonInclude(JsonInclude.Include.ALWAYS) String originalFile,
    /** Actual matched old string for Edit; absent for Write. */
    @JsonProperty("oldString") String oldString,
    /** Model-proposed replacement string for Edit; absent for Write. */
    @JsonProperty("newString") String newString,
    /** Whether the user changed the proposed edit in the approval UI. */
    @JsonProperty("userModified") boolean userModified,
    /** Edit's replace-all flag; absent for Write. */
    @JsonProperty("replaceAll") Boolean replaceAll
) {
    @JsonCreator
    public FileChangeResult {
        structuredPatch = structuredPatch != null ? List.copyOf(structuredPatch) : List.of();
    }

    public static FileChangeResult edited(String filePath, List<StructuredPatchHunk> patch) {
        return edited(filePath, null, null, null, patch, false, false);
    }

    public static FileChangeResult edited(String filePath, String oldString, String newString,
                                          String originalFile, List<StructuredPatchHunk> patch,
                                          boolean userModified, boolean replaceAll) {
        return new FileChangeResult(filePath, patch, null, null, originalFile,
            oldString, newString, userModified, replaceAll);
    }

    public static FileChangeResult created(String filePath, String content) {
        return new FileChangeResult(filePath, List.of(), "create", content, null,
            null, null, false, null);
    }

    public static FileChangeResult updated(String filePath, List<StructuredPatchHunk> patch) {
        return new FileChangeResult(filePath, patch, "update", null, null,
            null, null, false, null);
    }

    public static FileChangeResult updated(String filePath, String content,
                                           String originalFile,
                                           List<StructuredPatchHunk> patch) {
        return new FileChangeResult(filePath, patch, "update", content, originalFile,
            null, null, false, null);
    }
}

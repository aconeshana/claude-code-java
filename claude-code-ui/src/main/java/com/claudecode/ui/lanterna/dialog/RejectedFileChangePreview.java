package com.claudecode.ui.lanterna.dialog;

import org.apache.commons.lang3.StringUtils;

import com.claudecode.core.diff.StructuredPatchHunk;
import java.util.ArrayList;
import java.util.List;

/** Immutable projection used to render a rejected file-changing tool without disk I/O. */
public record RejectedFileChangePreview(
        Kind kind,
        String operation,
        String filePath,
        String cellId,
        List<StructuredPatchHunk> hunks,
        String content,
        String language) {

    public enum Kind { FILE, NOTEBOOK }

    public RejectedFileChangePreview {
        kind = kind == null ? Kind.FILE : kind;
        operation = operation == null ? "write" : operation;
        filePath = filePath == null ? "" : filePath;
        cellId = cellId == null ? "" : cellId;
        hunks = List.copyOf(hunks == null ? List.of() : hunks);
        content = content == null ? "" : content;
        language = language == null ? filePath : language;
    }

    public static RejectedFileChangePreview diff(
            String operation, String filePath, List<StructuredPatchHunk> hunks) {
        return new RejectedFileChangePreview(
            Kind.FILE, operation, filePath, "", hunks, "", filePath);
    }

    public static RejectedFileChangePreview source(
            String operation, String filePath, String content, String language) {
        return new RejectedFileChangePreview(
            Kind.FILE, operation, filePath, "", List.of(), content, language);
    }

    /** Deterministic input-only fallback; it does not inspect or infer the current file. */
    public static RejectedFileChangePreview inputEdit(
            String filePath, String oldContent, String newContent) {
        if (StringUtils.isEmpty(oldContent)) {
            return source("write", filePath, newContent, filePath);
        }
        String[] oldLines = oldContent.split("\\n", -1);
        String[] newLines = (newContent == null ? "" : newContent).split("\\n", -1);
        List<String> lines = new ArrayList<>(oldLines.length + newLines.length);
        for (String line : oldLines) lines.add("-" + line);
        for (String line : newLines) lines.add("+" + line);
        return diff("update", filePath, List.of(new StructuredPatchHunk(
            1, oldLines.length, 1, newLines.length, lines)));
    }

    public static RejectedFileChangePreview notebook(
            String operation, String filePath, String cellId,
            List<StructuredPatchHunk> hunks, String content, String language) {
        return new RejectedFileChangePreview(
            Kind.NOTEBOOK, operation, filePath, cellId, hunks, content, language);
    }
}

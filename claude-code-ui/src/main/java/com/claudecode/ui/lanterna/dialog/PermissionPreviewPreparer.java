package com.claudecode.ui.lanterna.dialog;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.PermissionAskContext;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Builds all blocking/CPU-heavy permission presentation state off the GUI thread. */
public final class PermissionPreviewPreparer {
    private static final PermissionPreviewPreparer STANDARD =
        new PermissionPreviewPreparer(FileSnapshotReader.STANDARD);

    private final FileSnapshotReader fileSnapshotReader;

    public PermissionPreviewPreparer(FileSnapshotReader fileSnapshotReader) {
        this.fileSnapshotReader = fileSnapshotReader;
    }

    public static PermissionPreviewPreparer standard() { return STANDARD; }

    public PreparedPermissionPrompt prepare(PermissionAskContext source) {
        PermissionAskContext context = snapshotContext(source);
        PermissionRequestBody body = PermissionRequestBody.from(context, fileSnapshotReader);
        return new PreparedPermissionPrompt(context, body, rejectionPreview(context, body));
    }

    public PermissionAskContext snapshotContext(PermissionAskContext source) {
        if (source == null) return PermissionAskContext.simple(null, null, null);
        JsonNode input = source.input();
        return source.toBuilder().input(input == null ? null : input.deepCopy()).build();
    }

    private static RejectedFileChangePreview rejectionPreview(
            PermissionAskContext context, PermissionRequestBody body) {
        String tool = context.toolName();
        JsonNode input = context.input();
        if (body instanceof PermissionRequestBody.FileChange change) {
            boolean edit = Strings.CS.equalsAny(tool, "Edit", "FileEdit");
            String oldString = text(input, "old_string", "old_str");
            String newString = text(input, "new_string", "new_str");
            if (edit && oldString.isEmpty()) {
                return RejectedFileChangePreview.source(
                    "write", change.filePath(), newString, change.filePath());
            }
            String operation = edit || StringUtils.isEmpty(change.contentPreview())
                ? "update" : "write";
            if (!change.hunks().isEmpty()) {
                return RejectedFileChangePreview.diff(
                    operation, change.filePath(), change.hunks());
            }
            if (!StringUtils.isEmpty(change.contentPreview())) {
                return RejectedFileChangePreview.source(
                    operation, change.filePath(), change.contentPreview(), change.filePath());
            }
            return RejectedFileChangePreview.diff(operation, change.filePath(), List.of());
        }
        if (body instanceof PermissionRequestBody.NotebookEdit notebook) {
            String mode = text(input, "edit_mode");
            if (StringUtils.isBlank(mode)) mode = "replace";
            String operation = Strings.CS.equals("delete", mode)
                ? "delete" : mode + " cell in";
            return RejectedFileChangePreview.notebook(
                operation, notebook.filePath(), text(input, "cell_id"), notebook.hunks(),
                notebook.contentPreview(), notebook.language());
        }
        if (body instanceof PermissionRequestBody.SedEdit(_, _, _, var filePath, var hunks, _, _)) {
            return RejectedFileChangePreview.diff("update", filePath, hunks);
        }
        return null;
    }

    private static String text(JsonNode input, String... fields) {
        if (input == null) return "";
        for (String field : fields) {
            JsonNode value = input.get(field);
            if (value != null && value.isTextual()) return value.asText();
        }
        return "";
    }
}

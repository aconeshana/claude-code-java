package com.claudecode.ui.lanterna.dialog;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.diff.DiffHunks;
import com.claudecode.core.diff.StructuredPatchHunk;
import com.claudecode.core.engine.PermissionAskContext;
import com.claudecode.tools.files.FileEditTool;
import com.claudecode.tools.bash.SedEditParser;
import com.claudecode.core.io.PathUtils;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Typed permission-body router, keeping per-tool presentation out of the generic dialog.
 */
sealed interface PermissionRequestBody {

    String title();

    record Generic(String title, String summary, String description)
            implements PermissionRequestBody {}

    record Mcp(String title, String invocation, String description)
            implements PermissionRequestBody {}

    record FileChange(
            String title,
            String subtitle,
            String question,
            String filePath,
            List<StructuredPatchHunk> hunks,
            String contentPreview,
            String warning) implements PermissionRequestBody {
        public FileChange {
            hunks = hunks == null ? List.of() : List.copyOf(hunks);
            contentPreview = contentPreview == null ? "" : contentPreview;
            warning = warning == null ? "" : warning;
        }
    }

    record NotebookEdit(String title, String subtitle, String question, String filePath,
                        String description, List<StructuredPatchHunk> hunks,
                        String contentPreview, String language) implements PermissionRequestBody {
        public NotebookEdit {
            hunks = hunks == null ? List.of() : List.copyOf(hunks);
            contentPreview = contentPreview == null ? "" : contentPreview;
        }
    }

    record SedEdit(String title, String subtitle, String question, String filePath,
                   List<StructuredPatchHunk> hunks, String noChangesMessage,
                   JsonNode updatedInput) implements PermissionRequestBody {
        public SedEdit {
            hunks = hunks == null ? List.of() : List.copyOf(hunks);
        }
    }

    static PermissionRequestBody from(PermissionAskContext context) {
        return from(context, FileSnapshotReader.STANDARD);
    }

    static PermissionRequestBody from(
            PermissionAskContext context, FileSnapshotReader fileSnapshotReader) {
        String toolName = context == null ? null : context.toolName();
        JsonNode input = context == null ? null : context.input();
        if (Strings.CS.equals("Edit", toolName)) return fileEdit(input, fileSnapshotReader);
        if (Strings.CS.equals("Write", toolName)) return fileWrite(input, fileSnapshotReader);
        if (Strings.CS.equals("NotebookEdit", toolName)) {
            return notebookEdit(input, fileSnapshotReader);
        }
        if (Strings.CS.equals("Bash", toolName)) {
            SedEdit sed = sedEdit(input, fileSnapshotReader);
            if (sed != null) return sed;
        }
        PermissionDialog.McpNameParts mcp = PermissionDialog.parseMcpToolName(toolName);
        if (mcp != null) return mcp(mcp, input, context.toolDescription());
        return new Generic(PermissionDialog.toolTitle(toolName),
            PermissionDialog.summarizeInputForBody(toolName, input), description(input));
    }

    private static FileChange fileEdit(JsonNode input, FileSnapshotReader fileSnapshotReader) {
        String rawPath = text(input, "file_path", "path");
        String oldString = text(input, "old_string", "old_str");
        String newString = text(input, "new_string", "new_str");
        boolean replaceAll = input != null && input.path("replace_all").asBoolean(false);
        Path path = PathUtils.expandPath(rawPath, System.getProperty("user.dir", "."));
        FileSnapshotReader.FileSnapshot snapshot = fileSnapshotReader.read(path);
        String content = snapshot.readable() && snapshot.exists()
            ? snapshot.content() : oldString;
        String warning = snapshot.warning();

        FileEditTool.EditPreview preview = FileEditTool.previewEdit(
            content, oldString, newString, replaceAll);
        List<StructuredPatchHunk> hunks = preview.hunks();
        if (!preview.error().isEmpty()) {
            warning = preview.error();
            hunks = DiffHunks.compute(oldString, newString);
        }
        String subtitle = relativeToCwd(path);
        String name = path.getFileName() == null ? rawPath : path.getFileName().toString();
        return new FileChange("Edit file", subtitle,
            "Do you want to make this edit to " + name + "?",
            path.toString(), hunks, "", warning);
    }

    private static FileChange fileWrite(JsonNode input, FileSnapshotReader fileSnapshotReader) {
        String rawPath = text(input, "file_path", "path");
        String newContent = text(input, "content");
        Path path = PathUtils.expandPath(rawPath, System.getProperty("user.dir", "."));
        FileSnapshotReader.FileSnapshot snapshot = fileSnapshotReader.read(path);
        String oldContent = snapshot.readable() ? snapshot.content() : "";
        String warning = snapshot.warning();
        boolean exists = snapshot.exists();
        List<StructuredPatchHunk> hunks = exists
            ? DiffHunks.compute(oldContent, newContent) : List.of();
        String subtitle = relativeToCwd(path);
        String name = path.getFileName() == null ? rawPath : path.getFileName().toString();
        return new FileChange(exists ? "Overwrite file" : "Create file", subtitle,
            "Do you want to " + (exists ? "overwrite " : "create ") + name + "?",
            path.toString(), hunks, exists ? "" : newContent, warning);
    }

    private static NotebookEdit notebookEdit(
            JsonNode input, FileSnapshotReader fileSnapshotReader) {
        String rawPath = text(input, "notebook_path");
        String cellId = text(input, "cell_id");
        String newSource = text(input, "new_source");
        String cellType = text(input, "cell_type");
        String mode = text(input, "edit_mode");
        if (StringUtils.isBlank(mode)) mode = "replace";
        Path path = PathUtils.expandPath(rawPath, System.getProperty("user.dir", "."));
        FileSnapshotReader.FileSnapshot snapshot = fileSnapshotReader.read(path);
        String oldSource = notebookCellSource(snapshot.content(), cellId);
        List<StructuredPatchHunk> hunks = Strings.CS.equals("replace", mode)
            ? DiffHunks.compute(oldSource, newSource) : List.of();
        String preview = Strings.CS.equals("delete", mode) ? oldSource
            : Strings.CS.equals("insert", mode) ? newSource : "";
        String action = switch (mode) {
            case "insert" -> "insert this cell into";
            case "delete" -> "delete this cell from";
            default -> "make this edit to";
        };
        String description = switch (mode) {
            case "insert" -> "Insert new cell";
            case "delete" -> "Delete cell";
            default -> "Replace cell contents";
        } + " for cell " + cellId + (StringUtils.isBlank(cellType) ? "" : " (" + cellType + ")");
        String name = path.getFileName() == null ? rawPath : path.getFileName().toString();
        return new NotebookEdit("Edit notebook", relativeToCwd(path),
            "Do you want to " + action + " " + name + "?", path.toString(), description,
            hunks, preview, Strings.CS.equals("markdown", cellType) ? "markdown" : "python");
    }

    private static SedEdit sedEdit(JsonNode input, FileSnapshotReader fileSnapshotReader) {
        if (input == null || !input.isObject()) return null;
        SedEditParser.SedEditInfo info = SedEditParser.parse(text(input, "command"));
        if (info == null) return null;
        Path path = PathUtils.expandPath(info.filePath(), System.getProperty("user.dir", "."));
        FileSnapshotReader.FileSnapshot snapshot = fileSnapshotReader.read(path);
        if (!snapshot.readable()) return null;
        boolean exists = snapshot.exists();
        String oldContent = snapshot.content();
        String newContent = SedEditParser.apply(oldContent, info);
        List<StructuredPatchHunk> hunks = Strings.CS.equals(oldContent, newContent)
            ? List.of() : DiffHunks.compute(oldContent, newContent);
        ObjectNode updated =
            ((ObjectNode) input).deepCopy();
        var simulated = updated.putObject("_simulatedSedEdit");
        simulated.put("filePath", path.toString());
        simulated.put("newContent", newContent);
        String name = path.getFileName() == null ? info.filePath() : path.getFileName().toString();
        return new SedEdit("Edit file", relativeToCwd(path),
            "Do you want to make this edit to " + name + "?", path.toString(), hunks,
            exists ? "Pattern did not match any content" : "File does not exist", updated);
    }

    private static String notebookCellSource(String notebookContent, String cellId) {
        try {
            JsonNode cells = JsonUtils.getMapper().readTree(notebookContent).path("cells");
            JsonNode cell = null;
            if (Strings.CS.startsWith(cellId, "cell-")) {
                try {
                    int index = Integer.parseInt(cellId.substring(5));
                    if (index >= 0 && index < cells.size()) cell = cells.get(index);
                } catch (NumberFormatException _) { }
            }
            if (cell == null) {
                for (JsonNode candidate : cells) {
                    if (Strings.CS.equals(cellId, candidate.path("id").asText())) {
                        cell = candidate;
                        break;
                    }
                }
            }
            if (cell == null) return "";
            JsonNode source = cell.path("source");
            if (source.isArray()) {
                StringBuilder joined = new StringBuilder();
                source.forEach(part -> joined.append(part.asText()));
                return joined.toString();
            }
            return source.asText("");
        } catch (Exception _) {
            return "";
        }
    }

    private static Mcp mcp(PermissionDialog.McpNameParts name, JsonNode input,
                           String description) {
        String args = mcpArgs(input);
        String invocation = name.server() + " - " + name.tool()
            + "(" + args + ") (MCP)";
        return new Mcp("Tool use", invocation, truncateDescription(description));
    }

    private static String mcpArgs(JsonNode input) {
        if (input == null || !input.isObject() || input.isEmpty()) return "";
        List<String> fields = new ArrayList<>();
        input.fields().forEachRemaining(entry -> {
            String rendered = entry.getValue() == null ? "null" : entry.getValue().toString();
            fields.add(entry.getKey() + ": " + rendered);
        });
        return String.join(", ", fields);
    }

    private static String truncateDescription(String value) {
        if (StringUtils.isBlank(value)) return "";
        String[] lines = value.strip().split("\\R", -1);
        if (lines.length <= 3) return String.join("\n", lines);
        return String.join("\n", lines[0], lines[1], lines[2]) + "…";
    }

    private static String description(JsonNode input) {
        return text(input, "description");
    }

    private static String text(JsonNode input, String... keys) {
        if (input == null) return "";
        for (String key : keys) {
            JsonNode value = input.get(key);
            if (value != null && value.isTextual()) return value.asText();
        }
        return "";
    }

    private static String relativeToCwd(Path path) {
        try {
            Path cwd = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
            return cwd.relativize(path.toAbsolutePath().normalize()).toString();
        } catch (IllegalArgumentException _) {
            return path.toString();
        }
    }
}

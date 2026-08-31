package com.claudecode.tools.files;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.FileStateCache;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.claudecode.core.io.FileUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.claudecode.tools.AnnotatedTool;
import com.claudecode.tools.BuiltInTool;
import com.claudecode.tools.ToolTexts;
import com.claudecode.tools.ToolCallResult;

/**
 * NotebookEditTool — edit Jupyter notebook cells.
 */
@BuiltInTool(
    name = "NotebookEdit",
    shouldDefer = true
)
public class NotebookEditTool extends AnnotatedTool<JsonNode, String> {


    @Override
    public String searchHint() {
        return "edit Jupyter notebook cells (.ipynb)";
    }

    private static final JsonNode SCHEMA = buildSchema();
    private static final Random RANDOM = new Random();
    private static final Pattern CELL_ID_PATTERN = Pattern.compile("^cell-(\\d+)$");
    private static final class InvocationCapture {
        private ObjectNode structuredOutput;
    }

    @Override public String description() {


        return ToolTexts.description("NotebookEdit");
    }

    @Override public JsonNode inputSchema() { return SCHEMA; }


    @Override
    public Object toAutoClassifierInput(JsonNode input) {
        if (input == null) return "";
        String mode = input.hasNonNull("edit_mode") ? input.path("edit_mode").asText() : "replace";
        String path = input.path("notebook_path").asText("");
        String source = input.path("new_source").asText("");
        return StringUtils.isBlank(path) ? "" : path + " " + mode + ": " + source;
    }

    @Override
    public String call(JsonNode input, ToolExecutionContext context) {
        return invoke(input, context, new InvocationCapture());
    }

    @Override
    public ToolCallResult<String> callWithResult(JsonNode input, ToolExecutionContext context) {
        InvocationCapture capture = new InvocationCapture();
        String text = invoke(input, context, capture);
        ToolResult mapped = capture.structuredOutput == null || Strings.CS.startsWith(text, "Error:")
            ? null : ToolResult.success(text).withToolUseResult(capture.structuredOutput);
        return new ToolCallResult<>(text, mapped);
    }

    private String invoke(JsonNode input, ToolExecutionContext context, InvocationCapture capture) {
        String notebookPath = text(input, "notebook_path");
        String cellId = text(input, "cell_id");
        String newSource = text(input, "new_source");
        String cellType = text(input, "cell_type");
        String editMode = input.has("edit_mode") && !input.get("edit_mode").isNull()
                ? input.get("edit_mode").asText("") : "replace";

        if (StringUtils.isBlank(notebookPath)) {
            return "Error: notebook_path is required";
        }

        Path path = Path.of(context.workingDirectory()).resolve(notebookPath);

// BEFORE normalize. On POSIX normalize collapses a leading "//" to "/",
        // so the UNC short-circuit below must run on the un-normalized resolved path
        // or it would never fire (a "//server/share/x" notebook would be wrongly
        // rejected as non-.ipynb). absolutePath stays normalized for cache/history keys.
        String resolvedPath = path.toAbsolutePath().toString();
        String absolutePath = path.toAbsolutePath().normalize().toString();


        // ALL filesystem validation (prevents NTLM credential leaks). Check on the
        // un-normalized path so it fires on POSIX too.
        boolean isUnc = Strings.CS.startsWith(resolvedPath, "\\\\") || Strings.CS.startsWith(resolvedPath, "//");
        if (!isUnc) {

            // hidden file (so a file literally named ".ipynb" has no extension and is
            // rejected), unlike a naive endsWith(".ipynb").
            if (!Strings.CS.equals(".ipynb", extname(Path.of(absolutePath)))) {
                return "Error: File must be a Jupyter notebook (.ipynb file). "
                        + "For editing other file types, use the FileEdit tool.";
            }
        }

        if (!List.of("replace", "insert", "delete").contains(editMode)) {
            return "Error: Edit mode must be replace, insert, or delete.";
        }


        if (Strings.CS.equals("insert", editMode) && StringUtils.isBlank(cellType)) {
            return "Error: Cell type is required when using edit_mode=insert.";
        }

        if (!Files.exists(path)) {
            return "Error: notebook not found: " + notebookPath;
        }


        // read-before-write guard is also skipped there.
        String readCheckError = isUnc ? null : validateReadBeforeWrite(context, path, absolutePath);
        if (readCheckError != null) {
            return readCheckError;
        }

        String content;
        try {
            content = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "Error: failed to read notebook: " + e.getMessage();
        }

        ObjectNode notebook;
        try {
            notebook = (ObjectNode) mapper().readTree(content);
        } catch (IOException _) {
            return "Error: notebook is not valid JSON.";
        }
        if (!notebook.has("cells") || !notebook.get("cells").isArray()) {
            return "Error: invalid notebook format - missing 'cells' array";
        }
        ArrayNode cells = (ArrayNode) notebook.get("cells");

        // Resolve the target cell index.
        int cellIndex;
        if (StringUtils.isBlank(cellId)) {
            if (!Strings.CS.equals("insert", editMode)) {
                return "Error: Cell ID must be specified when not inserting a new cell.";
            }
            cellIndex = 0; // insert at the beginning
        } else {
            cellIndex = -1;
            for (int i = 0; i < cells.size(); i++) {
                JsonNode c = cells.get(i);
                if (c.has("id") && cellId.equals(c.get("id").asText(""))) {
                    cellIndex = i;
                    break;
                }
            }
            if (cellIndex == -1) {
                Integer parsed = parseCellId(cellId);
                if (parsed == null) {
                    return "Error: Cell with ID \"" + cellId + "\" not found in notebook.";
                }
                if (parsed < 0 || parsed >= cells.size()) {
                    return "Error: Cell with index " + parsed + " does not exist in notebook.";
                }
                cellIndex = parsed;
            }
        }


        String effectiveMode = editMode;
        if (Strings.CS.equals("replace", effectiveMode) && cellIndex == cells.size()) {
            effectiveMode = "insert";
            if (StringUtils.isBlank(cellType)) cellType = "code";
        } else if (Strings.CS.equals("insert", effectiveMode)) {
            cellIndex += 1;
        }

        int nbformat = notebook.has("nbformat") ? notebook.get("nbformat").asInt(4) : 4;
        int minor = notebook.has("nbformat_minor") ? notebook.get("nbformat_minor").asInt(0) : 0;
        boolean supportsId = nbformat > 4 || (nbformat == 4 && minor >= 5);

        try {
            String outputCellId = cellId;
            String effectiveCellType = cellType;
            if (Strings.CS.equals("delete", effectiveMode)) {
                JsonNode target = cells.get(cellIndex);
                if (StringUtils.isBlank(effectiveCellType)) {
                    effectiveCellType = target.path("cell_type").asText("code");
                }
                cells.remove(cellIndex);
            } else if (Strings.CS.equals("insert", effectiveMode)) {
                ObjectNode newCell = mapper().createObjectNode();
                newCell.put("cell_type", StringUtils.isBlank(cellType) ? "code" : cellType);
                if (supportsId) {
                    String id = randomId();
                    newCell.put("id", id);
                    outputCellId = id;
                }
                newCell.put("source", newSource);
                newCell.putObject("metadata");
                if (!Strings.CS.equals("markdown", cellType)) {
                    newCell.putNull("execution_count");
                    newCell.putArray("outputs");
                }
                cells.insert(cellIndex, newCell);
                effectiveCellType = newCell.path("cell_type").asText("code");
            } else { // replace
                ObjectNode target = (ObjectNode) cells.get(cellIndex);
                if (StringUtils.isBlank(outputCellId) && target.hasNonNull("id")) {
                    outputCellId = target.path("id").asText();
                }
                target.put("source", newSource);
                if (Strings.CS.equals("code", target.path("cell_type").asText(""))) {
                    target.putNull("execution_count");
                    target.putArray("outputs");
                }
                if (!StringUtils.isBlank(cellType) && !cellType.equals(target.path("cell_type").asText(""))) {
                    target.put("cell_type", cellType);
                }
                effectiveCellType = target.path("cell_type").asText("code");
            }


            String updated = toIpynbJson(notebook);

            // /rewind "Restore code" checkpoint — must run before the write so

            // fileHistoryTrackEdit(...) called just before the notebook write.
            if (context.fileHistoryManager() != null) {
                context.fileHistoryManager().trackEdit(
                    absolutePath, context.currentUserMessageId());
            }

            FileUtils.writeString(path, updated, StandardCharsets.UTF_8);

// Register the post-edit content/mtime as "read" — the model now knows this content.

            context.fileStateCache().set(absolutePath, new FileStateCache.FileState(
                updated, FileUtils.modificationTimeMillis(path), null, null, false));

            ObjectNode structured = mapper().createObjectNode();
            structured.put("new_source", newSource);
            structured.put("cell_type", StringUtils.isBlank(effectiveCellType) ? "code" : effectiveCellType);
            structured.put("language", notebook.path("metadata").path("language_info")
                .path("name").asText("python"));
            structured.put("edit_mode", effectiveMode);
            if (StringUtils.isNotBlank(outputCellId)) structured.put("cell_id", outputCellId);
            structured.put("error", "");
            structured.put("notebook_path", absolutePath);
            structured.put("original_file", content);
            structured.put("updated_file", updated);
            capture.structuredOutput = structured;


            if (Strings.CS.equals("delete", effectiveMode)) {
                return "Deleted cell " + cellId;
            } else if (Strings.CS.equals("insert", effectiveMode)) {
                return "Inserted cell " + cellId + " with " + newSource;
            }
            return "Updated cell " + cellId + " with " + newSource;
        } catch (IOException e) {
            return "Error: failed to write notebook: " + e.getMessage();
        }
    }


    private String validateReadBeforeWrite(ToolExecutionContext context, Path path, String absolutePath) {
        FileStateCache.FileState state = context.fileStateCache().get(absolutePath);
        if (state == null || state.isPartialView()) {
            return "Error: File has not been read yet. Read it first before writing to it.";
        }
        try {
            long currentMtime = FileUtils.modificationTimeMillis(path);
            if (currentMtime > state.timestampMs()) {
                if (state.isFullRead()) {
                    try {
                        String currentContent = Files.readString(path, StandardCharsets.UTF_8);
                        if (currentContent.equals(state.content())) {
                            return null;
                        }
                    } catch (IOException _) {
                        // Fall through to the stale-file rejection below.
                    }
                }
                return "Error: File has been modified since read, either by the user or by a linter. "
                    + "Read it again before attempting to write it.";
            }
        } catch (IOException _) {
            // Can't stat the file — don't block the write on an unrelated I/O hiccup.
        }
        return null;
    }

/** matches  parseCellId — accepts the "cell-N" numeric index form. */
    private static Integer parseCellId(String id) {
        Matcher m = CELL_ID_PATTERN.matcher(id);
        if (m.matches()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException _) {
                return null;
            }
        }
        return null;
    }

    private static String extname(Path path) {
        String name = path.getFileName().toString();
        if (Strings.CS.startsWith(name, ".")) {
            return "";
        }
        int lastDot = name.lastIndexOf('.');
        return lastDot > 0 ? name.substring(lastDot) : "";
    }


    private static String randomId() {
        StringBuilder s = new StringBuilder(Long.toString(Math.abs(RANDOM.nextLong()), 36));
        if (s.length() > 13) s = new StringBuilder(s.substring(0, 13));
        while (s.length() < 13) s.append("0");
        return s.toString();
    }


    private static String toIpynbJson(ObjectNode notebook) throws IOException {
        DefaultPrettyPrinter pp = new DefaultPrettyPrinter();
        pp.indentObjectsWith(new DefaultIndenter(" ", "\n"));
        pp.indentArraysWith(new DefaultIndenter(" ", "\n"));
        return mapper().writer(pp).writeValueAsString(notebook);
    }

    private static String text(JsonNode node, String key) {
        return node.has(key) && !node.get(key).isNull() ? node.get(key).asText("") : "";
    }

    private static JsonNode buildSchema() {
        ObjectNode schema = mapper().createObjectNode();
        schema.put("type", "object");

        schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");

        ObjectNode p;

        p = properties.putObject("notebook_path");
        p.put("type", "string");
        p.put("description",
                "The absolute path to the Jupyter notebook file to edit (must be absolute, not relative)");

        p = properties.putObject("cell_id");
        p.put("type", "string");
        p.put("description",
                "The ID of the cell to edit. When inserting a new cell, the new cell will be "
                        + "inserted after the cell with this ID, or at the beginning if not specified.");

        p = properties.putObject("new_source");
        p.put("type", "string");
        p.put("description", "The new source for the cell");


        p = properties.putObject("cell_type");
        p.put("type", "string");
        p.putArray("enum").add("code").add("markdown");
        p.put("description",
                "The type of the cell (code or markdown). If not specified, it defaults to the "
                        + "current cell type. If using edit_mode=insert, this is required.");


        p = properties.putObject("edit_mode");
        p.put("type", "string");
        p.putArray("enum").add("replace").add("insert").add("delete");
        p.put("description",
                "The type of edit to make (replace, insert, delete). Defaults to replace.");


        // and edit_mode are optional.
        schema.putArray("required").add("notebook_path").add("new_source");
        return schema;
    }

}

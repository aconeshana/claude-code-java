package com.claudecode.tools.files;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.diff.DiffHunks;
import com.claudecode.core.diff.FileChangeResult;
import com.claudecode.core.diff.StructuredPatchHunk;
import com.claudecode.core.engine.FileStateCache;
import com.claudecode.core.engine.SessionCostState;
import com.claudecode.core.engine.StructuredToolOutput;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.io.FileUtils;
import com.claudecode.core.io.PathUtils;
import com.claudecode.tools.skills.DynamicSkillDiscovery;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import com.claudecode.tools.tasks.TeamMemSecretGuard;
import com.claudecode.tools.AnnotatedTool;
import com.claudecode.tools.BuiltInTool;
import com.claudecode.tools.ToolTexts;

/**
 * Tool for writing content to a file.
 */
@BuiltInTool(
    name = "Write",
    strict = true
)
public class FileWriteTool extends AnnotatedTool<JsonNode, Object> {


    @Override
    public String searchHint() {
        return "create or overwrite files";
    }


    private static final JsonNode SCHEMA = buildSchema();
    private static final String CURRENT_FILE_STATE_SUFFIX =
        " (file state is current in your context — no need to Read it back)";



    // Task 57.6: Common encoding detection (simple BOM check)
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final byte[] UTF16_LE_BOM = {(byte) 0xFF, (byte) 0xFE};
    private static final byte[] UTF16_BE_BOM = {(byte) 0xFE, (byte) 0xFF};

    // Task 57.6: UNC path prefix (Windows network paths)
    private static final String UNC_PREFIX = "\\\\";
    private final DynamicSkillDiscovery dynamicSkillDiscovery;

    public FileWriteTool() {
        this(null);
    }

    public FileWriteTool(DynamicSkillDiscovery dynamicSkillDiscovery) {
        this.dynamicSkillDiscovery = dynamicSkillDiscovery;
    }

    @Override
    public String description() {

        return ToolTexts.description("Write");
    }



    @Override
    public JsonNode inputSchema() {
        return SCHEMA;
    }


    @Override
    public Object toAutoClassifierInput(JsonNode input) {
        if (input == null) return "";
        return input.path("file_path").asText("") + ": " + input.path("content").asText("");
    }

    @Override
    public Object call(JsonNode input, ToolExecutionContext context) {
        String filePath = input.has("file_path") ? input.get("file_path").asText("") : "";
        String content = input.has("content") ? input.get("content").asText("") : "";

        // output is expected to be LF; normalize any stray CRLF so the on-disk
        // file is always LF (idempotent for already-LF content).
        content = content.replace("\r\n", "\n");

        if (StringUtils.isBlank(filePath)) {
            return "Error: file_path is required";
        }

        Path path = PathUtils.expandPath(filePath, context.workingDirectory());
        String absolutePath = path.toAbsolutePath().normalize().toString();

        if (dynamicSkillDiscovery != null) {
            dynamicSkillDiscovery.discover(path, context.workingDirectory());
        }


// teamMemoryEnabled settings key).

        // to the team-memory directory contains a high-confidence secret, since
        // team memory is synced to all repository collaborators. Must run BEFORE

        // UNC short-circuit at :182).
        if (context.teamMemoryEnabled()) {
            String teamMemError = TeamMemSecretGuard.checkTeamMemSecrets(
                absolutePath, content, context.workingDirectory());
            if (teamMemError != null) {
                return teamMemError;
            }
        }



        // decision to the permission layer (returns {result:true}) to avoid an
// SMB auth round-trip on Windows. match that: skip the read-before-write
        // gate and proceed.
        boolean isUnc = Strings.CS.startsWith(absolutePath, UNC_PREFIX) || Strings.CS.startsWith(absolutePath, "//");

        boolean existed = Files.exists(path);

        // Task 57.5: Read-before-write validation. Skipped for new-file


        // this only logged to stderr and let the write proceed regardless —

        // (result: false, errorCode 2/3). UNC paths skip this (deferred to the
        // permission layer, as above).
        if (!isUnc && existed) {
            String readValidationError = validateReadBeforeWrite(context, path, absolutePath);
            if (readValidationError != null) {
                return readValidationError;
            }
        }

        try {
            String oldContent = "";
            if (existed) {
                oldContent = Files.readString(path, StandardCharsets.UTF_8);
            }

            // Create parent directories
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            // Task 57.6: Encoding detection and preservation
            Charset writeCharset = detectAndPreserveEncoding(path);

// /rewind "Restore code" checkpoint — must run before the write so the backup captures
// pre-edit content (or records "file didn't exist" for brand-new files).
            if (context.fileHistoryManager() != null) {
                context.fileHistoryManager().trackEdit(
                    absolutePath, context.currentUserMessageId());
            }

            Files.writeString(path, content, writeCharset,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);



            context.fileStateCache().set(absolutePath, new FileStateCache.FileState(
                content, FileUtils.modificationTimeMillis(path), null, null, false));


            // payload for UserMessage.toolUseResult (/diff per-turn data).
            StringBuilder result = new StringBuilder();
            FileChangeResult change;
            long[] changed;
            if (!existed) {
                result.append("File created successfully at: ").append(filePath);
                change = FileChangeResult.created(absolutePath, content);

                changed = DiffHunks.countLinesChanged(List.of(), content);
            } else {
                result.append("The file ").append(filePath).append(" has been updated successfully.");
                List<StructuredPatchHunk> hunks = DiffHunks.compute(oldContent, content);
                change = FileChangeResult.updated(absolutePath, content, oldContent, hunks);
                changed = DiffHunks.countLinesChanged(hunks, null);
            }
            result.append(CURRENT_FILE_STATE_SUFFIX);
            SessionCostState.get().recordLinesChanged(changed[0], changed[1]);
            return new StructuredToolOutput(result.toString(), change);
        } catch (IOException e) {
            return "Error: failed to write file: " + e.getMessage();
        }
    }


    private String validateReadBeforeWrite(ToolExecutionContext context, Path path, String absolutePath) {
        var state = context.fileStateCache().get(absolutePath);
        if (state == null || state.isPartialView()) {
            return "Error: File has not been read yet. Read it first before writing to it.";
        }
        try {
            long currentMtime = FileUtils.modificationTimeMillis(path);
            if (currentMtime > state.timestampMs()) {
                return "Error: File has been modified since read, either by the user or by a linter. "
                    + "Read it again before attempting to write it.";
            }
        } catch (IOException _) {
            // Can't stat the file — don't block the write on an unrelated I/O hiccup.
        }
        return null;
    }

    /**
     * Task 57.6: Detect and preserve file encoding.
     */
    private Charset detectAndPreserveEncoding(Path path) throws IOException {
        if (!Files.exists(path)) {
            return StandardCharsets.UTF_8;
        }

        byte[] header = Files.readAllBytes(path);
        if (header.length < 2) return StandardCharsets.UTF_8;

        // Check BOM
        if (header.length >= 3 && header[0] == UTF8_BOM[0] && header[1] == UTF8_BOM[1] && header[2] == UTF8_BOM[2]) {
            return StandardCharsets.UTF_8;
        }
        if (header[0] == UTF16_LE_BOM[0] && header[1] == UTF16_LE_BOM[1]) {
            return StandardCharsets.UTF_16LE;
        }
        if (header[0] == UTF16_BE_BOM[0] && header[1] == UTF16_BE_BOM[1]) {
            return StandardCharsets.UTF_16BE;
        }

        return StandardCharsets.UTF_8;
    }

    private static JsonNode buildSchema() {
        ObjectNode schema = mapper().createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");

        ObjectNode filePathProp = properties.putObject("file_path");
        filePathProp.put("type", "string");
        filePathProp.put("description", "The absolute path to the file to write (must be absolute, not relative)");

        ObjectNode contentProp = properties.putObject("content");
        contentProp.put("type", "string");
        contentProp.put("description", "The content to write to the file");

        ArrayNode required = schema.putArray("required");
        required.add("file_path");
        required.add("content");


        schema.put("additionalProperties", false);

        return schema;
    }
}

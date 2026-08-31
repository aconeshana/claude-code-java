package com.claudecode.tools.files;

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.diff.DiffHunks;
import com.claudecode.core.diff.FileChangeResult;
import com.claudecode.core.diff.StructuredPatchHunk;
import com.claudecode.core.engine.FileStateCache;
import com.claudecode.core.engine.SessionCostState;
import com.claudecode.core.engine.StructuredToolOutput;
import com.claudecode.core.engine.ToolExecutionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.claudecode.core.io.FileUtils;
import com.claudecode.core.io.FileTextUtils;
import com.claudecode.core.io.FileReadCache;
import com.claudecode.core.config.EnvUtils;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.core.io.PathUtils;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.tools.skills.DynamicSkillDiscovery;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import com.claudecode.tools.tasks.TeamMemSecretGuard;
import com.claudecode.tools.AnnotatedTool;
import com.claudecode.tools.BuiltInTool;
import com.claudecode.tools.ToolTexts;


@BuiltInTool(
    name = "Edit",
    strict = true
)
public class FileEditTool extends AnnotatedTool<JsonNode, Object> {


    @Override
    public String searchHint() {
        return "modify file contents in place";
    }


    private static final JsonNode SCHEMA = buildSchema();
    private static final FileReadCache FILE_READ_CACHE = new FileReadCache();

    // Task 57.7h: 1GiB file size limit
    private static final long MAX_FILE_SIZE = 1024L * 1024 * 1024;

    // Task 57.7f: Notebook extensions to guard
    private static final Set<String> NOTEBOOK_EXTENSIONS = Set.of(".ipynb");


//  Claude can't emit curly quotes, so we map
    // the file's curly quotes to straight quotes for matching and re-apply the
    // file's quote style on write-back.
    private static final String LEFT_SINGLE_CURLY = "‘"; // \u2018
    private static final String RIGHT_SINGLE_CURLY = "’"; // \u2019
    private static final String LEFT_DOUBLE_CURLY = "“"; // \u201C
    private static final String RIGHT_DOUBLE_CURLY = "”"; // \u201D

// Task 57.7c: Team-memory secret guard only.


    // the team-memory directory is gated, and that lives in TeamMemSecretGuard).

    // Task 57.7d: File history tracking
    private static final Map<String, FileHistoryEntry> FILE_HISTORY = new ConcurrentHashMap<>();

    // Task 57.7e: Encoding BOMs
    private final DynamicSkillDiscovery dynamicSkillDiscovery;

    public FileEditTool() {
        this(null);
    }

    public FileEditTool(DynamicSkillDiscovery dynamicSkillDiscovery) {
        this.dynamicSkillDiscovery = dynamicSkillDiscovery;
    }

    @Override
    public String description() {

        return ToolTexts.description("Edit");
    }



    @Override
    public JsonNode inputSchema() {
        return SCHEMA;
    }


    @Override
    public Object toAutoClassifierInput(JsonNode input) {
        if (input == null) return "";
        return input.path("file_path").asText("") + ": " + input.path("new_string").asText("");
    }

    @Override
    public Object call(JsonNode input, ToolExecutionContext context) {
        String filePath = input.has("file_path") ? input.get("file_path").asText("") : "";

        String oldStr = readEither(input, "old_string", "old_str");
        String newStr = readEither(input, "new_string", "new_str");
        boolean replaceAll = input.has("replace_all") && input.get("replace_all").asBoolean(false);

        if (StringUtils.isBlank(filePath)) {
            return "Error: file_path is required";
        }


        if (oldStr.equals(newStr)) {
            return "No changes to make: old_string and new_string are exactly the same.";
        }

        Path path = PathUtils.expandPath(filePath, context.workingDirectory());
        String absolutePath = path.toAbsolutePath().normalize().toString();

        if (dynamicSkillDiscovery != null
                && !EnvUtils.isEnvTruthy(SubprocessEnvironment.get("CLAUDE_CODE_SIMPLE"))) {
            dynamicSkillDiscovery.discover(path, context.workingDirectory());
        }


// teamMemoryEnabled settings key).




        if (context.teamMemoryEnabled()) {
            String teamMemError = TeamMemSecretGuard.checkTeamMemSecrets(
                absolutePath, newStr, context.workingDirectory());
            if (teamMemError != null) {
                return teamMemError;
            }
        }



        // filesystem decision to the permission layer (returns {result:true})
// to avoid triggering an SMB auth round-trip on Windows. match that:
        // skip the existence/size/notebook/read-before-write gates and proceed.
        boolean isUnc = Strings.CS.startsWith(absolutePath, "\\\\") || Strings.CS.startsWith(absolutePath, "//");

        boolean existed = Files.exists(path);
        if (!isUnc) {


            if (!existed && !oldStr.isEmpty()) {
                return "Error: " + FileUtils.fileNotFoundMessage(
                    path, Path.of(context.workingDirectory()));
            }

// Task 57.7b: Read-before-write validation — skipped when old_string is empty (new-file
// creation needs no prior read).

            if (!oldStr.isEmpty()) {
                String readCheckError = validateReadBeforeWrite(context, path, absolutePath);
                if (readCheckError != null) {
                    return readCheckError;
                }
            }

            // Task 57.7h: File size limit (1GiB) — only meaningful for existing files.
            if (existed) {
                try {
                    long size = Files.size(path);
                    if (size > MAX_FILE_SIZE) {
                        return String.format(
                            "Error: File is too large to edit (%s). Maximum editable file size is %s.",
                            FormatUtils.formatFileSize(size), FormatUtils.formatFileSize(MAX_FILE_SIZE));
                    }
                } catch (IOException e) {
                    return "Error: failed to check file size: " + e.getMessage();
                }

                // Task 57.7f: Notebook guard — redirect .ipynb edits to NotebookEditTool
                if (isNotebookFile(path)) {
                    return "Error: editing Jupyter notebooks directly is not supported. Use the NotebookEdit tool instead.";
                }
            }
        }

        try {
            // Task 57.7e: Detect and preserve encoding (and line-ending style).
            FileTextUtils.TextFile metadata = existed
                ? FILE_READ_CACHE.read(path)
                : new FileTextUtils.TextFile("", StandardCharsets.UTF_8, FileTextUtils.LineEnding.LF);
            Charset charset = metadata.charset();


            // instead of silently converting a CRLF file to LF.
            String content = metadata.content();

            EditPreview preview = previewEdit(content, oldStr, newStr, replaceAll);
            if (!preview.error().isEmpty()) return preview.error();
            String newContent = preview.newContent();
            String actualOldStr = preview.actualOldString();

// /rewind "Restore code" checkpoint — must run before the write so the backup captures
// pre-edit content.

            if (context.fileHistoryManager() != null) {
                context.fileHistoryManager().trackEdit(
                    absolutePath, context.currentUserMessageId());
            }



            // before the write. Only needed for creation (no parent yet).
            if (!existed && oldStr.isEmpty()) {
                Path parent = path.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
            }


            String toWrite = FileTextUtils.restoreLineEndings(newContent, metadata.lineEnding());

            FileUtils.writeString(path, toWrite, charset,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            FILE_READ_CACHE.invalidate(path);

            // Task 57.7d: Track file history
            trackFileHistory(absolutePath, content, toWrite);

            // Task 57.7b: Register the post-edit content/mtime as "read" — store
            // the on-disk (CRLF-restored) content so a later staleness compare
            // matches (FileReadTool reads raw, un-normalized bytes).
            context.fileStateCache().set(absolutePath, new FileStateCache.FileState(
                toWrite, FileUtils.modificationTimeMillis(path), null, null, false));



            long oldLines = content.lines().count();
            long newLines = newContent.lines().count();
            long diffLines = newLines - oldLines;
            String resultText = replaceAll
                ? "The file " + filePath + " has been updated. All occurrences were successfully replaced."
                : "The file " + filePath + " has been updated successfully.";
            List<StructuredPatchHunk> hunks = preview.hunks();

            long[] changed = DiffHunks.countLinesChanged(hunks, null);
            SessionCostState.get().recordLinesChanged(changed[0], changed[1]);
            return new StructuredToolOutput(resultText,
                FileChangeResult.edited(absolutePath, actualOldStr, newStr, content,
                    hunks, false, replaceAll));
        } catch (IOException e) {
            return "Error: failed to edit file: " + e.getMessage();
        }
    }

    /**
     * Side-effect-free FileEdit transformation used by the permission preview.
     * Keeping this here prevents UI diffs from diverging from the actual write path.
     */
    public static EditPreview previewEdit(
            String content, String oldStr, String newStr, boolean replaceAll) {
        String source = content == null ? "" : content;
        String oldValue = oldStr == null ? "" : oldStr;
        String newValue = newStr == null ? "" : newStr;
        if (oldValue.equals(newValue)) {
            return EditPreview.error(source,
                "No changes to make: old_string and new_string are exactly the same.");
        }
        if (oldValue.isEmpty()) {
            if (!source.strip().isEmpty()) {
                return EditPreview.error(source, "Cannot create new file - file already exists.");
            }
            return EditPreview.success(oldValue, newValue, newValue,
                DiffHunks.compute(source, newValue));
        }
        String actualOld = findActualString(source, oldValue);
        if (actualOld == null) {
            return EditPreview.error(source,
                "Error: String to replace not found in file.\nString: " + oldValue);
        }
        int count = countOccurrences(source, actualOld);
        if (count > 1 && !replaceAll) {
            return EditPreview.error(source,
                "Error: Found " + count + " matches of the string to replace, but replace_all is false. "
                    + "To replace all occurrences, set replace_all to true. "
                    + "To replace only one occurrence, please provide more context to uniquely identify the instance.\n"
                    + "String: " + oldValue);
        }
        String normalizedNew = preserveQuoteStyle(oldValue, actualOld, newValue);
        String changed = replaceAll
            ? source.replace(actualOld, normalizedNew)
            : replaceFirst(source, actualOld, normalizedNew);
        return EditPreview.success(actualOld, normalizedNew, changed,
            DiffHunks.compute(source, changed));
    }

    /** Result of the pure edit preview; {@code error} is empty on success. */
    public record EditPreview(
            String actualOldString,
            String normalizedNewString,
            String newContent,
            List<StructuredPatchHunk> hunks,
            String error) {
        public EditPreview {
            hunks = hunks == null ? List.of() : List.copyOf(hunks);
            error = error == null ? "" : error;
        }

        static EditPreview success(String actualOld, String normalizedNew,
                                   String content, List<StructuredPatchHunk> hunks) {
            return new EditPreview(actualOld, normalizedNew, content, hunks, "");
        }

        static EditPreview error(String content, String error) {
            return new EditPreview("", "", content, List.of(), error);
        }
    }


    private static String findActualString(String content, String searchStr) {
        // First try exact match
        if (Strings.CS.contains(content, searchStr)) {
            return searchStr;
        }

        // Try with normalized (curly→straight) quotes on both sides
        String normalizedSearch = normalizeQuotes(searchStr);
        String normalizedFile = normalizeQuotes(content);
        int searchIndex = normalizedFile.indexOf(normalizedSearch);
        if (searchIndex != -1) {
            // Return the actual (possibly curly) substring from the original file.
            return content.substring(searchIndex, searchIndex + searchStr.length());
        }

        return null;
    }


    private static String normalizeQuotes(String str) {
        return str
            .replace(LEFT_SINGLE_CURLY, "'")
            .replace(RIGHT_SINGLE_CURLY, "'")
            .replace(LEFT_DOUBLE_CURLY, "\"")
            .replace(RIGHT_DOUBLE_CURLY, "\"");
    }

    /**
     * Task 57.7a: Preserve the file's curly-quote style in {@code newStr} when the original match used
     * curly quotes.
     */
    private static String preserveQuoteStyle(String oldStr, String actualOldStr, String newStr) {
        // If they're the same, no normalization happened
        if (oldStr.equals(actualOldStr)) {
            return newStr;
        }

        boolean hasDoubleQuotes = Strings.CS.contains(actualOldStr, LEFT_DOUBLE_CURLY)
            || Strings.CS.contains(actualOldStr, RIGHT_DOUBLE_CURLY);
        boolean hasSingleQuotes = Strings.CS.contains(actualOldStr, LEFT_SINGLE_CURLY)
            || Strings.CS.contains(actualOldStr, RIGHT_SINGLE_CURLY);

        if (!hasDoubleQuotes && !hasSingleQuotes) {
            return newStr;
        }

        String result = newStr;
        if (hasDoubleQuotes) {
            result = applyCurlyDoubleQuotes(result);
        }
        if (hasSingleQuotes) {
            result = applyCurlySingleQuotes(result);
        }
        return result;
    }

    /** True when {@code ch} is a Unicode letter (used for contraction detection). */
    private static boolean isLetter(char ch) {
        return Character.toString(ch).matches("\\p{L}");
    }


    private static boolean isOpeningContext(char[] chars, int index) {
        if (index == 0) {
            return true;
        }
        char prev = chars[index - 1];
        return prev == ' ' || prev == '\t' || prev == '\n' || prev == '\r'
            || prev == '(' || prev == '[' || prev == '{'
            || prev == '—' || prev == '–'; // em dash / en dash
    }

    private static String applyCurlyDoubleQuotes(String str) {
        char[] chars = str.toCharArray();
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] == '"') {
                result.append(isOpeningContext(chars, i) ? LEFT_DOUBLE_CURLY : RIGHT_DOUBLE_CURLY);
            } else {
                result.append(chars[i]);
            }
        }
        return result.toString();
    }

    private static String applyCurlySingleQuotes(String str) {
        char[] chars = str.toCharArray();
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] == '\'') {
                // Apostrophe between two letters is a contraction, not a quote.
                boolean prevIsLetter = i > 0 && isLetter(chars[i - 1]);
                boolean nextIsLetter = i < chars.length - 1 && isLetter(chars[i + 1]);
                if (prevIsLetter && nextIsLetter) {
                    result.append(RIGHT_SINGLE_CURLY);
                } else {
                    result.append(isOpeningContext(chars, i) ? LEFT_SINGLE_CURLY : RIGHT_SINGLE_CURLY);
                }
            } else {
                result.append(chars[i]);
            }
        }
        return result.toString();
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
                        String currentContent = FILE_READ_CACHE.read(path).content();
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

    /**
     * Task 57.7d: Track file edit history.
     */
    private void trackFileHistory(String absolutePath, String oldContent, String newContent) {
        long oldLines = oldContent.lines().count();
        long newLines = newContent.lines().count();
        FILE_HISTORY.put(absolutePath, new FileHistoryEntry(
            absolutePath, Instant.now(), oldLines, newLines, newContent.length()));
    }

    /**
     * Task 57.7f: Check if file is a Jupyter notebook.
     */
    private boolean isNotebookFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String ext : NOTEBOOK_EXTENSIONS) {
            if (Strings.CS.endsWith(name, ext)) return true;
        }
        return false;
    }

    /**
     * Counts non-overlapping occurrences of a substring.
     */
    static int countOccurrences(String text, String sub) {
        if (text == null || sub == null || sub.isEmpty()) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    /**
     * Task 57.7d: Get file history entry.
     */
    public static FileHistoryEntry getFileHistory(String absolutePath) {
        return FILE_HISTORY.get(absolutePath);
    }

    /**
     * Clear file history (for testing).
     */
    public static void clearFileHistory() {
        FILE_HISTORY.clear();
    }

    /**
     * Task 57.7d: File history entry record.
     */
    public record FileHistoryEntry(
        String path,
        Instant editTime,
        long oldLineCount,
        long newLineCount,
        long byteSize
    ) {}

    private static JsonNode buildSchema() {
        ObjectNode schema = mapper().createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");

        ObjectNode filePathProp = properties.putObject("file_path");
        filePathProp.put("type", "string");
        filePathProp.put("description", "The absolute path to the file to modify");

        // the compatibility contract uses old_string / new_string; published as canonical names.
        ObjectNode oldStrProp = properties.putObject("old_string");
        oldStrProp.put("type", "string");
        oldStrProp.put("description", "The text to replace");

        ObjectNode newStrProp = properties.putObject("new_string");
        newStrProp.put("type", "string");
        newStrProp.put("description", "The text to replace it with (must be different from old_string)");

        ObjectNode replaceAllProp = properties.putObject("replace_all");
        replaceAllProp.put("type", "boolean");
        replaceAllProp.put("default", false);
        replaceAllProp.put("description",
            "Replace all occurrences of old_string (default false)");

        ArrayNode required = schema.putArray("required");
        required.add("file_path");
        required.add("old_string");
        required.add("new_string");

        // the compatibility contract uses z.strictObject — reject unknown properties.
        schema.put("additionalProperties", false);

        return schema;
    }

    /** Returns the first non-empty value among {@code primary} and {@code fallback}. */
    private static String readEither(JsonNode input, String primary, String fallback) {
        if (input.has(primary) && !input.get(primary).asText("").isEmpty()) {
            return input.get(primary).asText();
        }
        if (input.has(fallback)) {
            return input.get(fallback).asText("");
        }
        return "";
    }

    /** Replace only the first occurrence of {@code needle} in {@code haystack}. */
    private static String replaceFirst(String haystack, String needle, String replacement) {
        int idx = haystack.indexOf(needle);
        if (idx < 0) return haystack;
        return haystack.substring(0, idx) + replacement + haystack.substring(idx + needle.length());
    }
}

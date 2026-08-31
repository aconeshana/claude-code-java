package com.claudecode.tools.files;

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.engine.FileStateCache;
import com.claudecode.core.engine.StructuredToolOutput;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.session.SessionManager;
import com.claudecode.tools.files.PdfUtils.PageRange;
import com.claudecode.core.memdir.AutoMemoryPrompt;
import com.claudecode.core.memdir.MemoryAge;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.DocumentBlock;
import com.claudecode.core.message.ImageBlock;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.core.io.FileUtils;
import com.claudecode.core.config.EnvUtils;
import com.claudecode.core.imagestore.ImageResizer;
import com.claudecode.core.io.PathUtils;
import com.claudecode.core.io.FileTextUtils;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.tools.skills.DynamicSkillDiscovery;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import com.claudecode.tools.AnnotatedTool;
import com.claudecode.tools.BuiltInTool;
import com.claudecode.tools.ToolTexts;
import com.claudecode.tools.ToolUseRenderContext;
import com.claudecode.tools.ToolUseTag;
import com.claudecode.tools.tasks.TaskOutputPaths;
import com.claudecode.tools.ValidationResult;

/**
 * Tool for reading file contents.
 */
@BuiltInTool(
    name = "Read",
    strict = true,
    readOnly = true,
    concurrencySafe = true,
    maxResultSizeChars = Integer.MAX_VALUE
)
public class FileReadTool extends AnnotatedTool<JsonNode, Object> {

    private volatile Supplier<String> modelSupplier = () -> null;

    /** Injects the live request model used by the model-visible PDF capability text. */
    public void setModelSupplier(Supplier<String> modelSupplier) {
        this.modelSupplier = modelSupplier != null ? modelSupplier : () -> null;
    }


    @Override
    public String searchHint() {
        return "read files, images, PDFs, notebooks";
    }

    @Override
    public Optional<ToolUseTag> renderToolUseTag(
            JsonNode input, ToolUseRenderContext context) {
        if (input == null) return Optional.empty();
        String filePath = input.path("file_path").asText("");
        if (StringUtils.isBlank(filePath)) return Optional.empty();
        String taskId = TaskOutputPaths.agentOutputTaskId(filePath);
        return taskId == null ? Optional.empty() : Optional.of(ToolUseTag.dim(taskId));
    }



    /**
     * Maximum output tokens for a text/notebook read.
     */
    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 25000;

    /**
     * Maximum file size in bytes (256 KB = 0.25 MB).
     */
    public static final long MAX_FILE_SIZE = 256 * 1024;

    /** Effective max-output-token cap for this tool instance. */
    private final int maxOutputTokens;
    private final DynamicSkillDiscovery dynamicSkillDiscovery;
    private final BiFunction<String, String, Path> toolResultsDirResolver;
    private final PdfUtils.CommandRunner pdfCommandRunner;

    public FileReadTool() {
        this(getDefaultMaxOutputTokens(), null, FileReadTool::defaultToolResultsDir,
            PdfUtils.defaultCommandRunner());
    }

    public FileReadTool(DynamicSkillDiscovery dynamicSkillDiscovery) {
        this(getDefaultMaxOutputTokens(), dynamicSkillDiscovery,
            FileReadTool::defaultToolResultsDir, PdfUtils.defaultCommandRunner());
    }

    /**
     * Package-private constructor for tests: pins the token cap so the gate
     * can be exercised deterministically without huge fixtures or env mutation.
     */
    FileReadTool(int maxOutputTokens) {
        this(maxOutputTokens, null, FileReadTool::defaultToolResultsDir,
            PdfUtils.defaultCommandRunner());
    }

    FileReadTool(int maxOutputTokens, DynamicSkillDiscovery dynamicSkillDiscovery) {
        this(maxOutputTokens, dynamicSkillDiscovery, FileReadTool::defaultToolResultsDir,
            PdfUtils.defaultCommandRunner());
    }

    FileReadTool(int maxOutputTokens, DynamicSkillDiscovery dynamicSkillDiscovery,
                 BiFunction<String, String, Path> toolResultsDirResolver) {
        this(maxOutputTokens, dynamicSkillDiscovery, toolResultsDirResolver,
            PdfUtils.defaultCommandRunner());
    }

    FileReadTool(int maxOutputTokens, DynamicSkillDiscovery dynamicSkillDiscovery,
                 BiFunction<String, String, Path> toolResultsDirResolver,
                 PdfUtils.CommandRunner pdfCommandRunner) {
        this.maxOutputTokens = maxOutputTokens;
        this.dynamicSkillDiscovery = dynamicSkillDiscovery;
        this.toolResultsDirResolver = toolResultsDirResolver;
        this.pdfCommandRunner = pdfCommandRunner;
    }

    private static Path defaultToolResultsDir(String cwd, String sessionId) {
        return new SessionManager(cwd).getToolResultsDir(sessionId);
    }

    /**
     * Effective max-output-token cap.
     */
    private static int getDefaultMaxOutputTokens() {
        String env = SubprocessEnvironment.get("CLAUDE_CODE_FILE_READ_MAX_OUTPUT_TOKENS");
        if (StringUtils.isNotBlank(env)) {
            try {
                int v = Integer.parseInt(env.trim());
                if (v > 0) {
                    return v;
                }
            } catch (NumberFormatException _) {
                // fall through to default
            }
        }
        return DEFAULT_MAX_OUTPUT_TOKENS;
    }

    private static final JsonNode SCHEMA = buildSchema();


    // BLOCKED_DEVICE_PATHS + isBlockedDevicePath (lines 98-128). Safe devices

    // Java's !isRegularFile gate already handles it — do NOT over-block it).
    private static final Set<String> BLOCKED_DEVICE_FILES = Set.of(
        "/dev/zero", "/dev/random", "/dev/urandom", "/dev/full",
        "/dev/stdin", "/dev/tty", "/dev/console",
        "/dev/stdout", "/dev/stderr",
        "/dev/fd/0", "/dev/fd/1", "/dev/fd/2"
    );

    // Image magic bytes
    private static final byte[] PNG_HEADER = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', '\032', '\n'};
    private static final byte[] JPG_HEADER = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] GIF_HEADER_87A = {'G', 'I', 'F', '8', '7', 'a'};
    private static final byte[] GIF_HEADER_89A = {'G', 'I', 'F', '8', '9', 'a'};
    private static final byte[] WEBP_HEADER = {'R', 'I', 'F', 'F'};
    // Supported image extensions
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".svg");
    private static final Set<String> NOTEBOOK_EXTENSIONS = Set.of(".ipynb");

// Binary file extensions that cannot be meaningfully read as text. implemented


    // explicit `!isPDFExtension && !IMAGE_EXTENSIONS` exclusion is redundant
    // here given that dispatch order).
    private static final Set<String> BINARY_EXTENSIONS = Set.of(
        ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".ico", ".webp", ".tiff", ".tif",
        ".mp4", ".mov", ".avi", ".mkv", ".webm", ".wmv", ".flv", ".m4v", ".mpeg", ".mpg",
        ".mp3", ".wav", ".ogg", ".flac", ".aac", ".m4a", ".wma", ".aiff", ".opus",
        ".zip", ".tar", ".gz", ".bz2", ".7z", ".rar", ".xz", ".z", ".tgz", ".iso",
        ".exe", ".dll", ".so", ".dylib", ".bin", ".o", ".a", ".obj", ".lib", ".app",
        ".msi", ".deb", ".rpm",
        ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".odt", ".ods", ".odp",
        ".ttf", ".otf", ".woff", ".woff2", ".eot",
        ".pyc", ".pyo", ".class", ".jar", ".war", ".ear", ".node", ".wasm", ".rlib",
        ".sqlite", ".sqlite3", ".db", ".mdb", ".idx",
        ".psd", ".ai", ".eps", ".sketch", ".fig", ".xd", ".blend", ".3ds", ".max",
        ".swf", ".fla",
        ".lockb", ".dat", ".data"
    );

    @Override
    public String description() {

        String description = ToolTexts.description("Read");
        String model = modelSupplier.get();
        if (model != null && Strings.CI.contains(model, "claude-3-haiku")) {
            description = description.replace(
                """

                - This tool can read PDF files (.pdf). For large PDFs (more than 10 pages), \
                you MUST provide the pages parameter to read specific page ranges (e.g., \
                pages: "1-5"). Reading a large PDF without the pages parameter will fail. \
                Maximum 20 pages per request.""", "");
        }
        return description;
    }

    

    @Override
    public JsonNode inputSchema() {
        return SCHEMA;
    }

    /**
     * matches  for the pure PDF page-range
     * checks. Keeping these in the semantic gate is observable: the registry
     * wraps them in {@code <tool_use_error>} while persisting the unwrapped
     * {@code Error: ...} payload.
     */
    @Override
    public ValidationResult validateInput(JsonNode input, ToolExecutionContext context) {
        if (input == null || !input.has("pages")) return ValidationResult.valid();
        String pages = normalizeOptionalPages(input.path("pages").asText(null));
        if (pages == null) return ValidationResult.valid();
        String error = validatePageRange(pages);
        return error == null ? ValidationResult.valid() : ValidationResult.invalid(error);
    }

    /**
     * Treats a blank optional string like an omitted JSON property. Some GPT
     * function-call implementations materialize every optional string as
     * {@code ""}; preserving that value would turn a harmless text-file Read
     * into a PDF page-range validation error.
     */
    @Explanation("Blank OpenAI-compatible Read.pages means omitted")
    private static String normalizeOptionalPages(String pages) {
        return StringUtils.isBlank(pages) ? null : pages;
    }

    private static String validatePageRange(String pages) {
        PageRange range = PdfUtils.parsePageRange(pages);
        if (range == null) {
            return "Invalid pages parameter: \"" + pages
                + "\". Use formats like \"1-5\", \"3\", or \"10-20\". Pages are 1-indexed.";
        }
        long rangeSize = range.last() == Integer.MAX_VALUE
            ? PdfUtils.PDF_MAX_PAGES_PER_READ + 1L
            : (long) range.last() - range.first() + 1L;
        if (rangeSize > PdfUtils.PDF_MAX_PAGES_PER_READ) {
            return "Page range \"" + pages + "\" exceeds maximum of "
                + PdfUtils.PDF_MAX_PAGES_PER_READ
                + " pages per request. Please use a smaller range.";
        }
        return null;
    }



    @Override
    public Object toAutoClassifierInput(JsonNode input) {
        return input == null ? "" : input.path("file_path").asText("");
    }


    @Override
    public SearchReadClassification searchReadClassification(JsonNode input) {
        return new SearchReadClassification(false, true, false);
    }





    static final int DEFAULT_LINE_LIMIT = 2000;

    @Override
    public Object call(JsonNode input, ToolExecutionContext context) {
        String filePath = input.has("file_path") ? input.get("file_path").asText("") : "";

        int offset = input.has("offset") ? input.get("offset").asInt(0) : 0;
        int limit = input.has("limit") ? input.get("limit").asInt(0) : 0;
        String pages = normalizeOptionalPages(
            input.has("pages") ? input.get("pages").asText(null) : null);

        if (StringUtils.isBlank(filePath)) {
            return "Error: file_path is required";
        }

        Path path = PathUtils.expandPath(filePath, context.workingDirectory());

        Path cwd = Path.of(context.workingDirectory());
        if (!Files.exists(path)) {
            return "Error: " + FileUtils.fileNotFoundMessage(path, cwd);
        }



        // of erroring with "not a regular file". BLOCKED_DEVICE_FILES (e.g.
        // /dev/zero) is still rejected above, since /dev/null is intentionally
        // omitted from that blocklist.
        if (!Files.isRegularFile(path) && !isSafeEmptyDevice(path)) {
            return "Error: not a regular file: " + filePath;
        }

        // Task 57.4: Block device files
        String absolutePath = path.toAbsolutePath().normalize().toString();
        if (isBlockedDevicePath(absolutePath)) {
            return "Error: access to device file blocked: " + absolutePath;
        }

        try {
            long size = Files.size(path);

            if (dynamicSkillDiscovery != null
                    && !EnvUtils.isEnvTruthy(SubprocessEnvironment.get("CLAUDE_CODE_SIMPLE"))) {
                dynamicSkillDiscovery.discover(path, context.workingDirectory());
            }

// Attachment auto-inject: register this read path as a nested-memory trigger so the
// next request build can attach the CLAUDE.md files in scope for what was just read.
            var triggers = context.nestedMemoryAttachmentTriggers();
            if (triggers != null) {
                triggers.add(absolutePath);
            }

            // Task 57.1: Image file handling — images have their own size limits
            // (token budget + compression), so they bypass the 256KB text cap.
            if (isImageFile(path)) {
                return readImageFile(path, filePath);
            }

            // Task 57.2: PDF file handling — PDFs use PDF_EXTRACT_SIZE_THRESHOLD /
            // token budget, so they bypass the 256KB text cap.
            if (isPdfFile(path)) {
                return readPdfFile(filePath, size, pages, path, context);
            }


            // reads; images/PDFs bypass it. Placed AFTER the image/PDF branches
            // so those formats proceed to their own (larger) size handling.

            // request pagination (limit === undefined). An explicit limit means
            // the model is deliberately paging through a big file, so the cap is
            // bypassed and the windowed read proceeds.
            if (limit <= 0 && size > MAX_FILE_SIZE) {
                return String.format("Error: file too large (%d bytes, max %d)", size, MAX_FILE_SIZE);
            }

            // Task 57.3: Jupyter notebook handling
            if (isNotebookFile(path)) {
                return readNotebookFile(path, filePath, absolutePath, offset, limit, context);
            }

            // Task 57.4: Read deduplication check — only when the requested
// range (offset/limit) matches the previously-read range. matches

            // offset/limit + mtime), so a re-read of a DIFFERENT range is never
            // collapsed into [file_unchanged].
            int effectiveOffset = offset > 0 ? offset : 1;
            Integer effectiveLimit = limit > 0 ? limit : null;
            long observedMtime = FileUtils.modificationTimeMillis(path);
            if (context.fileStateCache().matchesLatestReadRange(
                    absolutePath, effectiveOffset, effectiveLimit, observedMtime)) {
                ObjectNode data = mapper().createObjectNode();
                data.put("type", "file_unchanged");
                data.putObject("file").put("filePath", filePath);
                return new StructuredToolOutput(
                    "[file_unchanged] The file has not been modified since last read.", data);
            }

            // Task 57.4 (binary ext guard): reject binary files by extension
            // BEFORE the NUL-byte scan. Images/PDF/SVG are excluded (rendered

            if (hasBinaryExtension(path)) {
                return "Error: This tool cannot read binary files. The file appears to be a binary "
                    + extWithDot(path) + " file. Please use appropriate tools for binary file analysis.";
            }

            // Standard text file reading — NUL-byte scan fallback.
            if (isBinaryFile(path)) {
                return "Error: file appears to be binary: " + filePath;
            }

            int lineOffset = offset > 0 ? offset - 1 : 0;
            int lineLimit = limit > 0 ? limit : DEFAULT_LINE_LIMIT;
            FileRangeReader.Result read = FileRangeReader.read(
                path, lineOffset, lineLimit, context.abortController());
            String windowContent = read.content();


            // numbering, no cyber-risk reminder (the falsy-content else-branch).
            // Register the read so a follow-up Write/Edit is still permitted.
            if (read.totalBytes() == 0) {
                context.fileStateCache().set(absolutePath, new FileStateCache.FileState(
                    "", read.mtimeMs(), null, null, false));
                context.fileStateCache().recordReadRange(
                    absolutePath, effectiveOffset, effectiveLimit, read.mtimeMs());
                return textOutput(filePath, "", 1, 0,
                    "<system-reminder>Warning: the file exists but the contents are empty.</system-reminder>");
            }



            // NOT a hard error. Reading from a 1-indexed line beyond the last
            // line yields an empty window. (Empty file already returned above.)
            if (offset > 0) {
                if (offset > read.totalLines()) {
                    context.fileStateCache().set(absolutePath, new FileStateCache.FileState(
                        windowContent, read.mtimeMs(), offset, effectiveLimit, false));
                    context.fileStateCache().recordReadRange(
                        absolutePath, effectiveOffset, effectiveLimit, read.mtimeMs());
                    return textOutput(filePath, "", offset, read.totalLines(), String.format(
                        "<system-reminder>Warning: the file exists but is shorter than the provided offset (%d). The file has %d lines.</system-reminder>",
                        offset, read.totalLines()));
                }
            }



            // actually shown — so a bounded offset/limit read is not rejected
            // for an otherwise-huge file. Exceeding the cap returns a tool error

            String tokenError = checkMaxTokens(windowContent, extOf(filePath));
            if (tokenError != null) {
                return ToolResult.error(tokenError);
            }


            String content = FileTextUtils.addLineNumbers(windowContent, effectiveOffset);

// Task 57.5: Register file as read for FileWrite/FileEdit's read-before-write
// validation.
            String cacheContent = offset <= 0 && limit <= 0 && read.fullContent() != null
                ? read.fullContent() : windowContent;
            context.fileStateCache().set(absolutePath, new FileStateCache.FileState(
                cacheContent, read.mtimeMs(),
                offset > 0 ? offset : null, limit > 0 ? limit : null, false));
            context.fileStateCache().recordReadRange(
                absolutePath, effectiveOffset, effectiveLimit, read.mtimeMs());

            StringBuilder result = new StringBuilder();

            if (AutoMemoryPrompt.isAutoMemPath(path, cwd)) {
                result.append(MemoryAge.freshnessNote(read.mtimeMs()));
            }

            // dynamic skill discovery is a side-effect only.
            result.append(content);
            int startLine = offset > 0 ? offset : 1;
            return textOutput(filePath, windowContent, startLine, read.totalLines(), result.toString());

        } catch (IOException e) {
            return "Error: failed to read file: " + e.getMessage();
        }
    }

    private static StructuredToolOutput textOutput(
            String filePath, String content, int startLine, int totalLines, String rendered) {
        ObjectNode data = mapper().createObjectNode();
        data.put("type", "text");
        ObjectNode file = data.putObject("file");
        file.put("filePath", filePath);
        file.put("content", content);
        file.put("numLines", content.isEmpty() ? 0 : content.split("\r?\n", -1).length);
        file.put("startLine", startLine);
        file.put("totalLines", totalLines);
        return new StructuredToolOutput(rendered, data);
    }

    /**
     * Task 57.1: Read image file and return it as a base64 {@code image} content block in the
     * tool_result.
     */
    private ToolResult readImageFile(Path path, String filePath) throws IOException {
        byte[] data = Files.readAllBytes(path);
        if (data.length == 0) {
            return officialReadError("Image file is empty: " + filePath);
        }
        String format = detectImageFormat(data);
        if (format == null) {
            return officialReadError(invalidImageContentMessage(filePath, data));
        }

        ImageResizer.ResizeResult resized;
        try {

            resized = ImageResizer.maybeResizeForInputBlock(data, format.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return officialReadError(e.getMessage());
        }

        ObjectNode source = mapper().createObjectNode();
        source.put("type", "base64");
        source.put("media_type", resized.mediaType());
        String base64 = Base64.getEncoder().encodeToString(resized.buffer());
        source.put("data", base64);
        ImageBlock imageBlock = new ImageBlock(source);

        ObjectNode output = mapper().createObjectNode();
        output.put("type", "image");
        ObjectNode file = output.putObject("file");
        file.put("base64", base64);
        file.put("type", resized.mediaType());
        file.put("originalSize", data.length);
        if (resized.dimensions() != null) {
            ObjectNode dimensions = file.putObject("dimensions");
            if (resized.dimensions().originalWidth() != null) {
                dimensions.put("originalWidth", resized.dimensions().originalWidth());
            }
            if (resized.dimensions().originalHeight() != null) {
                dimensions.put("originalHeight", resized.dimensions().originalHeight());
            }
            if (resized.dimensions().displayWidth() != null) {
                dimensions.put("displayWidth", resized.dimensions().displayWidth());
            }
            if (resized.dimensions().displayHeight() != null) {
                dimensions.put("displayHeight", resized.dimensions().displayHeight());
            }
        }

        ToolResult result = new ToolResult(List.of(imageBlock), false).withToolUseResult(output);
        String metadataText = ImageResizer.createImageMetadataText(resized.dimensions(), null);
        if (metadataText == null) return result;

        UserMessage metadataMessage = new UserMessage(
            UUID.randomUUID().toString(), MessageContent.ofText(metadataText),
            true, false, null, MessageOrigin.USER, null, Instant.now(), null, null);
        return result.withNewMessages(List.of(metadataMessage));
    }

    private static String invalidImageContentMessage(String filePath, byte[] data) {
        String leadingHex = HexFormat.ofDelimiter(" ").formatHex(data, 0, Math.min(8, data.length));
        return "File has an image extension but its content is not a valid PNG/JPEG/GIF/WebP. "
            + "Detected: unrecognized bytes (hex: " + leadingHex + "). "
            + "This usually means a download saved an error/login page instead of the image. "
            + "Use `file \"" + filePath + "\"` to confirm, or read it as text with Bash "
            + "(e.g. `head -c 500`).";
    }

    private static ToolResult officialReadError(String message) {
        return ToolResult.error(message).withToolUseResult("Error: " + message);
    }

    /**
     * Task 57.2: Produce the Read tool's result for a PDF.
     */
    private ToolResult readPdfFile(String filePath, long size, String pages, Path path,
                                   ToolExecutionContext context) {
        // Direct Tool#call tests bypass ToolRegistry's semantic gate. Keep a
        // defensive copy here; production requests are rejected earlier and
// therefore retain the expected <tool_use_error> wrapper.
        if (StringUtils.isNotBlank(pages)) {
            String validationError = validatePageRange(pages);
            if (validationError != null) return officialReadError(validationError);

            PageRange pr = PdfUtils.parsePageRange(pages);
            Integer pageCount = PdfUtils.getPDFPageCount(path, pdfCommandRunner);
            if (pageCount != null && pr.first() > pageCount) {
                return officialReadError(outsideDocumentMessage(pages, pr, pageCount));
            }
            return readPdfPages(filePath, size, pr, path, context);
        }

        // No pages: refuse oversized page counts first.
        Integer pageCount = PdfUtils.getPDFPageCount(path, pdfCommandRunner);
        if (pageCount != null && pageCount > PdfUtils.PDF_AT_MENTION_INLINE_THRESHOLD) {
            return officialReadError(
                "This PDF has " + pageCount + " pages, which is too many to read at once. "
                    + "Use the pages parameter to read specific page ranges (e.g., pages: \"1-5\"). "
                    + "Maximum " + PdfUtils.PDF_MAX_PAGES_PER_READ + " pages per request.");
        }

        boolean shouldExtractPages =
            !isPDFSupported(context) || size > PdfUtils.PDF_EXTRACT_SIZE_THRESHOLD;
        if (shouldExtractPages) {

            // and telemetry. It deliberately ignores both success and failure.
            PdfUtils.extractPages(path, filePath, null, null,
                toolResultsDir(context), pdfCommandRunner);
        }

        if (!isPDFSupported(context)) {
            return officialReadError(
                "Reading full PDFs is not supported with this model. Use a newer model (Sonnet 3.5 v2 or later), "
                    + "or use the pages parameter to read specific page ranges (e.g., pages: \"1-5\", maximum "
                    + PdfUtils.PDF_MAX_PAGES_PER_READ + " pages per request). "
                    + "Page extraction requires poppler-utils: install with `brew install poppler` on macOS or "
                    + "`apt-get install poppler-utils` on Debian/Ubuntu.");
        }

        PdfUtils.PdfResult<PdfUtils.FullPdf> readResult = PdfUtils.readPdf(path, filePath);
        if (readResult instanceof PdfUtils.PdfFailure<PdfUtils.FullPdf>(PdfUtils.PdfError error)) {
            return officialReadError(error.message());
        }
        PdfUtils.FullPdf pdf = ((PdfUtils.PdfSuccess<PdfUtils.FullPdf>) readResult).data();
        return readPdfFullDocument(pdf);
    }

    private static String outsideDocumentMessage(String pages, PageRange range, int pageCount) {
        boolean single = range.first() == range.last() && !Strings.CS.contains(pages, "-");
        String requested = single
            ? "Requested page " + range.first()
            : "Requested pages " + pages;
        String pageWord = pageCount == 1 ? "page" : "pages";
        return requested + " is outside the document (PDF has " + pageCount + " " + pageWord
            + "). Use a range within 1-" + pageCount + ", maximum "
            + PdfUtils.PDF_MAX_PAGES_PER_READ + " pages per request (e.g. pages: \"1-"
            + pageCount + "\").";
    }

    /**
     * Sends the entire PDF as a base64 {@code document} block.
     */
    private ToolResult readPdfFullDocument(PdfUtils.FullPdf pdf) {
        String filePath = pdf.filePath();
        long size = pdf.originalSize();
        byte[] data = pdf.bytes();
        String base64 = Base64.getEncoder().encodeToString(data);

        ObjectNode source = mapper().createObjectNode();
        source.put("type", "base64");
        source.put("media_type", "application/pdf");
        source.put("data", base64);
        DocumentBlock docBlock = new DocumentBlock(source);

        UserMessage docMessage = new UserMessage(
            UUID.randomUUID().toString(),
            MessageContent.ofBlocks(List.of(docBlock)),
            true, false, null, null, null, Instant.now(), null, null);

        List<ContentBlock> content = new ArrayList<>();
        content.add(new TextBlock("PDF file read: " + filePath + " (" + FormatUtils.formatFileSize(size) + ")"));

        ObjectNode output = mapper().createObjectNode();
        output.put("type", "pdf");
        ObjectNode file = output.putObject("file");
        file.put("filePath", filePath);
        file.put("base64", base64);
        file.put("originalSize", size);

        return new ToolResult(content, false, null, output, null, List.of(docMessage));
    }

    /**
     * Rasterizes PDF pages [{@code range.first}, {@code range.last}] (or all pages when {@code range}
     * is {@code null}) to JPEGs via poppler and sends them as {@code image} blocks in a supplemental
     * {@code isMeta:true} user message.
     */
    private ToolResult readPdfPages(String filePath, long size, PageRange range, Path path,
                                    ToolExecutionContext context) {
        PdfUtils.PdfResult<PdfUtils.ExtractedPages> extractResult = PdfUtils.extractPages(
            path, filePath, range != null ? range.first() : null,
            range != null ? range.last() : null, toolResultsDir(context), pdfCommandRunner);
        if (extractResult instanceof PdfUtils.PdfFailure<PdfUtils.ExtractedPages>(
            PdfUtils.PdfError error
        )) {
            return officialReadError(error.message());
        }
        PdfUtils.ExtractedPages extracted =
            ((PdfUtils.PdfSuccess<PdfUtils.ExtractedPages>) extractResult).data();
        List<byte[]> pageImages = extracted.images();

        List<ContentBlock> imageBlocks = new ArrayList<>();
        for (byte[] img : pageImages) {
            ImageResizer.ResizeResult resized;
            try {
                resized = ImageResizer.maybeResizeAndDownsample(img, "jpeg");
            } catch (IllegalArgumentException e) {
                return officialReadError(e.getMessage());
            }
            ObjectNode source = mapper().createObjectNode();
            source.put("type", "base64");
            source.put("media_type", resized.mediaType());
            source.put("data", Base64.getEncoder().encodeToString(resized.buffer()));
            imageBlocks.add(new ImageBlock(source));
        }

        List<ContentBlock> content = List.of(new TextBlock(
            "PDF pages extracted: " + pageImages.size() + " page(s) from " + filePath
                + " (" + FormatUtils.formatFileSize(size) + ")"));

        UserMessage pagesMessage = new UserMessage(
            UUID.randomUUID().toString(),
            MessageContent.ofBlocks(imageBlocks),
            true, false, null, null, null, Instant.now(), null, null);

        ObjectNode output = mapper().createObjectNode();
        output.put("type", "parts");
        ObjectNode file = output.putObject("file");
        file.put("filePath", filePath);
        file.put("originalSize", size);
        file.put("outputDir", extracted.outputDir().toString());
        file.put("count", pageImages.size());

        return new ToolResult(content, false, null, output, null, List.of(pagesMessage));
    }

    private Path toolResultsDir(ToolExecutionContext context) {
        String cwd = context != null && context.workingDirectory() != null
            ? context.workingDirectory() : System.getProperty("user.dir");
        String sessionId = context != null && context.sessionId() != null
            ? context.sessionId() : UUID.randomUUID().toString();
        return toolResultsDirResolver.apply(cwd, sessionId);
    }


    private boolean isPDFSupported(ToolExecutionContext context) {
        String model = context != null ? context.currentModel() : null;
        if (model == null) return true;
        return !Strings.CI.contains(model, "claude-3-haiku");
    }


    /**
     * Task 57.3: Read Jupyter notebook and render cells.
     */
    private Object readNotebookFile(Path path, String filePath, String absolutePath,
                                     int offset, int limit, ToolExecutionContext context) throws IOException {
        String content = Files.readString(path, StandardCharsets.UTF_8);
        JsonNode notebook;
        try {
            notebook = mapper().readTree(content);
        } catch (Exception e) {
            return "Error: invalid Jupyter notebook JSON: " + e.getMessage();
        }

        if (!notebook.has("cells")) {
            return "Error: not a valid Jupyter notebook (missing 'cells' field)";
        }



        // registration so an oversized notebook is not treated as "read".
        String notebookTokenError = checkMaxTokens(content, "ipynb");
        if (notebookTokenError != null) {
            return ToolResult.error(notebookTokenError);
        }

        context.fileStateCache().set(absolutePath, new FileStateCache.FileState(
            content, FileUtils.modificationTimeMillis(path),
            offset > 0 ? offset : null, limit > 0 ? limit : null, false));

        StringBuilder result = new StringBuilder();
        result.append("[Jupyter Notebook: ").append(filePath).append("]\n\n");

        JsonNode cells = notebook.get("cells");
        int cellIndex = 0;
        for (JsonNode cell : cells) {
            cellIndex++;
            String cellType = cell.has("cell_type") ? cell.get("cell_type").asText() : "unknown";
            JsonNode source = cell.get("source");

            result.append(String.format("--- Cell %d [%s] ---%n", cellIndex, cellType));

            if (source.isArray()) {
                for (JsonNode line : source) {
                    result.append(line.asText());
                }
            } else if (source.isTextual()) {
                result.append(source.asText());
            }
            result.append("\n\n");
        }

        return result.toString();
    }

    /**
     * Path-based device-file block check (Task 57.4).
     */
    private static boolean isBlockedDevicePath(String absolutePath) {
        if (BLOCKED_DEVICE_FILES.contains(absolutePath)) {
            return true;
        }
        return Strings.CS.startsWith(absolutePath, "/proc/")
            && (Strings.CS.endsWith(absolutePath, "/fd/0")
            || Strings.CS.endsWith(absolutePath, "/fd/1")
            || Strings.CS.endsWith(absolutePath, "/fd/2"));
    }


    private static boolean isSafeEmptyDevice(Path path) {
        return Strings.CS.equals(path.toAbsolutePath().normalize().toString(), "/dev/null");
    }

    /**
     * Returns the file extension including the leading dot (lower-cased) for the binary-file guard
     * message (e.g.
     */
    private static String extWithDot(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return "";
        }
        return name.substring(dot);
    }

    /**
     * True when {@code path}'s extension is in the binary-extension set (Task 57.4 / fix #11).
     */
    private static boolean hasBinaryExtension(Path path) {
        String ext = extWithDot(path);
        return !ext.isEmpty() && BINARY_EXTENSIONS.contains(ext);
    }

    /**
     * Returns the file extension (without the dot, lower-cased) for token-estimation tuning.
     */
    private static String extOf(String filePath) {
        int dot = filePath.lastIndexOf('.');
        if (dot < 0 || dot == filePath.length() - 1) {
            return "";
        }
        return filePath.substring(dot + 1).toLowerCase(Locale.ROOT);
    }


    private static int estimateTokens(String content, String ext) {
        if (StringUtils.isEmpty(content)) {
            return 0;
        }
        int bytesPerToken = switch (ext) {
            case "json", "jsonl", "jsonc" -> 2;
            default -> 4;
        };
        return (int) Math.round((double) content.length() / bytesPerToken);
    }


    private String checkMaxTokens(String content, String ext) {
        int estimate = estimateTokens(content, ext);
        if (estimate <= 0 || estimate <= maxOutputTokens / 4) {
            return null;
        }
        if (estimate > maxOutputTokens) {
            return String.format(
                "File content (%d tokens) exceeds maximum allowed tokens (%d). "
                    + "Use offset and limit parameters to read specific portions of the file, "
                    + "or search for specific content instead of reading the whole file.",
                estimate, maxOutputTokens);
        }
        return null;
    }

    /**
     * Returns the raw (un-numbered) windowed content that {@link #applyOffsetLimit} will display, for
     * the token gate.
     */
    // ---- Image detection helpers ----

    private boolean isImageFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String ext : IMAGE_EXTENSIONS) {
            if (Strings.CS.endsWith(name, ext)) return true;
        }
        return false;
    }

    private boolean isPdfFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return Strings.CS.endsWith(name, ".pdf");
    }

    private boolean isNotebookFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String ext : NOTEBOOK_EXTENSIONS) {
            if (Strings.CS.endsWith(name, ext)) return true;
        }
        return false;
    }

    private String detectImageFormat(byte[] data) {
        if (data.length < 4) return null;

        if (data.length >= PNG_HEADER.length) {
            boolean match = true;
            for (int i = 0; i < PNG_HEADER.length; i++) {
                if (data[i] != PNG_HEADER[i]) { match = false; break; }
            }
            if (match) return "PNG";
        }

        if (data[0] == JPG_HEADER[0] && data[1] == JPG_HEADER[1] && data[2] == JPG_HEADER[2]) {
            return "JPEG";
        }

        if (data.length >= GIF_HEADER_87A.length) {
            boolean match87 = true, match89 = true;
            for (int i = 0; i < GIF_HEADER_87A.length; i++) {
                if (data[i] != GIF_HEADER_87A[i]) match87 = false;
                if (data[i] != GIF_HEADER_89A[i]) match89 = false;
            }
            if (match87 || match89) return "GIF";
        }

        boolean webpMatch = true;
        for (int i = 0; i < WEBP_HEADER.length; i++) {
            if (data[i] != WEBP_HEADER[i]) { webpMatch = false; break; }
        }
        if (webpMatch) return "WebP";

        return null;
    }

    /**
     * Checks if a file appears to be binary by scanning for null bytes in the first 8KB.
     */
    static boolean isBinaryFile(Path path) {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(8192);
            for (byte aByte : bytes) {
                if (aByte == 0) {
                    return true;
                }
            }
            return false;
        } catch (IOException _) {
            return false;
        }
    }

    private static JsonNode buildSchema() {
        ObjectNode schema = mapper().createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");


        ObjectNode filePathProp = properties.putObject("file_path");
        filePathProp.put("description", "The absolute path to the file to read");
        filePathProp.put("type", "string");

        ObjectNode offsetProp = properties.putObject("offset");
        offsetProp.put("description",
            "The line number to start reading from. Only provide if the file is "
            + "too large to read at once");
        offsetProp.put("type", "integer");
        offsetProp.put("minimum", 0);
        offsetProp.put("maximum", 9007199254740991L);

        ObjectNode limitProp = properties.putObject("limit");
        limitProp.put("description",
            "The number of lines to read. Only provide if the file is too large "
            + "to read at once.");
        limitProp.put("type", "integer");
        limitProp.put("exclusiveMinimum", 0);
        limitProp.put("maximum", 9007199254740991L);

        ObjectNode pagesProp = properties.putObject("pages");
        pagesProp.put("description",
            "Page range for PDF files (e.g., \"1-5\", \"3\", \"10-20\"). Only "
            + "applicable to PDF files. Maximum 20 pages per request.");
        pagesProp.put("type", "string");

        ArrayNode required = schema.putArray("required");
        required.add("file_path");


        schema.put("additionalProperties", false);

        return schema;
    }
}

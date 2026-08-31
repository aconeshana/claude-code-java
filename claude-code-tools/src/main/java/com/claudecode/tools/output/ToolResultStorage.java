package com.claudecode.tools.output;

import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.engine.ToolResultContentForm;
import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.session.SessionManager;
import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.config.EnvUtils;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import com.claudecode.tools.Tool;


public final class ToolResultStorage {

    public static final int DEFAULT_MAX_RESULT_SIZE_CHARS = 50_000;
    public static final int PREVIEW_SIZE_BYTES = 2_000;
    public static final String PERSISTED_OUTPUT_TAG = "<persisted-output>";
    public static final String PERSISTED_OUTPUT_CLOSING_TAG = "</persisted-output>";
    private static final int MAX_FILENAME_LENGTH = 240;
    private static final int HASH_HEX_LENGTH = 12;

    private ToolResultStorage() {}

    /**
     * Processes one already-mapped result. This method is synchronous because
     * the Java tool registry is synchronous; duplicate calls use CREATE_NEW so
     * replay never rewrites a persisted result.
     */
    public static ToolResult process(ToolResult result, Tool<?, ?> tool,
                                     ToolExecutionContext context) {
        return process(result, tool, context,
            (cwd, sessionId) -> new SessionManager(cwd).getToolResultsDir(sessionId));
    }

    static ToolResult process(ToolResult result, Tool<?, ?> tool,
                              ToolExecutionContext context,
                              BiFunction<String, String, Path> directoryResolver) {

        // contract violation — reject it rather than silently passing it through.
        Objects.requireNonNull(result, "result");

        List<ContentBlock> blocks = result.content();

        // never sees a bare function-result boundary. This applies before the
        // persistence/context checks because it is a wire-shape safeguard, not
        // a filesystem operation. isEffectivelyEmpty tolerates a null list, so
        // reaching the persistence guard below proves blocks != null.
        if (isEffectivelyEmpty(blocks)) {
            String toolName = tool == null ? "Tool" : tool.name();
            return withContent(result,
                List.of(new TextBlock("(" + toolName + " completed with no output)")));
        }

        if (tool == null || context == null
                || context.toolUseId() == null || StringUtils.isBlank(context.toolUseId())
                || context.sessionId() == null || StringUtils.isBlank(context.sessionId())
                || blocks.stream().anyMatch(block -> !(block instanceof TextBlock))) {
            return result;
        }

        boolean json = result.contentForm() == ToolResultContentForm.BLOCKS;
        String content = serialize(blocks, json);
        if (content == null) return result;

        long threshold = effectiveThreshold(tool.maxResultSizeChars(), tool.name(),
            tool.persistenceThresholdCeiling());
        long logicalSize = json ? content.length()
            : ((TextBlock) blocks.getFirst()).text() == null
                ? 0 : ((TextBlock) blocks.getFirst()).text().length();
        if (logicalSize <= threshold) {
            return result;
        }

        Path outputPath = outputPath(context, directoryResolver, json ? ".json" : ".txt");
        try {
            Files.createDirectories(outputPath.getParent());
            try {
                Files.writeString(outputPath, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException _) {
                // Idempotent replay: keep the original file and recompute the
                // same preview from the current content.
            }
            String preview = preview(content, PREVIEW_SIZE_BYTES);
            boolean hasMore = !preview.equals(content);
            String message = PERSISTED_OUTPUT_TAG + "\n"
                + "Output too large (" + formatFileSize(content.length())
                + "). Full output saved to: " + outputPath + "\n\n"
                + "Preview (first " + formatFileSize(PREVIEW_SIZE_BYTES) + "):\n"
                + preview + (hasMore ? "\n...\n" : "\n")
                + PERSISTED_OUTPUT_CLOSING_TAG;
            return withContent(result, List.of(new TextBlock(message)));
        } catch (IOException _) {
            return result;
        }
    }


    static long effectiveThreshold(int declared, String toolName, int ceiling) {
        JsonNode features = null;
        try {
            var env = SubprocessEnvironment.snapshot();
            boolean disabled =Strings.CS.equals( "test", env.get("NODE_ENV"))
                || EnvUtils.isEnvTruthy(env.get("CLAUDE_CODE_USE_BEDROCK"))
                || EnvUtils.isEnvTruthy(env.get("CLAUDE_CODE_USE_VERTEX"))
                || EnvUtils.isEnvTruthy(env.get("CLAUDE_CODE_USE_FOUNDRY"))
                || env.containsKey("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC")
                || env.containsKey("DISABLE_TELEMETRY");
            if (!disabled && Files.isRegularFile(ClaudePaths.GLOBAL_JSON)) {
                JsonNode global = JsonUtils.readJson(ClaudePaths.GLOBAL_JSON);
                features = global == null ? null : global.path("cachedGrowthBookFeatures");
            }
        } catch (Exception _) {
            // Missing or malformed GrowthBook state must never affect result delivery.
        }
        return effectiveThreshold(declared, toolName, ceiling, features);
    }

    static long effectiveThreshold(int declared, String toolName, JsonNode features) {
        return effectiveThreshold(declared, toolName, DEFAULT_MAX_RESULT_SIZE_CHARS, features);
    }

    static long effectiveThreshold(int declared, String toolName, int ceiling, JsonNode features) {
        if (declared == Integer.MAX_VALUE) return Long.MAX_VALUE;
        if (features != null && toolName != null && !StringUtils.isBlank(toolName)) {
            JsonNode overrides = features.get("tengu_velvet_ibis");
            if (overrides != null && overrides.isObject()) {
                JsonNode override = overrides.get(toolName);
                if (override != null && override.isNumber() && override.canConvertToInt()) {
                    int value = override.asInt();
                    if (value > 0) return value;
                }
            }
        }
        return Math.min(declared, ceiling);
    }

    @Explanation("Preserves safe tool-use ids byte-for-byte but hashes unsafe or overlong ids to prevent traversal and filename collisions.")
    static Path outputPath(ToolExecutionContext context,
                           BiFunction<String, String, Path> directoryResolver,
                           String extension) {
        String cwd = context.workingDirectory() == null
            ? System.getProperty("user.dir", ".") : context.workingDirectory();
        Path directory = directoryResolver.apply(cwd, context.sessionId()).toAbsolutePath().normalize();
        return safeOutputPath(directory, context.toolUseId(), extension);
    }

    @Explanation("Preserves safe tool-use ids byte-for-byte but hashes unsafe or overlong ids to prevent traversal and filename collisions.")
    static Path safeOutputPath(Path directory, String toolUseId, String extension) {
        Path normalizedDirectory = directory.toAbsolutePath().normalize();
        String id = safeFileStem(toolUseId, extension.length());
        Path resolved = normalizedDirectory.resolve(id + extension).normalize();
        if (!resolved.startsWith(normalizedDirectory)) {
            throw new IllegalArgumentException("Tool result path escapes its session directory");
        }
        return resolved;
    }

    private static String safeFileStem(String id, int extensionLength) {
        String value = id == null ? "" : id;
        int maxStem = MAX_FILENAME_LENGTH - extensionLength;
        boolean safe = StringUtils.isNotBlank(value)
            && value.length() <= maxStem
            && value.matches("[A-Za-z0-9][A-Za-z0-9._-]*")
            && !Strings.CS.equals(".", value) && !Strings.CS.equals("..", value);
        if (safe) return value;
        String readable = value.replaceAll("[^A-Za-z0-9._-]", "_")
            .replaceFirst("^[._-]+", "");
        if (StringUtils.isBlank(readable)) readable = "tool-result";
        String hash = sha256(value).substring(0, HASH_HEX_LENGTH);
        int readableLimit = Math.max(1, maxStem - hash.length() - 1);
        if (readable.length() > readableLimit) readable = readable.substring(0, readableLimit);
        return readable + "-" + hash;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    static String preview(String content, int maxBytes) {
        if (content.length() <= maxBytes) return content;
        String truncated = content.substring(0, maxBytes);
        int newline = truncated.lastIndexOf('\n');
        if (newline > maxBytes / 2) return truncated.substring(0, newline);
        return truncated;
    }

    static String formatFileSize(int bytes) {
        double kb = bytes / 1024.0;
        if (kb < 1) return bytes + " bytes";
        if (kb < 1024) return trim(kb) + "KB";
        double mb = kb / 1024.0;
        if (mb < 1024) return trim(mb) + "MB";
        return trim(mb / 1024.0) + "GB";
    }

    private static String trim(double value) {
        String text = String.format(Locale.ROOT, "%.1f", value);
        return Strings.CS.endsWith( text, ".0") ? text.substring(0, text.length() - 2) : text;
    }

    private static boolean isEffectivelyEmpty(List<ContentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) return true;
// A non-text block is meaningful even if adjacent text is blank; this.
        for (ContentBlock block : blocks) {
            if (!(block instanceof TextBlock(String text1))) return false;
            if (text1 != null && !text1.trim().isEmpty()) return false;
        }
        return true;
    }

    private static ToolResult withContent(ToolResult result, List<ContentBlock> content) {
        return new ToolResult(content, result.isError(), result.acceptFeedback(),
            result.toolUseResult(), result.structuredOutput(), result.newMessages(),
            result.contextModifier(), result.includeIsErrorField(), result.afterResultEmitted(),
            result.mcpMeta(), ToolResultContentForm.STRING, result.userFeedbackBlocks());
    }

    /**
     * Persists a candidate selected by the message-level aggregate budget.
     * This is deliberately separate from {@link #process}: aggregate selection
     * happens while assembling a request, after the original tool context has
     * gone out of scope, but uses the same path, preview and idempotent-write
     * contract as the per-tool pipeline.
     */
    public static Optional<String> persistForBudget(String toolUseId, List<ContentBlock> blocks,
                                              String workingDirectory, String sessionId) {
        if (StringUtils.isBlank(toolUseId) || sessionId == null || StringUtils.isBlank(sessionId)
                || blocks == null || blocks.isEmpty()
                || blocks.stream().anyMatch(block -> !(block instanceof TextBlock))) {
            return Optional.empty();
        }
        boolean json = blocks.size() > 1;
        String content = serialize(blocks, json);
        if (content == null) return Optional.empty();
        Path dir = new SessionManager(workingDirectory == null
            ? System.getProperty("user.dir", ".") : workingDirectory)
            .getToolResultsDir(sessionId);
        Path path = safeOutputPath(dir, toolUseId, json ? ".json" : ".txt");
        try {
            Files.createDirectories(dir);
            try {
                Files.writeString(path, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException _) {
                // Idempotent replay: recompute the same replacement below.
            }
            String preview = preview(content, PREVIEW_SIZE_BYTES);
            boolean hasMore = !preview.equals(content);
            String message = PERSISTED_OUTPUT_TAG + "\n"
                + "Output too large (" + formatFileSize(content.length())
                + "). Full output saved to: " + path + "\n\n"
                + "Preview (first " + formatFileSize(PREVIEW_SIZE_BYTES) + "):\n"
                + preview + (hasMore ? "\n...\n" : "\n")
                + PERSISTED_OUTPUT_CLOSING_TAG;
            return Optional.of(message);
        } catch (IOException _) {
            return Optional.empty();
        }
    }

    private static String serialize(List<ContentBlock> blocks, boolean json) {
        if (!json) {
            return blocks.size() == 1 && blocks.getFirst() instanceof TextBlock(String text1)
                ? Objects.requireNonNullElse(text1, "") : null;
        }
        StringBuilder out = new StringBuilder("[\n");
        try {
            for (int i = 0; i < blocks.size(); i++) {
                TextBlock text = (TextBlock) blocks.get(i);
                out.append("  {\n")
                    .append("    \"type\": \"text\",\n")
                    .append("    \"text\": ")
                    .append(JsonUtils.getMapper().writeValueAsString(
                        Objects.requireNonNullElse(text.text(), "")))
                    .append("\n  }");
                if (i + 1 < blocks.size()) out.append(',');
                out.append('\n');
            }
        } catch (IOException error) {
            return null;
        }
        return out.append(']').toString();
    }
}

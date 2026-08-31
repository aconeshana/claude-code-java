package com.claudecode.tools.mcp;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.engine.ToolResultContentForm;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.ImageBlock;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.imagestore.ImageResizer;
import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.mcp.McpClientRuntime;
import com.claudecode.mcp.McpException;
import com.claudecode.mcp.McpNameNormalizer;
import com.claudecode.mcp.McpOutputStorage;
import com.claudecode.mcp.McpToolInfo;
import com.claudecode.core.engine.PermissionUpdate;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.session.SessionManager;
import com.claudecode.tools.Tool;
import com.claudecode.tools.ToolIdentity;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.process.SubprocessEnvironment;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Proxy tool that exposes an MCP server tool as a built-in tool.
 */
public class MCPTool extends Tool<JsonNode, ToolResult> {

    private static final int MAX_MCP_DESCRIPTION_LENGTH = 2048;
    private static final int MAX_ANNOTATED_RESULT_SIZE_CHARS = 500_000;

    private final McpToolInfo toolInfo;
    private final McpClientRuntime clientManager;
    private final ToolIdentity identity;

    public MCPTool(McpToolInfo toolInfo, McpClientRuntime clientManager) {
        this.toolInfo = toolInfo;
        this.clientManager = clientManager;
        this.identity = new ToolIdentity(
            "mcp__" + toolInfo.serverId() + "__" + toolInfo.name());
    }

    @Override
    public ToolIdentity identity() {
        return identity;
    }

    @Override
    public String description() {

        // null description for a server that omitted the optional field.
        return toolInfo.description() == null ? "" : toolInfo.description();
    }

    @Override
    public String prompt(ToolExecutionContext context) {
        return truncateDescription(description());
    }

    @Override
    public String searchHint() {
        JsonNode meta = toolInfo.meta().path("anthropic/searchHint");
        if (!meta.isTextual()) return "";
        return meta.asText().replaceAll("\\s+", " ").trim();
    }

    @Override
    public boolean alwaysLoad() {
        return toolInfo.meta().path("anthropic/alwaysLoad").asBoolean(false);
    }

    @Override
    public int maxResultSizeChars() {
        Integer annotated = annotatedMaxResultSizeChars();
        return annotated == null ? super.maxResultSizeChars() : annotated;
    }

    @Override
    public int persistenceThresholdCeiling() {
        return annotatedMaxResultSizeChars() == null
            ? super.persistenceThresholdCeiling() : MAX_ANNOTATED_RESULT_SIZE_CHARS;
    }

    @Override
    public boolean isMcp() {
        return true;
    }

    @Override
    public ToolMcpInfo mcpInfo() {
        return new ToolMcpInfo(toolInfo.serverId(), toolInfo.name());
    }

    @Override
    public boolean isDestructive(JsonNode input) {
        return toolInfo.annotations().path("destructiveHint").asBoolean(false);
    }

    @Override
    public boolean isOpenWorld(JsonNode input) {
        return toolInfo.annotations().path("openWorldHint").asBoolean(false);
    }

    @Override
    public SearchReadClassification searchReadClassification(JsonNode input) {

        // for unknown names. Never infer this from verbs: create/update/delete
        // tools are often read-adjacent but still mutate external state.
        return McpToolCollapseClassifier.classify(toolInfo.name());
    }


    @Override
    public Object toAutoClassifierInput(JsonNode input) {
        if (input != null && input.isObject() && !input.isEmpty()) {
            List<String> parts = new ArrayList<>();
            input.fields().forEachRemaining(entry -> parts.add(
                entry.getKey() + "=" + classifierString(entry.getValue())));
            return String.join(" ", parts);
        }
        return toolInfo.name();
    }

    private static String classifierString(JsonNode value) {
        if (value == null || value.isNull()) return "null";
        if (value.isTextual()) return value.asText();
        if (value.isBoolean()) return Boolean.toString(value.asBoolean());
        if (value.isNumber()) return value.asText();
        // Arrays are comma-joined and objects use a stable marker. Keep a
        // non-empty representation for the
        // security classifier rather than silently omitting nested input.
        if (value.isArray()) {
            List<String> values = new ArrayList<>();
            value.forEach(v -> values.add(classifierString(v)));
            return String.join(",", values);
        }
        return "[object Object]";
    }


    @Override
    public PermissionDecision checkPermissions(JsonNode input, ToolPermissionContext context) {
        PermissionUpdate suggestion = new PermissionUpdate.AddRules(
            List.of(new PermissionUpdate.RuleValue(name(), null)),
            PermissionUpdate.Behavior.ALLOW,
            PermissionUpdate.Destination.LOCAL_SETTINGS);
        return new PermissionDecision.Ask(
            null, null, "MCPTool requires permission.", null, null,
            List.of(suggestion));
    }

    @Override
    public String description(JsonNode input, ToolExecutionContext context) {
        return truncateDescription(description());
    }

    private static String truncateDescription(String description) {
        return description != null && description.length() > MAX_MCP_DESCRIPTION_LENGTH
            ? description.substring(0, MAX_MCP_DESCRIPTION_LENGTH) + "… [truncated]"
            : description;
    }

    @Override
    public JsonNode inputSchema() {
        return toolInfo.inputSchema();
    }

    @Override
    public JsonNode outputSchema() {
        return JsonUtils.getMapper().createObjectNode().put("type", "string");
    }

    @Override
    public ToolResult mapUpdatedOutput(JsonNode updatedOutput, JsonNode input,
                                       ToolExecutionContext context) {
        return ToolResult.success(updatedOutput.asText())
            .withToolUseResult(updatedOutput.asText());
    }

    @Override
    public ToolResult call(JsonNode input, ToolExecutionContext context) {
        long started = System.currentTimeMillis();
        reportProgress(context, "started", 0L);
        try {
            JsonNode result;
            if (context == null
                    || context.progressSink() == ToolExecutionContext.ProgressSink.NOOP) {
                // Keep the narrow five-argument seam used by lightweight MCP
                // fixtures; production contexts with a progress sink use the
                // callback-aware path below.
                result = clientManager.callTool(
                    toolInfo.serverId(), toolInfo.name(), input,
                    context != null ? context.toolUseId() : null,
                    context != null ? context.abortController() : null);
            } else {
                result = clientManager.callTool(
                    toolInfo.serverId(), toolInfo.name(), input,
                    context.toolUseId(), context.abortController(),
                    progress -> reportProgress(context, progress));
            }
            ToolResult output = toToolResult(result, context);
            reportProgress(context, "completed", System.currentTimeMillis() - started);
            return output;
        } catch (McpException e) {
            reportProgress(context, "failed", System.currentTimeMillis() - started);
            String message = StringUtils.isBlank(e.getMessage())
                ? "MCP tool call failed" : e.getMessage();

            // the same diagnostic with an "Error: " prefix in toolUseResult.
            return ToolResult.error(message).withToolUseResult("Error: " + message);
        } catch (RuntimeException e) {
            reportProgress(context, "failed", System.currentTimeMillis() - started);
            throw e;
        }
    }

    private ToolResult toToolResult(JsonNode result, ToolExecutionContext context) {
        if (result == null || !result.isObject()) {
            return ToolResult.error(unexpectedResponseMessage());
        }
        if (result.path("isError").asBoolean(false)) {
            // MCP error results may still carry _meta (for example a trace id,

            // metadata on McpToolCallError instead of dropping it with the
            // model-facing error text, so keep the same dual-channel payload.
            String errorText = firstErrorText(result);

            // `Error: <message>` as toolUseResult while leaving the model-facing
            // tool_result content as the raw diagnostic text. Preserve that
            // split and keep the MCP _meta/structured payload alongside it.
            return withMcpMetadata(ToolResult.error(errorText), result, "Error: " + errorText);
        }

        JsonNode structured = result.get("structuredContent");
        if (structured != null && !structured.isNull()) {
            String text = structured.toString();
            ToolResult processed = processLargeTextResult(text, "JSON", context);
            return withMcpMetadata(processed, result, textPayload(processed));
        }

        JsonNode content = result.get("content");
        if (content == null || !content.isArray()) {
            JsonNode legacy = result.get("toolResult");
            if (legacy != null) {
                String text = legacy.asText();
                ToolResult processed = processLargeTextResult(text, "text", context);
                return withMcpMetadata(processed, result, textPayload(processed));
            }
            return ToolResult.error(unexpectedResponseMessage());
        }

        List<ContentBlock> blocks = new ArrayList<>();
        ArrayNode sdkPayload = JsonUtils.getMapper().createArrayNode();
        for (JsonNode item : content) {
            appendContent(item, blocks, sdkPayload, context);
        }
        ProcessedContent processed = processLargeBlocks(blocks, sdkPayload, context);
        return withMcpMetadata(new ToolResult(List.copyOf(processed.blocks()), false)
                .withContentForm(ToolResultContentForm.BLOCKS),
            result, processed.modelData());
    }

    /**
     * matches {@code processMCPResult}: estimate output size, truncate when the large-output feature is
     * disabled, and otherwise save text/JSON to the session tool-results directory.
     */
    private record ProcessedContent(List<ContentBlock> blocks, Object modelData) {}

    private ProcessedContent processLargeBlocks(List<ContentBlock> blocks,
                                                ArrayNode sdkPayload,
                                                ToolExecutionContext context) {

        // the IDE server: those results are consumed by the IDE integration,
        // not sent directly to the model. Keep the same server-scoped escape
        // hatch before applying the generic MCP threshold.
        if (Strings.CI.equals("ide", toolInfo.serverId())) {
            return new ProcessedContent(blocks, sdkPayload);
        }
        if (annotatedMaxResultSizeChars() != null && !containsBinary(blocks)) {
            return new ProcessedContent(blocks, sdkPayload);
        }
        if (!needsMcpTruncation(blocks)) {
            return new ProcessedContent(blocks, sdkPayload);
        }
        if (containsImage(blocks) || !largeOutputFilesEnabled()) {
            List<ContentBlock> truncated = truncateBlocks(blocks, maxMcpOutputChars());
            return new ProcessedContent(truncated, blocksAsJson(truncated));
        }
        String content = sdkPayload.toPrettyString();
        String persisted = persistLargeOutput(content, "JSON array", context);
        // processMCPResult returns the persisted instruction string for a
        // large content-array result. Keep the same value on both the model
        // channel and the transcript/toolUseResult channel; retaining the
        // pre-persistence array here would make the UI claim the full result
        // was available even though the model received a file pointer.
        return new ProcessedContent(List.of(new TextBlock(persisted)), persisted);
    }

    private ToolResult processLargeTextResult(String text, String format,
                                              ToolExecutionContext context) {
        if (Strings.CI.equals("ide", toolInfo.serverId())) {
            return ToolResult.success(text);
        }
        if (annotatedMaxResultSizeChars() != null) {
            return ToolResult.success(text);
        }
        if (text == null || estimateMcpTokens(text) <= maxMcpOutputTokens()) {
            return ToolResult.success(text);
        }
        if (!largeOutputFilesEnabled()) {
            String truncated = text.substring(0, Math.min(text.length(), maxMcpOutputChars()))
                + truncationMessage();
            return ToolResult.success(truncated);
        }
        return ToolResult.success(persistLargeOutput(text, format, context));
    }

    private static boolean containsImage(List<ContentBlock> blocks) {
        return blocks.stream().anyMatch(ImageBlock.class::isInstance);
    }

    private static boolean containsBinary(List<ContentBlock> blocks) {
        return blocks.stream().anyMatch(block -> !(block instanceof TextBlock));
    }

    private Integer annotatedMaxResultSizeChars() {
        JsonNode value = toolInfo.meta().get("anthropic/maxResultSizeChars");
        if (value == null || !value.isNumber()) return null;
        double numeric = value.asDouble(Double.NaN);
        if (!Double.isFinite(numeric) || numeric <= 0) return null;
        return (int) Math.min(Math.floor(numeric), MAX_ANNOTATED_RESULT_SIZE_CHARS);
    }

    private static boolean needsMcpTruncation(List<ContentBlock> blocks) {
        long estimate = 0;
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock(String text1)) {
                estimate += estimateMcpTokens(text1);
            } else if (block instanceof ImageBlock) {
                estimate += 1600;
            }
        }
        return estimate > maxMcpOutputTokens();
    }

    private static List<ContentBlock> truncateBlocks(List<ContentBlock> blocks, int maxChars) {
        List<ContentBlock> out = new ArrayList<>();
        int used = 0;
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock(String text1)) {
                if (used >= maxChars) break;
                String value = text1 == null ? "" : text1;
                int remaining = maxChars - used;
                String kept = value.length() <= remaining ? value : value.substring(0, remaining);
                out.add(new TextBlock(kept));
                used += kept.length();
                if (kept.length() < value.length()) break;
            } else if (block instanceof ImageBlock && used + 1600 <= maxChars) {
                out.add(block);
                used += 1600;
            }
        }
        out.add(new TextBlock(truncationMessage()));
        return List.copyOf(out);
    }

    private String persistLargeOutput(String content, String format,
                                      ToolExecutionContext context) {
        if (context == null || context.sessionId() == null || StringUtils.isBlank(context.sessionId())) {
            return "Error: result (" + content.length()
                + " characters) exceeds maximum allowed tokens."
                + " Output could not be saved because the session is unavailable.";
        }
        String cwd = context.workingDirectory() == null
            ? System.getProperty("user.dir", ".") : context.workingDirectory();
        Path directory = new SessionManager(cwd)
            .getToolResultsDir(context.sessionId());
        String id = "mcp-" + McpNameNormalizer.normalize(toolInfo.serverId()) + "-"
            + McpNameNormalizer.normalize(toolInfo.name()) + "-" + System.currentTimeMillis();

        // JSON/text results intentionally have no MIME-derived extension.
        Path path = directory.resolve(id);
        try {
            Files.createDirectories(directory);
            try {
                Files.writeString(path, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException _) {
                // Replay of the same result: retain the existing file.
            }
            return "Error: result (" + content.length()
                + " characters) exceeds maximum allowed tokens. Output has been saved to "
                + path + ".\nFormat: " + format
                + "\nUse offset and limit parameters to read specific portions of the file, search within it for specific content, and jq to make structured queries.\n"
                + "REQUIREMENTS FOR SUMMARIZATION/ANALYSIS/REVIEW:\n"
                + "- You MUST read the content from the file at " + path
                + " in sequential chunks until 100% of the content has been read.\n"
                + "- If you receive truncation warnings when reading the file, reduce the chunk size until you have read 100% of the content without truncation.\n"
                + "- Before producing ANY summary or analysis, you MUST explicitly describe what portion of the content you have read. ***If you did not read the entire content, you MUST explicitly state this.***";
        } catch (Exception error) {
            return "Error: result (" + content.length()
                + " characters) exceeds maximum allowed tokens. Failed to save output to file: "
                + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage())
                + ". If this MCP server provides pagination or filtering tools, use them to retrieve specific portions of the data.";
        }
    }

    private static int maxMcpOutputTokens() {
        String value = SubprocessEnvironment.get("MAX_MCP_OUTPUT_TOKENS");
        if (value != null) {
            try {
                int parsed = Integer.parseInt(value.trim());
                if (parsed > 0) return parsed;
            } catch (NumberFormatException _) {

            }
        }

        // tengu_satin_quoll GrowthBook value after the explicit environment
        // override. Read the same persisted cache when it is available; a
        // missing or malformed cache must not affect MCP execution.
        try {
            if (Files.isRegularFile(ClaudePaths.GLOBAL_JSON)) {
                JsonNode global = JsonUtils.readJson(ClaudePaths.GLOBAL_JSON);
                JsonNode overrides = global == null ? null
                    : global.path("cachedGrowthBookFeatures").path("tengu_satin_quoll");
                JsonNode override = overrides == null ? null : overrides.get("mcp_tool");
                if (override != null && override.isNumber() && override.asInt() > 0) {
                    return override.asInt();
                }
            }
        } catch (Exception _) {

        }
        return 25_000;
    }

    private static int maxMcpOutputChars() {
        long chars = (long) maxMcpOutputTokens() * 4L;
        return chars > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) chars;
    }

    private static int estimateMcpTokens(String text) {
        if (StringUtils.isEmpty(text)) return 0;
        return (text.length() + 3) / 4;
    }

    private static boolean largeOutputFilesEnabled() {
        String value = SubprocessEnvironment.get("ENABLE_MCP_LARGE_OUTPUT_FILES");
        if (value == null) return true;
        return !(Strings.CI.equals(value, "0") ||Strings.CI.equals( value, "false")
            ||Strings.CI.equals( value, "no") ||Strings.CI.equals( value, "off"));
    }

    private static String truncationMessage() {
        return "\n\n[OUTPUT TRUNCATED - exceeded " + maxMcpOutputTokens() + " token limit]\n\n"
            + "The tool output was truncated. If this MCP server provides pagination or filtering tools, use them to retrieve specific portions of the data. If pagination is not available, inform the user that you are working with truncated output and results may be incomplete.";
    }

    private void appendContent(JsonNode item, List<ContentBlock> blocks,
                               ArrayNode sdkPayload, ToolExecutionContext context) {
        String type = item.path("type").asText("");
        switch (type) {
            case "text" -> addText(item.path("text").asText(""), blocks, sdkPayload);
            case "image" -> {
                ImageResizer.ResizeResult resized = resizeImage(
                    item.path("data").asText(""), item.path("mimeType").asText("image/png"));
                ObjectNode source = JsonUtils.getMapper().createObjectNode();
                source.put("type", "base64");
                source.put("media_type", resized.mediaType());
                source.put("data", Base64.getEncoder().encodeToString(resized.buffer()));
                blocks.add(new ImageBlock(source));
                sdkPayload.addObject().put("type", "image").set("source", source);
            }
            case "audio" -> {
                McpOutputStorage.PersistResult persisted = persistBinary(
                    item.path("data").asText(""), item.path("mimeType").asText(null),
                    context);
                addText(persistedMessage(persisted, item.path("mimeType").asText(null),
                    "[Audio from " + toolInfo.serverId() + "] "), blocks, sdkPayload);
            }
            case "resource" -> appendResource(item.path("resource"), blocks, sdkPayload, context);
            case "resource_link" -> {
                StringBuilder text = new StringBuilder("[Resource link: ")
                    .append(item.path("name").asText(""))
                    .append("] ")
                    .append(item.path("uri").asText(""));
                if (item.hasNonNull("description") && !StringUtils.isBlank(item.path("description").asText())) {
                    text.append(" (").append(item.path("description").asText()).append(')');
                }
                addText(text.toString(), blocks, sdkPayload);
            }
            default -> {

                // unknown MCP content discriminators. Resource tools preserve
                // extension fields separately; this proxy follows the MCP
                // tool result contract and must not invent model-visible text.
            }
        }
    }

    private void appendResource(JsonNode resource, List<ContentBlock> blocks,
                                ArrayNode sdkPayload, ToolExecutionContext context) {
        if (resource == null || !resource.isObject()) return;
        String prefix = "[Resource from " + toolInfo.serverId() + " at "
            + resource.path("uri").asText("") + "] ";
        if (resource.hasNonNull("text")) {
            addText(prefix + resource.path("text").asText(""), blocks, sdkPayload);
        } else if (resource.hasNonNull("blob")) {
            String mime = resource.path("mimeType").asText(null);
            if (mime != null &&Strings.CS.startsWith( mime.toLowerCase(Locale.ROOT), "image/")) {
                ImageResizer.ResizeResult resized = resizeImage(
                    resource.path("blob").asText(""), mime);
                ObjectNode source = JsonUtils.getMapper().createObjectNode();
                source.put("type", "base64").put("media_type", resized.mediaType())
                    .put("data", Base64.getEncoder().encodeToString(resized.buffer()));

                // retains the source URI while the image remains multimodal.
                addText(prefix, blocks, sdkPayload);
                blocks.add(new ImageBlock(source));
                sdkPayload.addObject().put("type", "image").set("source", source);
            } else {
                McpOutputStorage.PersistResult persisted = persistBinary(
                    resource.path("blob").asText(""), mime, context);
                addText(persistedMessage(persisted, mime, prefix), blocks, sdkPayload);
            }
        }
    }

    private static ImageResizer.ResizeResult resizeImage(String base64, String mimeType) {
        try {
            return ImageResizer.maybeResizeAndDownsampleBase64(base64, mimeType);
        } catch (RuntimeException _) {
            // An undecodable image still follows the MCP content shape. Keep
            // the original bytes rather than converting an otherwise valid
            // result into a tool failure.
            byte[] raw;
            try {
                raw = Base64.getDecoder().decode(base64);
            } catch (IllegalArgumentException _) {
                raw = new byte[0];
            }
            return new ImageResizer.ResizeResult(raw,
                StringUtils.isBlank(mimeType) ? "image/png" : mimeType, null);
        }
    }

    private McpOutputStorage.PersistResult persistBinary(String base64, String mime,
                                                          ToolExecutionContext context) {
        return new McpBinaryResourceStorage().persistMcpToolBinary(
            base64, mime, toolInfo.serverId(), context);
    }

    private String persistedMessage(McpOutputStorage.PersistResult result,
                                    String mime, String prefix) {
        if (result.succeeded()) {
            return prefix + "Binary content (" + (mime == null ? "unknown type" : mime)
                + ", " + FormatUtils.formatFileSize(result.size())
                + ") saved to " + result.filepath();
        }
        return prefix + "Binary content (" + (mime == null ? "unknown type" : mime)
            + ") could not be saved to disk: " + result.error();
    }

    private ToolResult withMcpMetadata(ToolResult output, JsonNode result, Object data) {
        JsonNode meta = result.get("_meta");
        JsonNode structured = result.get("structuredContent");
        Map<String, Object> mcpMeta = new LinkedHashMap<>();
        if ((meta != null && !meta.isNull()) || (structured != null && !structured.isNull())) {
            if (meta != null && !meta.isNull()) {
                mcpMeta.put("_meta", JsonUtils.getMapper().convertValue(meta, Object.class));
            }
            if (structured != null && !structured.isNull()) {
                mcpMeta.put("structuredContent",
                    JsonUtils.getMapper().convertValue(structured, Object.class));
            }
        }
        return output.withToolUseResult(data)
            .withMcpMeta(mcpMeta.isEmpty()
                ? null : Collections.unmodifiableMap(mcpMeta));
    }

    private static String textPayload(ToolResult result) {
        if (result.content().size() == 1 && result.content().getFirst() instanceof TextBlock(
            String text1
        )) {
            return text1;
        }
        return result.content().toString();
    }

    private static ArrayNode blocksAsJson(List<ContentBlock> blocks) {
        ArrayNode payload = JsonUtils.getMapper().createArrayNode();
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock(String text1)) {
                payload.addObject().put("type", "text").put("text", text1);
            } else if (block instanceof ImageBlock(JsonNode source)) {
                payload.addObject().put("type", "image").set("source", source);
            }
        }
        return payload;
    }

    private void reportProgress(ToolExecutionContext context, String status, long elapsedMs) {
        if (context == null || context.progressSink() == ToolExecutionContext.ProgressSink.NOOP) return;
        if (Strings.CS.equalsAny(status, "completed", "failed")) {
            context.reportProgress(ToolExecutionContext.ProgressUpdate.builder()
                .progress(1.0)
                .toolUseId(context.toolUseId())
                .output(status)
                .elapsedSeconds(elapsedMs / 1000.0)
                .complete(true)
                .build());
            return;
        }
        context.reportProgress(ToolExecutionContext.ProgressUpdate.of(Strings.CS.equals(
            "completed", status) ? 1.0 : 0.0,
            "Running…",
            "mcp_progress", context.toolUseId(), null, status, null, 0, 0,
            elapsedMs / 1000.0, 0,Strings.CS.equals( "completed", status) ||Strings.CS.equals( "failed", status)));
    }

    private void reportProgress(ToolExecutionContext context, JsonNode progress) {
        if (context == null || context.progressSink() == ToolExecutionContext.ProgressSink.NOOP) return;
        double current = progress.path("progress").asDouble(0.0);
        double total = progress.path("total").asDouble(0.0);
        String message = progress.path("message").asText(
            "MCP progress: " + toolInfo.serverId() + "/" + toolInfo.name());
        context.reportProgress(ToolExecutionContext.ProgressUpdate.mcp(
            current, total > 0.0 ? total : null, message, false));
    }

    private static void addText(String text, List<ContentBlock> blocks,
                                ArrayNode sdkPayload) {
        blocks.add(new TextBlock(text));
        sdkPayload.addObject().put("type", "text").put("text", text);
    }

    private String firstErrorText(JsonNode result) {
        JsonNode content = result.get("content");
        if (content != null && content.isArray() && !content.isEmpty()) {
            JsonNode first = content.get(0);
            if (first != null && first.hasNonNull("text")) {
                return first.path("text").asText();
            }
        }
        if (result.hasNonNull("error")) return result.path("error").asText();
        return "Unknown error";
    }

    private String unexpectedResponseMessage() {
        return "MCP server \"" + toolInfo.serverId() + "\" tool \""
            + toolInfo.name() + "\": unexpected response format";
    }

    @Override
    public boolean isConcurrencySafe() {
        return isReadOnly();
    }

    @Override
    public boolean isReadOnly() {
        return toolInfo.annotations().path("readOnlyHint").asBoolean(false);
    }

    /**
     * Returns the underlying MCP tool info.
     */
    public McpToolInfo getToolInfo() {
        return toolInfo;
    }
}

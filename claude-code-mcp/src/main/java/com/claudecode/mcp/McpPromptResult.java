package com.claudecode.mcp;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.ImageBlock;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.imagestore.ImageResizer;
import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.text.FormatUtils;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * Response from a {@code prompts/get} JSON-RPC call: a rendered prompt as a
 * list of {@link PromptMessage} entries. Consumed by {@code McpPromptCommand}
 * to inject the messages into the current conversation as a user query.
 *
 * the SDK's
 * {@code GetPromptResult} shape plus
 * {@code transformResultContent}/{@code persistBlobToTextBlock}: the ordered
 * MCP message contents become one prompt command content-block list, images
 * are resized for API limits, and audio/non-image blobs are persisted to the
 * session tool-results directory.
 *
 * @param messages ordered list of prompt messages returned by the server
 */
public record McpPromptResult(List<PromptMessage> messages) {
    private static final Set<String> IMAGE_MIME_TYPES = Set.of(
        "image/jpeg", "image/png", "image/gif", "image/webp");

    public McpPromptResult {
        if (messages == null) messages = List.of();
    }

    /** Converts MCP prompt content using a session-scoped tool-results directory. */
    public List<ContentBlock> toContentBlocks(String serverName, Path toolResultsDir) {
        List<ContentBlock> out = new ArrayList<>();
        for (PromptMessage message : messages) {
            appendContent(out, message.content(), serverName, toolResultsDir);
        }
        return List.copyOf(out);
    }

    private static void appendContent(List<ContentBlock> out, JsonNode content,
                                      String serverName, Path toolResultsDir) {
        if (content == null || content.isNull()) return;
        if (content.isTextual()) {
            out.add(new TextBlock(content.asText()));
            return;
        }
        if (content.isArray()) {
            content.forEach(block -> appendContent(out, block, serverName, toolResultsDir));
            return;
        }
        if (!content.isObject()) return;
        switch (content.path("type").asText()) {
            case "text" -> out.add(new TextBlock(content.path("text").asText("")));
            case "audio" -> appendPersistedBlob(out, content.path("data").asText(""),
                content.path("mimeType").asText(null), serverName, toolResultsDir,
                "[Audio from " + serverName + "] ");
            case "image" -> appendImage(out, content.path("data").asText(""),
                content.path("mimeType").asText("image/png"));
            case "resource" -> appendResource(out, content.path("resource"), serverName,
                toolResultsDir);
            case "resource_link" -> {
                String name = content.path("name").asText("");
                String uri = content.path("uri").asText("");
                String description = content.path("description").asText("");
                String text = "[Resource link: " + name + "] " + uri
                    + (StringUtils.isBlank(description) ? "" : " (" + description + ")");
                out.add(new TextBlock(text));
            }
            default -> {  }
        }
    }

    private static void appendResource(List<ContentBlock> out, JsonNode resource,
                                       String serverName, Path toolResultsDir) {
        String uri = resource.path("uri").asText("");
        String prefix = "[Resource from " + serverName + " at " + uri + "] ";
        if (resource.has("text")) {
            out.add(new TextBlock(prefix + resource.path("text").asText("")));
            return;
        }
        String mimeType = resource.path("mimeType").asText("");
        if (!resource.has("blob")) return;
        if (IMAGE_MIME_TYPES.contains(mimeType)) {
            out.add(new TextBlock(prefix));
            appendImage(out, resource.path("blob").asText(""), mimeType);
        } else {
            appendPersistedBlob(out, resource.path("blob").asText(""),
                StringUtils.isBlank(mimeType) ? null : mimeType, serverName, toolResultsDir, prefix);
        }
    }

    private static void appendImage(List<ContentBlock> out, String base64, String mimeType) {
        ImageResizer.ResizeResult resized = ImageResizer.maybeResizeAndDownsampleBase64(
            base64, mimeType);
        ObjectNode source = JsonUtils.getMapper().createObjectNode();
        source.put("type", "base64");
        source.put("media_type", resized.mediaType());
        source.put("data", Base64.getEncoder().encodeToString(resized.buffer()));
        out.add(new ImageBlock(source));
    }

    private static void appendPersistedBlob(List<ContentBlock> out, String base64,
                                            String mimeType, String serverName,
                                            Path toolResultsDir, String sourceDescription) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException _) {
            out.add(new TextBlock(sourceDescription + "Binary content ("
                + displayMimeType(mimeType) + ", 0 bytes) could not be saved to disk: "
                + "invalid base64 data"));
            return;
        }

        Path directory = toolResultsDir != null
            ? toolResultsDir
            : Path.of(System.getProperty("java.io.tmpdir"), "claude-code-tool-results");
        String id = "mcp-" + McpNameNormalizer.normalize(serverName) + "-blob-"
            + System.currentTimeMillis() + "-" + McpOutputStorage.randomBase36Suffix();
        McpOutputStorage.PersistResult persisted = McpOutputStorage.persistBinaryContent(
            directory, bytes, mimeType, id);
        if (persisted.succeeded()) {
            out.add(new TextBlock(sourceDescription + "Binary content ("
                + displayMimeType(mimeType) + ", " + FormatUtils.formatFileSize(bytes.length)
                + ") saved to " + persisted.filepath()));
        } else {
            out.add(new TextBlock(sourceDescription + "Binary content ("
                + displayMimeType(mimeType) + ", " + bytes.length
                + " bytes) could not be saved to disk: " + persisted.error()));
        }
    }

    private static String displayMimeType(String mimeType) {
        return StringUtils.isBlank(mimeType) ? "unknown type" : mimeType;
    }

    /**
     * One entry in the {@link #messages} list.
     *
     * @param role    {@code "user"} or {@code "assistant"}
     * @param content raw JSON content block — kept as {@link JsonNode} so
     *                {@link #toContentBlocks(String, Path)} can dispatch nested
     *                MCP union variants without a pre-emptive union parse
     */
    public record PromptMessage(String role, JsonNode content) {}

    /**
     * Parses a {@code prompts/get} JSON-RPC {@code result} node into an
     * {@link McpPromptResult}. Missing / non-array {@code messages} field
     * yields an empty result rather than an exception.
     */
    public static McpPromptResult fromJson(JsonNode result) {
        if (result == null) return new McpPromptResult(List.of());
        JsonNode messagesNode = result.get("messages");
        if (messagesNode == null || !messagesNode.isArray()) return new McpPromptResult(List.of());
        List<PromptMessage> out = new ArrayList<>(messagesNode.size());
        for (JsonNode m : messagesNode) {
            String role = m.path("role").asText("user");
            JsonNode content = m.get("content");
            out.add(new PromptMessage(role, content));
        }
        return new McpPromptResult(List.copyOf(out));
    }
}

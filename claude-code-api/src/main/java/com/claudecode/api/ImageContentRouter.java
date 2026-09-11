package com.claudecode.api;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.message.TextBlock;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Routes image content blocks away from text-only endpoints.
 *
 * <p>When the request's target endpoint is configured {@code multimodal: false} and
 * the request carries image blocks, each image is first sent (alone) to the
 * configured image-capable model, whose textual description replaces the image
 * block on the wire. This prevents the text-only endpoint's 400
 * ("Model only supports text input") while keeping the visual content available
 * to the main model as text.
 *
 * <p>Image blocks are recognized both at the top level of a message's content
 * array and nested inside {@code tool_result.content} (Read and MCP tools return
 * images there), in both Map and Jackson {@code JsonNode} shapes.
 *
 * <p>The router is deliberately conservative: any failure while captioning leaves
 * the original image block in place, so a misconfigured image model degrades to
 * the pre-existing behavior (the endpoint's own error) instead of failing the
 * whole turn locally.
 */
@Explanation("Image captioning fallback for text-only custom endpoints")
final class ImageContentRouter {

    private ImageContentRouter() {}

    /** Sentinel prefix marking a placeholder text block that replaces a captioned image. */
    static final String CAPTIONED_IMAGE_PREFIX = "[Image content, described by ";

    /**
     * Rewrites the request's messages when the target endpoint rejects images and an
     * image-capable model is configured.
     *
     * @return the rewritten messages, or the original list when no rewrite is needed
     *         or captioning failed
     */
    static List<CreateMessageRequest.RequestMessage> routeImages(
            List<CreateMessageRequest.RequestMessage> messages,
            boolean targetAcceptsImages,
            ImageEndpoint imageEndpoint,
            String imageModelName) {
        if (targetAcceptsImages || imageModelName == null || StringUtils.isBlank(imageModelName)) {
            return messages;
        }
        if (imageEndpoint == null || !imageEndpoint.acceptsImages()) return messages;
        if (!containsImageBlock(messages)) return messages;
        LlmClient imageClient = imageEndpoint.client();
        int totalImages = countImageBlocks(messages);
        CaptionCounter counter = new CaptionCounter(totalImages);
        List<CreateMessageRequest.RequestMessage> rewritten = new ArrayList<>(messages.size());
        boolean changed = false;
        for (CreateMessageRequest.RequestMessage message : messages) {
            CreateMessageRequest.RequestMessage converted =
                convertMessage(message, imageClient, imageModelName, counter);
            rewritten.add(converted);
            if (converted != message) changed = true;
        }
        return changed ? rewritten : messages;
    }

    /** The resolved image endpoint: its client and whether it takes images itself. */
    record ImageEndpoint(LlmClient client, boolean acceptsImages) {
        static ImageEndpoint textOnly(LlmClient client) {
            return new ImageEndpoint(client, false);
        }
    }

    /** Numbers captions across the whole request so the model can tell images apart. */
    private static final class CaptionCounter {
        private final int total;
        private int next;

        CaptionCounter(int total) { this.total = total; }

        String label() {
            next++;
            return total > 1 ? " [" + next + " of " + total + "]" : "";
        }
    }

    private static boolean containsImageBlock(List<CreateMessageRequest.RequestMessage> messages) {
        for (CreateMessageRequest.RequestMessage message : messages) {
            if (contentHasImage(message.content())) return true;
        }
        return false;
    }

    private static int countImageBlocks(List<CreateMessageRequest.RequestMessage> messages) {
        int count = 0;
        for (CreateMessageRequest.RequestMessage message : messages) {
            count += countImageBlocks(message.content());
        }
        return count;
    }

    /** Message-level conversion: swaps image blocks for caption text blocks. */
    private static CreateMessageRequest.RequestMessage convertMessage(
            CreateMessageRequest.RequestMessage message,
            LlmClient imageClient, String imageModelName, CaptionCounter counter) {
        Object content = message.content();
        Object converted = convertContent(content, imageClient, imageModelName, counter);
        if (converted != content) {
            return new CreateMessageRequest.RequestMessage(message.role(), converted);
        }
        return message;
    }

    /**
     * Converts one content value (a block list, a JSON block array, or a plain
     * string); returns the original reference when nothing was captioned.
     */
    private static Object convertContent(Object content,
            LlmClient imageClient, String imageModelName, CaptionCounter counter) {
        if (content instanceof List<?> blocks) {
            return convertListBlocks(blocks, imageClient, imageModelName, counter);
        }
        if (content instanceof JsonNode node && node.isArray()) {
            return convertJsonBlocks(node, imageClient, imageModelName, counter);
        }
        return content;
    }

    private static Object convertListBlocks(List<?> blocks,
            LlmClient imageClient, String imageModelName, CaptionCounter counter) {
        List<Object> converted = null;
        for (int i = 0; i < blocks.size(); i++) {
            Object block = blocks.get(i);
            Object replacement = replaceBlock(block, imageClient, imageModelName, counter);
            if (replacement != null) {
                if (converted == null) converted = new ArrayList<>(blocks);
                converted.set(i, replacement);
            }
        }
        return converted != null ? converted : blocks;
    }

    private static Object convertJsonBlocks(JsonNode array,
            LlmClient imageClient, String imageModelName, CaptionCounter counter) {
        List<Object> converted = null;
        for (int i = 0; i < array.size(); i++) {
            JsonNode block = array.get(i);
            Object replacement = replaceBlock(block, imageClient, imageModelName, counter);
            if (replacement != null) {
                if (converted == null) {
                    converted = new ArrayList<>(array.size());
                    for (int j = 0; j < i; j++) converted.add(array.get(j));
                }
                converted.add(replacement);
            } else if (converted != null) {
                converted.add(block);
            }
        }
        return converted != null ? converted : array;
    }

    /**
     * One block's replacement, or null to keep it. A top-level image block is
     * captioned directly; a tool_result's inner content array is converted
     * recursively so nested images are captioned too.
     */
    private static Object replaceBlock(Object block,
            LlmClient imageClient, String imageModelName, CaptionCounter counter) {
        if (block instanceof Map<?, ?> map) {
            if (isImageBlock(map)) {
                return captionBlock(map, imageClient, imageModelName, counter);
            }
            Object inner = map.get("content");
            if (isToolResultBlock(map) && inner instanceof List<?> innerBlocks) {
                Object convertedInner =
                    convertListBlocks(innerBlocks, imageClient, imageModelName, counter);
                if (convertedInner != inner) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        String key = String.valueOf(entry.getKey());
                        copy.put(key,
                            Strings.CS.equals("content", key) ? convertedInner : entry.getValue());
                    }
                    return copy;
                }
            }
            return null;
        }
        if (block instanceof JsonNode node && node.isObject()) {
            if (isImageBlock(node)) {
                return captionBlock(node, imageClient, imageModelName, counter);
            }
            JsonNode inner = node.path("content");
            if (Strings.CS.equals("tool_result", node.path("type").asText(null))
                    && inner.isArray()) {
                Object convertedInner =
                    convertJsonBlocks(inner, imageClient, imageModelName, counter);
                if (convertedInner != inner) {
                    return convertedInner;
                }
            }
            return null;
        }
        return null;
    }

    private static boolean contentHasImage(Object content) {
        if (content instanceof List<?> blocks) {
            for (Object block : blocks) {
                if (blockHasImage(block)) return true;
            }
        } else if (content instanceof JsonNode node && node.isArray()) {
            for (JsonNode child : node) {
                if (blockHasImage(child)) return true;
            }
        }
        return false;
    }

    private static int countImageBlocks(Object content) {
        int count = 0;
        if (content instanceof List<?> blocks) {
            for (Object block : blocks) {
                count += countBlockImages(block);
            }
        } else if (content instanceof JsonNode node && node.isArray()) {
            for (JsonNode child : node) {
                count += countBlockImages(child);
            }
        }
        return count;
    }

    private static boolean blockHasImage(Object block) {
        if (block instanceof Map<?, ?> map) {
            if (isImageBlock(map)) return true;
            Object inner = map.get("content");
            return isToolResultBlock(map) && contentHasImage(inner);
        }
        if (block instanceof JsonNode node && node.isObject()) {
            if (isImageBlock(node)) return true;
            JsonNode inner = node.path("content");
            return Strings.CS.equals("tool_result", node.path("type").asText(null))
                && contentHasImage(inner);
        }
        return false;
    }

    private static int countBlockImages(Object block) {
        if (block instanceof Map<?, ?> map) {
            if (isImageBlock(map)) return 1;
            Object inner = map.get("content");
            if (isToolResultBlock(map)) return countImageBlocks(inner);
            return 0;
        }
        if (block instanceof JsonNode node && node.isObject()) {
            if (isImageBlock(node)) return 1;
            JsonNode inner = node.path("content");
            if (Strings.CS.equals("tool_result", node.path("type").asText(null))) {
                return countImageBlocks(inner);
            }
        }
        return 0;
    }

    private static boolean isImageBlock(Map<?, ?> block) {
        return Strings.CS.equals("image", Objects.toString(block.get("type"), null));
    }

    private static boolean isImageBlock(JsonNode block) {
        return Strings.CS.equals("image", block.path("type").asText(null));
    }

    private static boolean isToolResultBlock(Map<?, ?> block) {
        return Strings.CS.equals("tool_result", Objects.toString(block.get("type"), null));
    }

    /**
     * Sends one image block to the image model and wraps its description in a
     * text block; returns {@code null} on any failure (keep the original block).
     */
    private static Object captionBlock(Object imageBlock,
                                       LlmClient imageClient, String imageModelName,
                                       CaptionCounter counter) {
        try {
            Object source = sourceOf(imageBlock);
            if (source == null) return null;
            CreateMessageRequest captionRequest = captionRequest(source, imageModelName);
            ApiMessage described = imageClient.createMessage(captionRequest, 60_000L);
            String text = extractText(described);
            if (StringUtils.isBlank(text)) return null;
            Map<String, Object> replacement = new LinkedHashMap<>();
            replacement.put("type", "text");
            replacement.put("text",
                CAPTIONED_IMAGE_PREFIX + imageModelName + "]" + counter.label() + ":\n"
                    + text.trim());
            return replacement;
        } catch (RuntimeException _) {
            return null;
        }
    }

    private static Object sourceOf(Object imageBlock) {
        if (imageBlock instanceof Map<?, ?> map) return map.get("source");
        if (imageBlock instanceof JsonNode node && node.isObject()) {
            JsonNode source = node.path("source");
            return source.isMissingNode() ? null : source;
        }
        return null;
    }

    private static CreateMessageRequest captionRequest(Object source, String imageModelName) {
        Map<String, Object> imagePart = new LinkedHashMap<>();
        imagePart.put("type", "image");
        imagePart.put("source", source);
        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("type", "text");
        textPart.put("text",
            "Describe this image factually and completely. Include all visible text, "
                + "UI layouts, diagrams, and notable details. Your description will be "
                + "read by another model in place of the image.");
        List<Object> content = List.of(imagePart, textPart);
        return CreateMessageRequest.builder()
            .model(imageModelName)
            .maxTokens(2048)
            .messages(List.of(new CreateMessageRequest.RequestMessage("user", content)))
            .stream(false)
            .build();
    }

    private static String extractText(ApiMessage message) {
        if (message == null || message.content() == null) return null;
        StringBuilder text = new StringBuilder();
        for (var block : message.content()) {
            if (block instanceof TextBlock textBlock && textBlock.text() != null) {
                if (!text.isEmpty()) text.append('\n');
                text.append(textBlock.text());
            }
        }
        return text.isEmpty() ? null : text.toString();
    }
}

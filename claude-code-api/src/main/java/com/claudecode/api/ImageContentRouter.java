package com.claudecode.api;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.message.TextBlock;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * <p>Captions are memoized in an {@link ImageCaptionCache} supplied by the caller
 * and keyed by image content, because the rewrite only applies to the wire copy:
 * the session history keeps the original image blocks, so an uncached router
 * re-describes the entire history's images on every single turn. Reuse buys both
 * the obvious latency saving and prompt-cache stability, since a re-described
 * image yields different words every turn and invalidates the main endpoint's
 * cache from the image's position onwards.
 *
 * <p><b>Sequence-label constraint.</b> Only the model's raw description is
 * cached, never the assembled block. The {@code [n of m]} suffix produced by
 * {@link CaptionCounter} numbers images by their order <em>within the current
 * request</em>; caching the assembled text would pin whichever suffix the image
 * happened to get the first time and make it wrong (and unstable) in later
 * requests. So on a cache hit the description is reused verbatim while the suffix
 * is regenerated from this request's counter.
 *
 * <p>The router is deliberately conservative: any failure while captioning leaves
 * the original image block in place, so a misconfigured image model degrades to
 * the pre-existing behavior (the endpoint's own error) instead of failing the
 * whole turn locally. Failures are never cached — the next turn retries.
 */
@Explanation("Image captioning fallback for text-only custom endpoints")
final class ImageContentRouter {

    private static final Logger log = LoggerFactory.getLogger(ImageContentRouter.class);

    private ImageContentRouter() {}

    /** Sentinel prefix marking a placeholder text block that replaces a captioned image. */
    static final String CAPTIONED_IMAGE_PREFIX = "[Image content, described by ";

    /**
     * Rewrites the request's messages when the target endpoint rejects images and an
     * image-capable model is configured.
     *
     * @param captionCache caption memo shared across requests; must not be null
     * @return the rewritten messages, or the original list when no rewrite is needed
     *         or captioning failed
     */
    static List<CreateMessageRequest.RequestMessage> routeImages(
            List<CreateMessageRequest.RequestMessage> messages,
            boolean targetAcceptsImages,
            ImageEndpoint imageEndpoint,
            String imageModelName,
            ImageCaptionCache captionCache) {
        if (targetAcceptsImages || imageModelName == null || StringUtils.isBlank(imageModelName)) {
            return messages;
        }
        if (imageEndpoint == null || !imageEndpoint.acceptsImages()) return messages;
        if (!containsImageBlock(messages)) return messages;
        int totalImages = countImageBlocks(messages);
        long routeStart = System.nanoTime();
        log.info("[image-diag] captioning {} image block(s) via {} before the text-only request",
            totalImages, imageModelName);
        CaptionContext context = new CaptionContext(imageEndpoint.client(), imageModelName,
            Objects.requireNonNull(captionCache, "captionCache"),
            new CaptionCounter(totalImages));
        List<CreateMessageRequest.RequestMessage> rewritten = new ArrayList<>(messages.size());
        boolean changed = false;
        for (CreateMessageRequest.RequestMessage message : messages) {
            CreateMessageRequest.RequestMessage converted = convertMessage(message, context);
            rewritten.add(converted);
            if (converted != message) changed = true;
        }
        log.info("[image-diag] captioning done: images={} changed={} totalMs={}",
            totalImages, changed, (System.nanoTime() - routeStart) / 1_000_000L);
        return changed ? rewritten : messages;
    }

    /** The resolved image endpoint: its client and whether it takes images itself. */
    record ImageEndpoint(LlmClient client, boolean acceptsImages) {
        static ImageEndpoint textOnly(LlmClient client) {
            return new ImageEndpoint(client, false);
        }
    }

    /**
     * Everything one captioning pass needs: where to send images, how to label the
     * resulting blocks, and where previously described images are remembered. The
     * counter is request-scoped mutable state; the cache outlives the request.
     */
    private record CaptionContext(
        LlmClient client, String modelName, ImageCaptionCache cache, CaptionCounter counter) {}

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
            CreateMessageRequest.RequestMessage message, CaptionContext context) {
        Object content = message.content();
        Object converted = convertContent(content, context);
        if (converted != content) {
            return new CreateMessageRequest.RequestMessage(message.role(), converted);
        }
        return message;
    }

    /**
     * Converts one content value (a block list, a JSON block array, or a plain
     * string); returns the original reference when nothing was captioned.
     */
    private static Object convertContent(Object content, CaptionContext context) {
        if (content instanceof List<?> blocks) {
            return convertListBlocks(blocks, context);
        }
        if (content instanceof JsonNode node && node.isArray()) {
            return convertJsonBlocks(node, context);
        }
        return content;
    }

    private static Object convertListBlocks(List<?> blocks, CaptionContext context) {
        List<Object> converted = null;
        for (int i = 0; i < blocks.size(); i++) {
            Object block = blocks.get(i);
            Object replacement = replaceBlock(block, context);
            if (replacement != null) {
                if (converted == null) converted = new ArrayList<>(blocks);
                converted.set(i, replacement);
            }
        }
        return converted != null ? converted : blocks;
    }

    private static Object convertJsonBlocks(JsonNode array, CaptionContext context) {
        List<Object> converted = null;
        for (int i = 0; i < array.size(); i++) {
            JsonNode block = array.get(i);
            Object replacement = replaceBlock(block, context);
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
    private static Object replaceBlock(Object block, CaptionContext context) {
        if (block instanceof Map<?, ?> map) {
            if (isImageBlock(map)) {
                return captionBlock(map, context);
            }
            Object inner = map.get("content");
            if (isToolResultBlock(map) && inner instanceof List<?> innerBlocks) {
                Object convertedInner = convertListBlocks(innerBlocks, context);
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
                return captionBlock(node, context);
            }
            JsonNode inner = node.path("content");
            if (Strings.CS.equals("tool_result", node.path("type").asText(null))
                    && inner.isArray()) {
                Object convertedInner = convertJsonBlocks(inner, context);
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
     * Reuses this image's remembered description, or sends the image to the image
     * model once and remembers the result; returns {@code null} on any failure
     * (keep the original block, and do not remember the failure).
     */
    private static Object captionBlock(Object imageBlock, CaptionContext context) {
        long start = System.nanoTime();
        try {
            Object source = sourceOf(imageBlock);
            if (source == null) return null;
            String cacheKey = ImageCaptionCache.keyFor(source, context.modelName());
            String remembered = context.cache().get(cacheKey);
            if (remembered != null) {
                log.info("[image-diag] caption cache hit ({} chars) — image request skipped",
                    remembered.length());
                return captionTextBlock(remembered, context);
            }
            CreateMessageRequest captionRequest = captionRequest(source, context.modelName());
            ApiMessage described = context.client().createMessage(captionRequest, 60_000L);
            String text = extractText(described);
            if (StringUtils.isBlank(text)) {
                log.warn("[image-diag] caption returned no text after {}ms — keeping raw image",
                    (System.nanoTime() - start) / 1_000_000L);
                return null;
            }
            log.info("[image-diag] caption ok in {}ms ({} chars)",
                (System.nanoTime() - start) / 1_000_000L, text.length());
            String caption = text.trim();
            context.cache().put(cacheKey, caption);
            return captionTextBlock(caption, context);
        } catch (RuntimeException e) {
            log.warn("[image-diag] caption failed after {}ms — keeping raw image: {}",
                (System.nanoTime() - start) / 1_000_000L, e.toString());
            return null;
        }
    }

    /**
     * Assembles the placeholder text block. The sequence suffix is taken from this
     * request's counter — see the class comment: it must never be cached with the
     * description, or the same image would carry a stale position in later requests.
     */
    private static Map<String, Object> captionTextBlock(String caption, CaptionContext context) {
        Map<String, Object> replacement = new LinkedHashMap<>();
        replacement.put("type", "text");
        replacement.put("text",
            CAPTIONED_IMAGE_PREFIX + context.modelName() + "]" + context.counter().label() + ":\n"
                + caption);
        return replacement;
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

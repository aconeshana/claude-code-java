package com.claudecode.api;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.ImageBlock;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.util.List;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared lowering helpers for OpenAI protocol adapters. */
@Explanation("Shared OpenAI wire content lowering")
final class OpenAiWireSupport {
    private static final Set<String> IMAGE_MIMES = Set.of(
        "image/png", "image/jpeg", "image/gif", "image/webp");
    private static final int MAX_ENCODED_BYTES = 28 * 1024 * 1024;
    private static final int MAX_DECODED_BYTES = 20 * 1024 * 1024;
    private static final Pattern DATA_URL = Pattern.compile(
        "^data:([^;,]+);base64,([A-Za-z0-9+/]*={0,2})$", Pattern.DOTALL);

    private OpenAiWireSupport() {}

    static String imageUrl(JsonNode source) {
        if (source == null || source.isNull()) {
            throw new ApiException("OpenAI image content is missing source", 0);
        }
        String type = source.path("type").asText("");
        if (Strings.CS.equals("url", type)) {
            String url = source.path("url").asText("");
            if (!StringUtils.isBlank(url)) return url;
        }
        if (Strings.CS.equals("base64", type)) {
            String mediaType = source.path("media_type").asText("application/octet-stream")
                .toLowerCase(Locale.ROOT);
            String data = source.path("data").asText("");
            if (!IMAGE_MIMES.contains(mediaType)) {
                throw new ApiException("OpenAI does not support image media type " + mediaType, 0);
            }
            Matcher dataUrl = DATA_URL.matcher(data);
            if (Strings.CS.startsWith(data, "data:")) {
                if (!dataUrl.matches()) {
                    throw new ApiException("OpenAI image data URL must contain valid base64", 0);
                }
                if (!Strings.CS.equals(mediaType, dataUrl.group(1).toLowerCase(Locale.ROOT))) {
                    throw new ApiException("OpenAI image media type " + mediaType
                        + " does not match data URL type " + dataUrl.group(1), 0);
                }
                data = dataUrl.group(2);
            }
            validateBase64(data);
            return "data:" + mediaType + ";base64," + data;
        }
        throw new ApiException("OpenAI image content requires a URL or base64 source", 0);
    }

    private static void validateBase64(String data) {
        if (StringUtils.isEmpty(data) || data.length() > MAX_ENCODED_BYTES) {
            String detail = data != null && data.length() > MAX_ENCODED_BYTES
                ? " exceeds the " + MAX_ENCODED_BYTES + " byte encoded limit"
                : " must contain valid base64";
            throw new ApiException("OpenAI image" + detail, 0);
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(data);
        } catch (IllegalArgumentException e) {
            throw new ApiException("OpenAI image must contain valid base64", 0, e);
        }
        if (decoded.length > MAX_DECODED_BYTES) {
            throw new ApiException("OpenAI image exceeds the " + MAX_DECODED_BYTES
                + " byte decoded limit", 0);
        }
        if (!Base64.getEncoder().encodeToString(decoded).equals(data)) {
            throw new ApiException("OpenAI image must contain canonical base64", 0);
        }
    }

    static ObjectNode responsesText(String type, String text) {
        return JsonUtils.getMapper().createObjectNode().put("type", type).put("text", text);
    }

    static ObjectNode responsesImage(JsonNode source) {
        return JsonUtils.getMapper().createObjectNode()
            .put("type", "input_image")
            .put("image_url", imageUrl(source));
    }

    static ObjectNode chatText(String text) {
        return JsonUtils.getMapper().createObjectNode().put("type", "text").put("text", text);
    }

    static ObjectNode chatImage(JsonNode source) {
        ObjectNode part = JsonUtils.getMapper().createObjectNode().put("type", "image_url");
        part.putObject("image_url").put("url", imageUrl(source));
        return part;
    }

    static String textContent(List<ContentBlock> content) {
        if (content == null) return "";
        return content.stream()
            .filter(TextBlock.class::isInstance)
            .map(TextBlock.class::cast)
            .map(TextBlock::text)
            .reduce((left, right) -> left + "\n" + right)
            .orElse("");
    }

    static ArrayNode responsesToolContent(List<ContentBlock> content) {
        ArrayNode output = JsonUtils.getMapper().createArrayNode();
        if (content == null) return output;
        for (ContentBlock block : content) {
            if (block instanceof TextBlock(String text1)) {
                output.add(responsesText("input_text", text1));
            } else if (block instanceof ImageBlock(JsonNode source)) {
                output.add(responsesImage(source));
            } else {
                throw new ApiException("OpenAI Responses tool output does not support "
                    + block.getClass().getSimpleName(), 0);
            }
        }
        return output;
    }
}

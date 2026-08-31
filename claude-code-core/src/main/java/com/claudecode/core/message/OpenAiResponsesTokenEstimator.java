package com.claudecode.core.message;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.imageio.ImageIO;

/** Coarse token estimates for OpenAI Responses input items. */
@Explanation("Codex-compatible OpenAI Responses item token estimator")
public final class OpenAiResponsesTokenEstimator {
    private static final long BYTES_PER_TOKEN = 4;
    private static final long RESIZED_IMAGE_BYTES_ESTIMATE = 7_373;
    private static final int ORIGINAL_IMAGE_PATCH_SIZE = 32;
    private static final long ORIGINAL_IMAGE_MAX_PATCHES = 10_000;
    private static final double AUDIO_TOKENS_PER_SECOND = 10.0;

    private OpenAiResponsesTokenEstimator() {}

    public static long estimateMessageTokens(Message message) {
        long total = 0;
        for (ObjectNode item : OpenAiResponsesItemProjector.projectMessage(message)) {
            total = saturatedAdd(total, estimateItemTokens(item));
        }
        return total;
    }

    static long estimateItemTokens(JsonNode item) {
        return ceilDiv(modelVisibleBytes(item), BYTES_PER_TOKEN);
    }

    static long modelVisibleBytes(JsonNode item) {
        if (item == null || item.isNull()) return 0;
        String type = item.path("type").asText("");
        if (isEncryptedReasoningItem(type)
                && item.path("encrypted_content").isTextual()) {
            return estimateReasoningLength(utf8Length(item.path("encrypted_content").asText()));
        }
        long adjusted = JsonUtils.getMapper().valueToTree(item).toString()
            .getBytes(StandardCharsets.UTF_8).length;
        adjusted = adjustDataUrls(item, adjusted, "input_image", "image_url", true);
        adjusted = adjustDataUrls(item, adjusted, "input_audio", "audio_url", false);
        return adjustEncryptedFunctionOutputs(item, adjusted);
    }

    private static boolean isEncryptedReasoningItem(String type) {
        return Strings.CS.equalsAny(type,
            "reasoning", "compaction", "context_compaction", "compaction_summary");
    }

    private static long adjustDataUrls(
            JsonNode item, long raw, String itemType, String urlField, boolean image) {
        long adjusted = raw;
        for (JsonNode node : item.findParents("type")) {
            if (!Strings.CS.equals(itemType, node.path("type").asText(""))) continue;
            String url = node.path(urlField).asText("");
            DataUrl dataUrl = parseBase64DataUrl(url, image ? "image/" : "audio/");
            if (dataUrl == null) continue;
            long replacement = image
                ? imageReplacementBytes(url, node.path("detail").asText(""))
                : audioReplacementBytes(url);
            adjusted = saturatedAdd(Math.max(0, adjusted - utf8Length(dataUrl.payload())), replacement);
        }
        return adjusted;
    }

    private static long adjustEncryptedFunctionOutputs(JsonNode item, long raw) {
        if (!Strings.CS.equalsAny(item.path("type").asText(""),
                "function_call_output", "custom_tool_call_output", "agent_message")) {
            return raw;
        }
        long adjusted = raw;
        for (JsonNode encrypted : item.findValues("encrypted_content")) {
            if (!encrypted.isTextual()) continue;
            long bytes = utf8Length(encrypted.asText());
            adjusted = saturatedAdd(Math.max(0, adjusted - bytes),
                ceilDiv(saturatedMultiply(bytes, 9), 16));
        }
        return adjusted;
    }

    private static long imageReplacementBytes(String imageUrl, String detail) {
        if (!Strings.CS.equals("original", detail)) return RESIZED_IMAGE_BYTES_ESTIMATE;
        DataUrl dataUrl = parseBase64DataUrl(imageUrl, "image/");
        if (dataUrl == null) return RESIZED_IMAGE_BYTES_ESTIMATE;
        try {
            byte[] bytes = Base64.getDecoder().decode(dataUrl.payload());
            ImageDimensions dimensions = imageDimensions(bytes);
            if (dimensions == null) return RESIZED_IMAGE_BYTES_ESTIMATE;
            long wide = ceilDiv(dimensions.width(), ORIGINAL_IMAGE_PATCH_SIZE);
            long high = ceilDiv(dimensions.height(), ORIGINAL_IMAGE_PATCH_SIZE);
            return Math.min(ORIGINAL_IMAGE_MAX_PATCHES, saturatedMultiply(wide, high))
                * BYTES_PER_TOKEN;
        } catch (Exception _) {
            return RESIZED_IMAGE_BYTES_ESTIMATE;
        }
    }

    private static ImageDimensions imageDimensions(byte[] bytes) {
        if (bytes == null || bytes.length < 4) return null;
        if (bytes.length >= 24
                && unsigned(bytes[0]) == 0x89
                && asciiEquals(bytes, 1, "PNG")) {
            return dimensions(beInt(bytes, 16), beInt(bytes, 20));
        }
        if (bytes.length >= 10 && asciiEquals(bytes, 0, "GIF")) {
            return dimensions(leUnsignedShort(bytes, 6), leUnsignedShort(bytes, 8));
        }
        if (unsigned(bytes[0]) == 0xff
                && unsigned(bytes[1]) == 0xd8
                && unsigned(bytes[2]) == 0xff) {
            ImageDimensions jpeg = jpegDimensions(bytes);
            if (jpeg != null) return jpeg;
        }
        if (bytes.length >= 30
                && asciiEquals(bytes, 0, "RIFF")
                && asciiEquals(bytes, 8, "WEBP")) {
            if (asciiEquals(bytes, 12, "VP8 ")) {
                return dimensions(leUnsignedShort(bytes, 26) & 0x3fff,
                    leUnsignedShort(bytes, 28) & 0x3fff);
            }
            if (asciiEquals(bytes, 12, "VP8L")) {
                long bits = leUnsignedInt(bytes, 21);
                return dimensions((int) (bits & 0x3fff) + 1,
                    (int) ((bits >>> 14) & 0x3fff) + 1);
            }
            if (asciiEquals(bytes, 12, "VP8X")) {
                return dimensions(leUnsignedMedium(bytes, 24) + 1,
                    leUnsignedMedium(bytes, 27) + 1);
            }
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            return image != null ? dimensions(image.getWidth(), image.getHeight()) : null;
        } catch (Exception _) {
            return null;
        }
    }

    private static ImageDimensions jpegDimensions(byte[] bytes) {
        int offset = 2;
        while (offset + 9 < bytes.length) {
            if (unsigned(bytes[offset]) != 0xff) {
                offset++;
                continue;
            }
            int marker = unsigned(bytes[offset + 1]);
            if (marker == 0xff) {
                offset++;
                continue;
            }
            if (marker >= 0xc0 && marker <= 0xcf
                    && marker != 0xc4 && marker != 0xc8 && marker != 0xcc) {
                return dimensions(beUnsignedShort(bytes, offset + 7),
                    beUnsignedShort(bytes, offset + 5));
            }
            if ((marker >= 0xd0 && marker <= 0xd9) || marker == 0x01) {
                offset += 2;
                continue;
            }
            if (offset + 3 >= bytes.length) return null;
            int segmentLength = beUnsignedShort(bytes, offset + 2);
            if (segmentLength < 2) return null;
            offset += 2 + segmentLength;
        }
        return null;
    }

    private static ImageDimensions dimensions(int width, int height) {
        return width > 0 && height > 0 ? new ImageDimensions(width, height) : null;
    }

    private static long audioReplacementBytes(String audioUrl) {
        long tokens = estimateAudioTokenCount(audioUrl);
        return saturatedMultiply(tokens, BYTES_PER_TOKEN);
    }

    private static long estimateAudioTokenCount(String audioUrl) {
        Double seconds = wavDurationSeconds(audioUrl);
        if (seconds == null) return ceilDiv(utf8Length(audioUrl), BYTES_PER_TOKEN);
        double tokens = Math.ceil(seconds * AUDIO_TOKENS_PER_SECOND);
        return tokens >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) tokens;
    }

    private static Double wavDurationSeconds(String audioUrl) {
        DataUrl dataUrl = parseBase64DataUrl(audioUrl, "audio/");
        if (dataUrl == null || !isWavMime(dataUrl.mimeType())) return null;
        try {
            byte[] bytes = Base64.getDecoder().decode(dataUrl.payload());
            if (bytes.length < 44
                    || !asciiEquals(bytes, 0, "RIFF")
                    || !asciiEquals(bytes, 8, "WAVE")) return null;
            int sampleRate = leInt(bytes, 24);
            int byteRate = leInt(bytes, 28);
            int dataOffset = findWavData(bytes);
            if (sampleRate <= 0 || byteRate <= 0 || dataOffset < 0) return null;
            int dataLength = leInt(bytes, dataOffset + 4);
            if (dataLength < 0) return null;
            return (double) dataLength / byteRate;
        } catch (Exception _) {
            return null;
        }
    }

    private static int findWavData(byte[] bytes) {
        int offset = 12;
        while (offset + 8 <= bytes.length) {
            int size = leInt(bytes, offset + 4);
            if (size < 0) return -1;
            if (asciiEquals(bytes, offset, "data")) return offset;
            long next = (long) offset + 8 + size + (size & 1);
            if (next > bytes.length) return -1;
            offset = (int) next;
        }
        return -1;
    }

    private static boolean asciiEquals(byte[] bytes, int offset, String value) {
        if (offset < 0 || offset + value.length() > bytes.length) return false;
        for (int i = 0; i < value.length(); i++) {
            if (bytes[offset + i] != (byte) value.charAt(i)) return false;
        }
        return true;
    }

    private static int leInt(byte[] bytes, int offset) {
        if (offset < 0 || offset + 4 > bytes.length) return -1;
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static int beInt(byte[] bytes, int offset) {
        if (offset < 0 || offset + 4 > bytes.length) return -1;
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).getInt();
    }

    private static int unsigned(byte value) {
        return value & 0xff;
    }

    private static int leUnsignedShort(byte[] bytes, int offset) {
        if (offset < 0 || offset + 2 > bytes.length) return -1;
        return unsigned(bytes[offset]) | unsigned(bytes[offset + 1]) << 8;
    }

    private static int beUnsignedShort(byte[] bytes, int offset) {
        if (offset < 0 || offset + 2 > bytes.length) return -1;
        return unsigned(bytes[offset]) << 8 | unsigned(bytes[offset + 1]);
    }

    private static int leUnsignedMedium(byte[] bytes, int offset) {
        if (offset < 0 || offset + 3 > bytes.length) return -1;
        return unsigned(bytes[offset])
            | unsigned(bytes[offset + 1]) << 8
            | unsigned(bytes[offset + 2]) << 16;
    }

    private static long leUnsignedInt(byte[] bytes, int offset) {
        if (offset < 0 || offset + 4 > bytes.length) return -1;
        return Integer.toUnsignedLong(leInt(bytes, offset));
    }

    private static boolean isWavMime(String mime) {
        return Strings.CI.equalsAny(mime,
            "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave");
    }

    private static DataUrl parseBase64DataUrl(String url, String mimePrefix) {
        if (url == null || !Strings.CI.startsWith(url, "data:")) return null;
        int comma = url.indexOf(',');
        if (comma < 0) return null;
        String metadata = url.substring("data:".length(), comma);
        String[] parts = metadata.split(";");
        if (parts.length == 0 || !Strings.CI.startsWith(parts[0], mimePrefix)) return null;
        boolean base64 = false;
        for (int i = 1; i < parts.length; i++) {
            if (Strings.CI.equals("base64", parts[i])) {
                base64 = true;
                break;
            }
        }
        return base64 ? new DataUrl(parts[0], url.substring(comma + 1)) : null;
    }

    private static long estimateReasoningLength(long encodedLength) {
        return Math.max(0, saturatedMultiply(encodedLength, 3) / 4 - 650);
    }

    private static long utf8Length(String text) {
        if (StringUtils.isEmpty(text)) return 0;
        long bytes = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch <= 0x7f) bytes++;
            else if (ch <= 0x7ff) bytes += 2;
            else if (Character.isHighSurrogate(ch)
                    && i + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else bytes += 3;
        }
        return bytes;
    }

    private static long ceilDiv(long value, long divisor) {
        if (value <= 0) return 0;
        return value / divisor + (value % divisor == 0 ? 0 : 1);
    }

    private static long saturatedMultiply(long left, long right) {
        if (left <= 0 || right <= 0) return 0;
        if (left > Long.MAX_VALUE / right) return Long.MAX_VALUE;
        return left * right;
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    private record DataUrl(String mimeType, String payload) {}
    private record ImageDimensions(int width, int height) {}
}

package com.claudecode.core.message;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


class OpenAiResponsesTokenEstimatorTest {

    @Test
    void textOnlyItemUsesItsSerializedUtf8Size() throws Exception {
        ObjectNode item = messageWith(content("output_text", "Hello world"), "assistant");

        assertEquals(JsonUtils.getMapper().writeValueAsBytes(item).length,
            OpenAiResponsesTokenEstimator.modelVisibleBytes(item));
    }

    @Test
    void inlineImagePayloadDoesNotDominateEstimate() throws Exception {
        String payload = "A".repeat(100_000);
        ObjectNode image = JsonUtils.getMapper().createObjectNode()
            .put("type", "input_image")
            .put("image_url", "data:image/png;base64," + payload);
        ObjectNode item = messageWith(image, "user");
        long raw = JsonUtils.getMapper().writeValueAsBytes(item).length;

        long estimated = OpenAiResponsesTokenEstimator.modelVisibleBytes(item);

        assertEquals(raw - payload.length() + 7_373, estimated);
        assertTrue(estimated < raw);
    }

    @Test
    void inlineImagesAreAdjustedInsideFunctionAndCustomToolOutputs() throws Exception {
        for (String type : new String[] {"function_call_output", "custom_tool_call_output"}) {
            String payload = "B".repeat(50_000);
            ObjectNode image = JsonUtils.getMapper().createObjectNode()
                .put("type", "input_image")
                .put("image_url", "data:image/png;base64," + payload);
            ObjectNode item = JsonUtils.getMapper().createObjectNode();
            item.put("type", type);
            item.put("call_id", "call-image");
            item.putArray("output").add(image);
            long raw = JsonUtils.getMapper().writeValueAsBytes(item).length;

            assertEquals(raw - payload.length() + 7_373,
                OpenAiResponsesTokenEstimator.modelVisibleBytes(item), type);
        }
    }

    @Test
    void multipleInlineImagesApplyMultipleFixedCosts() throws Exception {
        String first = "D".repeat(100);
        String second = "E".repeat(200);
        ObjectNode item = JsonUtils.getMapper().createObjectNode();
        item.put("type", "message");
        item.put("role", "user");
        ArrayNode content = item.putArray("content");
        content.add(content("input_text", "images"));
        content.add(image("data:image/png;base64," + first));
        content.add(image("data:image/jpeg;base64," + second));
        long raw = JsonUtils.getMapper().writeValueAsBytes(item).length;

        assertEquals(raw - first.length() - second.length() + 2 * 7_373,
            OpenAiResponsesTokenEstimator.modelVisibleBytes(item));
    }

    @Test
    void onlyBase64ImageDataUrlsAreDiscounted() throws Exception {
        for (String url : new String[] {
            "https://example.com/foo.png",
            "data:image/svg+xml,<svg/>",
            "data:application/octet-stream;base64," + "C".repeat(4_096)
        }) {
            ObjectNode item = messageWith(image(url), "user");
            assertEquals(JsonUtils.getMapper().writeValueAsBytes(item).length,
                OpenAiResponsesTokenEstimator.modelVisibleBytes(item), url);
        }
    }

    @Test
    void mixedCaseDataUrlMarkersAreAdjusted() throws Exception {
        String payload = "F".repeat(1_024);
        ObjectNode item = messageWith(image("DATA:image/png;BASE64," + payload), "user");
        long raw = JsonUtils.getMapper().writeValueAsBytes(item).length;

        assertEquals(raw - payload.length() + 7_373,
            OpenAiResponsesTokenEstimator.modelVisibleBytes(item));
    }

    @Test
    void originalDetailImageUsesThirtyTwoPixelPatchCount() throws Exception {
        BufferedImage pixel = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(pixel, "png", encoded));
        String payload = Base64.getEncoder().encodeToString(encoded.toByteArray());
        ObjectNode image = JsonUtils.getMapper().createObjectNode()
            .put("type", "input_image")
            .put("image_url", "data:image/png;base64," + payload)
            .put("detail", "original");
        ObjectNode item = messageWith(image, "user");
        long raw = JsonUtils.getMapper().writeValueAsBytes(item).length;

        assertEquals(raw - payload.length() + 4,
            OpenAiResponsesTokenEstimator.modelVisibleBytes(item),
            "a 1x1 image occupies one 32px patch, represented as four estimated bytes");
    }

    @Test
    void originalDetailImagesScaleAndCapAtTenThousandPatches() throws Exception {
        assertOriginalImagePatchBytes(pngHeader(2_304, 864), "image/png", 7_776);
        assertOriginalImagePatchBytes(pngHeader(3_201, 3_201), "image/png", 40_000);
    }

    @Test
    void originalDetailWebpUsesHeaderDimensionsWhenImageIoHasNoDecoder() throws Exception {
        assertOriginalImagePatchBytes(webpVp8x(2_304, 864), "image/webp", 7_776);
    }

    @Test
    void wavAudioUsesTenTokensPerSecond() throws Exception {
        byte[] wav = pcmWav(801, 8_000);
        String payload = Base64.getEncoder().encodeToString(wav);
        ObjectNode audio = JsonUtils.getMapper().createObjectNode()
            .put("type", "input_audio")
            .put("audio_url", "data:audio/wav;base64," + payload);
        ObjectNode item = messageWith(audio, "user");
        long raw = JsonUtils.getMapper().writeValueAsBytes(item).length;

        assertEquals(raw - payload.length() + 8,
            OpenAiResponsesTokenEstimator.modelVisibleBytes(item),
            "801 samples at 8kHz slightly exceed 0.1s, so Codex rounds up to two tokens");
    }

    @Test
    void wavAudioIsAdjustedInsideFunctionAndCustomToolOutputs() throws Exception {
        for (String type : new String[] {"function_call_output", "custom_tool_call_output"}) {
            byte[] wav = pcmWav(800, 8_000);
            String payload = Base64.getEncoder().encodeToString(wav);
            ObjectNode audio = JsonUtils.getMapper().createObjectNode()
                .put("type", "input_audio")
                .put("audio_url", "data:audio/wav;base64," + payload);
            ObjectNode item = JsonUtils.getMapper().createObjectNode();
            item.put("type", type);
            item.put("call_id", "call-audio");
            item.putArray("output").add(audio);
            long raw = JsonUtils.getMapper().writeValueAsBytes(item).length;

            assertEquals(raw - payload.length() + 4,
                OpenAiResponsesTokenEstimator.modelVisibleBytes(item), type);
        }
    }

    @Test
    void malformedAudioFallsBackToWholeUrlTokenEstimate() throws Exception {
        String payload = "A".repeat(100_000);
        String url = "data:audio/wav;base64," + payload;
        ObjectNode audio = JsonUtils.getMapper().createObjectNode()
            .put("type", "input_audio")
            .put("audio_url", url);
        ObjectNode item = messageWith(audio, "user");
        long raw = JsonUtils.getMapper().writeValueAsBytes(item).length;
        long replacement = ((url.getBytes(StandardCharsets.UTF_8).length + 3L) / 4) * 4;

        assertEquals(raw - payload.length() + replacement,
            OpenAiResponsesTokenEstimator.modelVisibleBytes(item));
    }

    @Test
    void encryptedReasoningUsesDecodedPayloadEstimate() {
        ObjectNode reasoning = JsonUtils.getMapper().createObjectNode();
        reasoning.put("type", "reasoning");
        reasoning.putArray("summary").addObject().put("type", "summary_text").put("text", "summary");
        reasoning.put("encrypted_content", "A".repeat(1_868));

        assertEquals(751, OpenAiResponsesTokenEstimator.modelVisibleBytes(reasoning));
        assertEquals(188, OpenAiResponsesTokenEstimator.estimateItemTokens(reasoning));
    }

    @Test
    void encryptedCompactionVariantsUseTheReasoningEstimate() {
        for (String type : new String[] {
            "compaction", "compaction_summary", "context_compaction"
        }) {
            ObjectNode item = JsonUtils.getMapper().createObjectNode();
            item.put("type", type);
            item.put("encrypted_content", "A".repeat(1_868));

            assertEquals(751, OpenAiResponsesTokenEstimator.modelVisibleBytes(item), type);
        }
    }

    @Test
    void shortEncryptedReasoningSaturatesAtZero() {
        ObjectNode reasoning = JsonUtils.getMapper().createObjectNode();
        reasoning.put("type", "reasoning");
        reasoning.put("encrypted_content", "A".repeat(100));

        assertEquals(0, OpenAiResponsesTokenEstimator.modelVisibleBytes(reasoning));
        assertEquals(0, OpenAiResponsesTokenEstimator.estimateItemTokens(reasoning));
    }

    @Test
    void encryptedFunctionOutputUsesPlaintextByteEstimate() throws Exception {
        String encrypted = "A".repeat(1_868);
        ObjectNode item = JsonUtils.getMapper().createObjectNode();
        item.put("type", "function_call_output");
        item.put("call_id", "call-encrypted");
        item.putArray("output").addObject()
            .put("type", "encrypted_content")
            .put("encrypted_content", encrypted);
        long raw = JsonUtils.getMapper().writeValueAsBytes(item).length;

        assertEquals(raw - encrypted.length() + 1_051,
            OpenAiResponsesTokenEstimator.modelVisibleBytes(item));
    }

    @Test
    void customAndAgentOutputsUseEncryptedPlaintextEstimate() throws Exception {
        for (String type : new String[] {"custom_tool_call_output", "agent_message"}) {
            String encrypted = "A".repeat(1_868);
            ObjectNode item = JsonUtils.getMapper().createObjectNode();
            item.put("type", type);
            item.putArray("content").addObject()
                .put("type", "encrypted_content")
                .put("encrypted_content", encrypted);
            long raw = JsonUtils.getMapper().writeValueAsBytes(item).length;

            assertEquals(raw - encrypted.length() + 1_051,
                OpenAiResponsesTokenEstimator.modelVisibleBytes(item), type);
        }
    }

    private static ObjectNode content(String type, String text) {
        return JsonUtils.getMapper().createObjectNode().put("type", type).put("text", text);
    }

    private static ObjectNode image(String url) {
        return JsonUtils.getMapper().createObjectNode()
            .put("type", "input_image")
            .put("image_url", url);
    }

    private static ObjectNode messageWith(ObjectNode content, String role) {
        ObjectNode item = JsonUtils.getMapper().createObjectNode();
        item.put("type", "message");
        item.put("role", role);
        ArrayNode array = item.putArray("content");
        array.add(content);
        return item;
    }

    private static byte[] pcmWav(int sampleCount, int sampleRate) throws Exception {
        int padding = sampleCount % 2;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes("RIFF".getBytes(StandardCharsets.US_ASCII));
        output.writeBytes(le32(36 + sampleCount + padding));
        output.writeBytes("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        output.writeBytes(le32(16));
        output.writeBytes(le16(1));
        output.writeBytes(le16(1));
        output.writeBytes(le32(sampleRate));
        output.writeBytes(le32(sampleRate));
        output.writeBytes(le16(1));
        output.writeBytes(le16(8));
        output.writeBytes("data".getBytes(StandardCharsets.US_ASCII));
        output.writeBytes(le32(sampleCount));
        output.write(new byte[sampleCount + padding]);
        return output.toByteArray();
    }

    private static byte[] pngHeader(int width, int height) {
        byte[] bytes = new byte[24];
        bytes[0] = (byte) 0x89;
        System.arraycopy("PNG".getBytes(StandardCharsets.US_ASCII), 0, bytes, 1, 3);
        ByteBuffer.wrap(bytes, 16, 4).order(ByteOrder.BIG_ENDIAN).putInt(width);
        ByteBuffer.wrap(bytes, 20, 4).order(ByteOrder.BIG_ENDIAN).putInt(height);
        return bytes;
    }

    private static byte[] webpVp8x(int width, int height) {
        byte[] bytes = new byte[30];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, bytes, 0, 4);
        System.arraycopy("WEBPVP8X".getBytes(StandardCharsets.US_ASCII), 0, bytes, 8, 8);
        putLe24(bytes, 24, width - 1);
        putLe24(bytes, 27, height - 1);
        return bytes;
    }

    private static void putLe24(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
        bytes[offset + 2] = (byte) (value >>> 16);
    }

    private static void assertOriginalImagePatchBytes(
            byte[] bytes, String mimeType, long replacement) throws Exception {
        String payload = Base64.getEncoder().encodeToString(bytes);
        ObjectNode image = image("data:" + mimeType + ";base64," + payload)
            .put("detail", "original");
        ObjectNode item = messageWith(image, "user");
        long raw = JsonUtils.getMapper().writeValueAsBytes(item).length;

        assertEquals(raw - payload.length() + replacement,
            OpenAiResponsesTokenEstimator.modelVisibleBytes(item));
    }

    private static byte[] le16(int value) {
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) value).array();
    }

    private static byte[] le32(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }
}

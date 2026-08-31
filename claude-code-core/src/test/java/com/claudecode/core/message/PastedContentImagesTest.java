package com.claudecode.core.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link PastedContent#imagesFromMessage(UserMessage)} — the pure {@code UserMessage →
 * pasted-content} derivation lifted out of the UI layer.
 */
class PastedContentImagesTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode base64Source(String data) {
        return M.createObjectNode()
            .put("type", "base64")
            .put("data", data)
            .put("media_type", "image/png");
    }

    private static UserMessage withBlocks(List<ContentBlock> blocks, List<Integer> pasteIds) {
        return new UserMessage("u1", MessageContent.ofBlocks(blocks), pasteIds);
    }

    @Test
    void nullOrEmpty_returnsEmptyMap() {
        assertTrue(PastedContent.imagesFromMessage(null).isEmpty());
        UserMessage textOnly = new UserMessage("t", MessageContent.ofText("hi"));
        assertTrue(PastedContent.imagesFromMessage(textOnly).isEmpty());
    }

    @Test
    void mapsImagesByPasteId_skippingNonImageBlocks() {
        UserMessage msg = withBlocks(
            List.of(new ImageBlock(base64Source("AAA")),
                    new TextBlock("in between"),
                    new ImageBlock(base64Source("BBB"))),
            List.of(7, 9));

        Map<Integer, PastedContent> images = PastedContent.imagesFromMessage(msg);

        assertEquals(2, images.size());
        // The interleaved TextBlock must NOT consume a paste id — img #2 keeps id 9.
        assertEquals("AAA", images.get(7).content());
        assertEquals("BBB", images.get(9).content());
        assertEquals("image/png", images.get(7).mediaType());
        assertTrue(images.get(7).isImage());
    }

    @Test
    void withoutPasteIds_fallsBackToOneBasedIndex() {
        UserMessage msg = withBlocks(
            List.of(new ImageBlock(base64Source("AAA")),
                    new ImageBlock(base64Source("BBB"))),
            null);

        Map<Integer, PastedContent> images = PastedContent.imagesFromMessage(msg);

        assertEquals("AAA", images.get(1).content());
        assertEquals("BBB", images.get(2).content());
    }

    @Test
    void nonBase64Source_isSkipped() {
        JsonNode urlSource = M.createObjectNode().put("type", "url").put("url", "http://x/y.png");
        UserMessage msg = withBlocks(List.of(new ImageBlock(urlSource)), List.of(1));
        assertTrue(PastedContent.imagesFromMessage(msg).isEmpty());
    }
}

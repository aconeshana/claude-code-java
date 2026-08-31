package com.claudecode.mcp;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpPromptResultTest {

    @TempDir
    Path tempDir;

    @Test
    void audioIsPersistedToToolResultsWithMimeExtension() throws Exception {
        byte[] bytes = "audio".getBytes();
        ObjectNode audio = JsonUtils.getMapper().createObjectNode();
        audio.put("type", "audio");
        audio.put("mimeType", "audio/mpeg");
        audio.put("data", Base64.getEncoder().encodeToString(bytes));

        List<ContentBlock> blocks = result(audio).toContentBlocks("demo server", tempDir);

        TextBlock text = assertInstanceOf(TextBlock.class, blocks.getFirst());
        assertTrue(Strings.CS.startsWith(text.text(), "[Audio from demo server] Binary content (audio/mpeg, 5 bytes) saved to "));
        Path saved = Path.of(text.text().substring(text.text().lastIndexOf(" saved to ") + 10));
        assertTrue(saved.startsWith(tempDir));
        assertTrue(Strings.CS.startsWith(saved.getFileName().toString(),
            "mcp-demo_server-blob-"));
        assertTrue(Strings.CS.endsWith(saved.getFileName().toString(), ".mp3"));
        assertArrayEquals(bytes, Files.readAllBytes(saved));
    }

    @Test
    void nonImageResourceBlobIsPersistedWithResourcePrefix() throws Exception {
        byte[] bytes = "%PDF".getBytes();
        ObjectNode resource = JsonUtils.getMapper().createObjectNode();
        resource.put("uri", "file:///report.pdf");
        resource.put("mimeType", "application/pdf");
        resource.put("blob", Base64.getEncoder().encodeToString(bytes));
        ObjectNode content = JsonUtils.getMapper().createObjectNode();
        content.put("type", "resource");
        content.set("resource", resource);

        List<ContentBlock> blocks = result(content).toContentBlocks("reports", tempDir);

        TextBlock text = assertInstanceOf(TextBlock.class, blocks.getFirst());
        assertTrue(Strings.CS.startsWith(text.text(), "[Resource from reports at file:///report.pdf] Binary content (application/pdf, 4 bytes) saved to "));
        Path saved = Path.of(text.text().substring(text.text().lastIndexOf(" saved to ") + 10));
        assertTrue(Strings.CS.endsWith(saved.getFileName().toString(), ".pdf"));
        assertArrayEquals(bytes, Files.readAllBytes(saved));
    }

    @Test
    void malformedBase64ReturnsPersistenceErrorTextInsteadOfThrowing() {
        ObjectNode audio = JsonUtils.getMapper().createObjectNode();
        audio.put("type", "audio");
        audio.put("mimeType", "audio/wav");
        audio.put("data", "not-base64");

        List<ContentBlock> blocks = result(audio).toContentBlocks("broken", tempDir);

        TextBlock text = assertInstanceOf(TextBlock.class, blocks.getFirst());
        assertEquals("[Audio from broken] Binary content (audio/wav, 0 bytes) could not be saved to disk: invalid base64 data",
            text.text());
    }

    private static McpPromptResult result(ObjectNode content) {
        return new McpPromptResult(List.of(new McpPromptResult.PromptMessage("user", content)));
    }
}

package com.claudecode.core.lsp;

import com.claudecode.core.serialization.JsonUtils;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LspToolUseSummaryTest {

    @TempDir Path tempDir;

    private ObjectNode input(String operation, String filePath, Integer line, Integer character) {
        ObjectNode n = JsonUtils.getMapper().createObjectNode();
        n.put("operation", operation);
        if (filePath != null) n.put("filePath", filePath);
        if (line != null) n.put("line", line);
        if (character != null) n.put("character", character);
        return n;
    }

    @Test
    void hover_withSymbol() throws Exception {
        Path f = tempDir.resolve("Bar.java");
        Files.writeString(f, "int foo = 1;");
        Optional<String> s = LspToolUseSummary.format(input("hover", f.toString(), 1, 5));
        assertTrue(s.isPresent());
        assertEquals("operation: \"hover\", symbol: \"foo\", in: \"Bar.java\"", s.get());
    }

    @Test
    void hover_withoutSymbol_fallsBackToPosition() throws Exception {
        Path f = tempDir.resolve("Bar.java");
        Files.writeString(f, "   ");
        Optional<String> s = LspToolUseSummary.format(input("hover", f.toString(), 1, 2));
        assertTrue(s.isPresent());
        assertEquals("operation: \"hover\", file: \"Bar.java\", position: 1:2", s.get());
    }

    @Test
    void documentSymbol_showsOperationAndFile() throws Exception {
        Path f = tempDir.resolve("Bar.java");
        Files.writeString(f, "class Bar {}");
        Optional<String> s = LspToolUseSummary.format(input("documentSymbol", f.toString(), null, null));
        assertTrue(s.isPresent());
        assertEquals("operation: \"documentSymbol\", file: \"Bar.java\"", s.get());
    }

    @Test
    void missingOperation_empty() {
        ObjectNode n = JsonUtils.getMapper().createObjectNode();
        n.put("filePath", "x.java");
        assertFalse(LspToolUseSummary.format(n).isPresent());
    }

    @Test
    void nullInput_empty() {
        assertFalse(LspToolUseSummary.format((JsonNode) null).isPresent());
    }
}

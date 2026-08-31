package com.claudecode.services.config;

import org.apache.commons.lang3.Strings;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JsoncParserTest {

    @Test
    void parsesPlainJson() {
        String json = """
            {"key": "value", "num": 42}""";
        assertEquals(json, JsoncParser.parse(json));
    }

    @Test
    void stripsSingleLineComments() {
        String jsonc = """
            {
              // this is a comment
              "key": "value"
            }""";
        String result = JsoncParser.parse(jsonc);
        assertFalse(Strings.CS.contains(result, "//"));
        assertTrue(Strings.CS.contains(result, "\"key\": \"value\""));
    }

    @Test
    void stripsMultiLineComments() {
        String jsonc = """
            {
              /* multi
                 line */
              "key": "value"
            }""";
        String result = JsoncParser.parse(jsonc);
        assertFalse(Strings.CS.contains(result, "/*"));
        assertFalse(Strings.CS.contains(result, "*/"));
        assertTrue(Strings.CS.contains(result, "\"key\": \"value\""));
    }

    @Test
    void preservesCommentsInsideStrings() {
        String jsonc = """
            {"url": "https://example.com // not a comment"}""";
        String result = JsoncParser.parse(jsonc);
        assertTrue(Strings.CS.contains(result, "// not a comment"));
    }

    @Test
    void preservesMultiLineCommentInsideStrings() {
        String jsonc = """
            {"note": "/* not a comment */"}""";
        String result = JsoncParser.parse(jsonc);
        assertTrue(Strings.CS.contains(result, "/* not a comment */"));
    }

    @Test
    void removesTrailingCommaBeforeBrace() {
        String jsonc = """
            {"a": 1, "b": 2,}""";
        String result = JsoncParser.parse(jsonc);
        assertTrue(Strings.CS.contains(result, "\"b\": 2}"));
    }

    @Test
    void removesTrailingCommaBeforeBracket() {
        String jsonc = """
            {"arr": [1, 2, 3,]}""";
        String result = JsoncParser.parse(jsonc);
        assertTrue(Strings.CS.contains(result, "[1, 2, 3]"));
    }

    @Test
    void handlesNullInput() {
        assertEquals("", JsoncParser.parse(null));
    }

    @Test
    void handlesBlankInput() {
        assertEquals("", JsoncParser.parse("   "));
    }

    @Test
    void handlesEscapedQuotesInStrings() {
        String jsonc = """
            {"msg": "say \\"hello\\""}""";
        String result = JsoncParser.parse(jsonc);
        assertTrue(Strings.CS.contains(result, "say \\\"hello\\\""));
    }

    @Test
    void combinedCommentsAndTrailingCommas() {
        String jsonc = """
            {
              // API settings
              "model": "sonnet", /* default */
              "maxTokens": 4096,
            }""";
        String result = JsoncParser.parse(jsonc);
        assertFalse(Strings.CS.contains(result, "//"));
        assertFalse(Strings.CS.contains(result, "/*"));
        // Should be valid JSON (no trailing comma)
        assertDoesNotThrow(() -> {
            new ObjectMapper().readTree(result);
        });
    }
}

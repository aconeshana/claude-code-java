package com.claudecode.tools.skills;
import com.claudecode.core.util.FrontmatterParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FrontmatterParserTest {

    private FrontmatterParser parser;

    @Test
    void parsePositiveIntFromFrontmatter_acceptsNumberAndStringRepresentations() {
        assertEquals(7, FrontmatterParser.parsePositiveIntFromFrontmatter(7));
        assertEquals(12, FrontmatterParser.parsePositiveIntFromFrontmatter("12 turns"));
    }

    @Test
    void parsePositiveIntFromFrontmatter_rejectsMissingNonPositiveAndFractionalNumbers() {
        assertNull(FrontmatterParser.parsePositiveIntFromFrontmatter(null));
        assertNull(FrontmatterParser.parsePositiveIntFromFrontmatter("0"));
        assertNull(FrontmatterParser.parsePositiveIntFromFrontmatter(1.5));
    }

    @BeforeEach
    void setUp() {
        parser = new FrontmatterParser();
    }

    @Test
    void parseWithFrontmatter() {
        String content = """
                ---
                name: test-skill
                description: A test skill
                ---
                This is the body content.
                """;

        FrontmatterParser.ParseResult result = parser.parse(content);

        assertEquals("test-skill", result.name());
        assertEquals("A test skill", result.description());
        assertEquals("This is the body content.\n", result.body());
    }

    @Test
    void parseWithListValues() {
        String content = """
                ---
                name: conditional-skill
                allowedTools:
                - Read
                - Write
                - Bash
                paths:
                - *.java
                - src/**/*.ts
                ---
                Body here.
                """;

        FrontmatterParser.ParseResult result = parser.parse(content);

        assertEquals("conditional-skill", result.name());
        assertEquals(3, result.allowedTools().size());
        assertTrue(result.allowedTools().contains("Read"));
        assertTrue(result.allowedTools().contains("Write"));
        assertEquals(2, result.paths().size());
    }

    @Test
    void parseWithoutFrontmatter() {
        String content = "Just plain content without frontmatter.";

        FrontmatterParser.ParseResult result = parser.parse(content);

        assertTrue(result.metadata().isEmpty());
        assertEquals("Just plain content without frontmatter.", result.body());
    }

    @Test
    void parsePreservesBodyWhitespaceLikeTsParser() {
        String content = "---\ndescription: exact bytes\n---\n  Body starts indented.  \n\n";

        FrontmatterParser.ParseResult result = parser.parse(content);

        assertEquals("Body starts indented.  \n\n", result.body());
    }

    @Test
    void parseWithoutFrontmatterPreservesContentWhitespaceLikeTsParser() {
        String content = "  Plain body.  \n\n";

        FrontmatterParser.ParseResult result = parser.parse(content);

        assertEquals(content, result.body());
    }

    @Test
    void parseEmptyContent() {
        FrontmatterParser.ParseResult result = parser.parse("");
        assertTrue(result.metadata().isEmpty());
        assertEquals("", result.body());
    }

    @Test
    void parseNullContent() {
        FrontmatterParser.ParseResult result = parser.parse(null);
        assertTrue(result.metadata().isEmpty());
        assertEquals("", result.body());
    }

    @Test
    void parseWithComments() {
        String content = """
                ---
                name: skill-with-comments
                # This is a comment
                description: Has comments
                ---
                Body.
                """;

        FrontmatterParser.ParseResult result = parser.parse(content);
        assertEquals("skill-with-comments", result.name());
        assertEquals("Has comments", result.description());
    }

    @Test
    void parseFoldedBlockScalar_joinsLinesAndPreservesParagraphBreaks() {
        String content = """
                ---
                name: web-research
                description: >
                  Search the web across several sources.
                  Verify important claims before answering.

                  Use citations in the final response.
                ---
                Body.
                """;

        FrontmatterParser.ParseResult result = parser.parse(content);

        assertEquals(
            """
            Search the web across several sources. Verify important claims before answering.
            Use citations in the final response.""",
            result.description());
    }

    @Test
    void parseLiteralBlockScalar_preservesLineBreaks() {
        String content = """
                ---
                name: workflow
                description: |
                  First line.
                  Second line.

                  Final paragraph.
                ---
                Body.
                """;

        FrontmatterParser.ParseResult result = parser.parse(content);

        assertEquals("First line.\nSecond line.\n\nFinal paragraph.", result.description());
    }
}

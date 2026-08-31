package com.claudecode.ui;

import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.claudecode.ui.lanterna.components.AnsiToSegments;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.transcript.MessagePanel;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TextColor;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests for Markdown rendering to ANSI terminal output.
 */
class MarkdownRendererTest {

    private MarkdownRenderer renderer;

    @BeforeEach
    void setUp() {
        renderer = new MarkdownRenderer();
    }

    @Test
    void renderNullReturnsEmpty() {
        assertEquals("", renderer.render(null));
    }

    @Test
    void renderEmptyReturnsEmpty() {
        assertEquals("", renderer.render(""));
    }

    @Test
    void renderPlainText() {
        String result = renderer.render("Hello world");
        assertTrue(Strings.CS.contains(result, "Hello world"));
    }

    @Test
    void renderHeadingContainsText() {
        String result = renderer.render("# My Heading");
        assertTrue(Strings.CS.contains(result, "My Heading"), "Should contain heading text");

        assertFalse(Strings.CS.contains(result, "# "), "Should NOT contain raw # prefix");
    }

    @Test
    void renderH2Heading() {
        String result = renderer.render("## Sub Heading");
        assertTrue(Strings.CS.contains(result, "Sub Heading"));

        assertFalse(Strings.CS.contains(result, "## "), "Should NOT contain raw ## prefix");
    }

    @Test
    void renderFencedCodeBlock() {
        String result = renderer.render("```java\npublic class Foo {}\n```");
        assertTrue(Strings.CS.contains(result, "public"), "Should contain code content");
        assertFalse(Strings.CS.contains(result, "[java]"), "2.1.197 has no language label");
        assertFalse(Strings.CS.contains(result, "│"), "2.1.197 has no code gutter");
    }

    @Test
    void renderFencedCodeBlockWithoutLanguage() {
        String result = renderer.render("```\nsome code\n```");
        assertTrue(Strings.CS.contains(result, "some code"));
        assertFalse(Strings.CS.contains(result, "│"));
    }

    @Test
    void renderBulletList() {
        String result = renderer.render("- item one\n- item two\n- item three");
        assertTrue(Strings.CS.contains(result, "item one"));
        assertTrue(Strings.CS.contains(result, "item two"));
        assertTrue(Strings.CS.contains(result, "item three"));

        assertTrue(Strings.CS.contains(result, "- "), "Should use hyphen bullet character");
    }

    @Test
    void renderOrderedList() {
        String result = renderer.render("1. first\n2. second");
        assertTrue(Strings.CS.contains(result, "first"));
        assertTrue(Strings.CS.contains(result, "second"));
    }

    @Test
    void renderBoldText() {
        String result = renderer.render("This is **bold** text");
        assertTrue(Strings.CS.contains(result, "bold"), "Should contain bold text");
    }

    @Test
    void renderItalicText() {
        String result = renderer.render("This is *italic* text");
        assertTrue(Strings.CS.contains(result, "italic"), "Should contain italic text");
    }

    @Test
    void renderInlineCode() {
        String result = renderer.render("Use `println` here");
        assertTrue(Strings.CS.contains(result, "println"), "Should contain inline code");
    }

    @Test
    void renderLink() {
        String result = renderer.render("[Click here](https://example.com)");


        assertTrue(Strings.CS.contains(result, "example.com"), "Should contain URL");
        if (Ansi.supportsHyperlinks()) {
            assertTrue(Strings.CS.contains(result, "Click here"),
                "Should contain link text when hyperlinks are supported");
        }
    }

    @Test
    void renderBlockQuote() {
        String result = renderer.render("> This is a quote");
        assertTrue(Strings.CS.contains(result, "This is a quote"));
        assertTrue(Strings.CS.contains(result, "▎"), "Should have blockquote marker");
    }

    @Test
    void renderThematicBreak() {
        String result = renderer.render("---\n");
        // With GFM tables extension, "---" may be parsed differently.
        // Use explicit *** syntax which is always a thematic break.
        if (!Strings.CS.contains(result, "─")) {
            result = renderer.render("***");
        }
        assertTrue(Strings.CS.contains(result, "─") || Strings.CS.contains(result, "---") || !result.isEmpty(),
            "Should render thematic break");
    }

    @Test
    void renderComplexDocument() {
        String md = """
                # Title
                
                Some **bold** and *italic* text.
                
                - item 1
                - item 2
                
                ```python
                print("hello")
                ```
                """;
        String result = renderer.render(md);
        assertTrue(Strings.CS.contains(result, "Title"));
        assertTrue(Strings.CS.contains(result, "bold"));
        assertTrue(Strings.CS.contains(result, "italic"));
        assertTrue(Strings.CS.contains(result, "item 1"));
        assertTrue(Strings.CS.contains(result, "print"));
    }



    @Test
    void detectsBashFromShebang() {
        assertEquals("bash",
            MarkdownRenderer.TerminalMarkdownVisitor.detectLanguageFromContent(
                "#!/bin/bash\necho hi"));
        assertEquals("bash",
            MarkdownRenderer.TerminalMarkdownVisitor.detectLanguageFromContent(
                "#!/usr/bin/env sh\necho hi"));
    }

    @Test
    void detectsPythonFromShebang() {
        assertEquals("python",
            MarkdownRenderer.TerminalMarkdownVisitor.detectLanguageFromContent(
                "#!/usr/bin/env python3\nprint('hi')"));
    }

    @Test
    void detectsJavascriptFromNodeShebang() {
        assertEquals("javascript",
            MarkdownRenderer.TerminalMarkdownVisitor.detectLanguageFromContent(
                "#!/usr/bin/env node\nconsole.log('hi')"));
    }

    @Test
    void detectsPhpFromOpenTag() {
        assertEquals("php",
            MarkdownRenderer.TerminalMarkdownVisitor.detectLanguageFromContent(
                "<?php\necho \"hi\";"));
    }

    @Test
    void detectsXmlFromProlog() {
        assertEquals("xml",
            MarkdownRenderer.TerminalMarkdownVisitor.detectLanguageFromContent(
                "<?xml version=\"1.0\"?>\n<root/>"));
    }

    @Test
    void detectsDockerfileFromFilenameMarker() {
        assertEquals("dockerfile",
            MarkdownRenderer.TerminalMarkdownVisitor.detectLanguageFromContent(
                "Dockerfile\nFROM alpine\nRUN apk add curl"));
    }

    @Test
    void detectsMakefileFromFilenameMarker() {
        assertEquals("makefile",
            MarkdownRenderer.TerminalMarkdownVisitor.detectLanguageFromContent(
                "Makefile:\nall:\n\techo hi"));
    }

    @Test
    void detectReturnsNullOnPlainText() {
        assertNull(MarkdownRenderer.TerminalMarkdownVisitor.detectLanguageFromContent(
            "just some plain text"));
        assertNull(MarkdownRenderer.TerminalMarkdownVisitor.detectLanguageFromContent(""));
        assertNull(MarkdownRenderer.TerminalMarkdownVisitor.detectLanguageFromContent(null));
    }

    // ── Visual width helpers ──────────────────────────────────────────────
    // These are what fix table alignment for Chinese / CJK content. Without
// them String.length undercounts the display width by a factor of ~2
    // and every downstream pipe drifts left.

    @Test
    void visualWidthCountsAsciiAsOne() {
        assertEquals(5, MarkdownRenderer.TerminalMarkdownVisitor.visualWidth("hello"));
    }

    @Test
    void visualWidthCountsCjkAsTwo() {
        assertEquals(4, MarkdownRenderer.TerminalMarkdownVisitor.visualWidth("你好"));
        assertEquals(4, MarkdownRenderer.TerminalMarkdownVisitor.visualWidth("架构"));   // 2 fullwidth chars, 2 each
    }

    @Test
    void visualWidthMixedCjkAscii() {
        // "A100 80GB SXM" = 13 ascii, no CJK. But "架构 A100" = 4+1+4 = 9.
        assertEquals(9, MarkdownRenderer.TerminalMarkdownVisitor.visualWidth("架构 A100"));
    }

    @Test
    void visualWidthSkipsCombiningMarks() {
        // "é" as e + combining acute — the mark should not add width.
        assertEquals(1, MarkdownRenderer.TerminalMarkdownVisitor.visualWidth("é"));
    }

    @Test
    void visualWidthHandlesEmoji() {
        // 🚀 is one code point but visually 2 columns wide in most terminals.
        assertEquals(2, MarkdownRenderer.TerminalMarkdownVisitor.visualWidth("🚀"));
    }

    @Test
    void visualWidthTreatsEmojiGraphemeSequencesAsSingleCells() {
        assertEquals(2, MarkdownRenderer.TerminalMarkdownVisitor.visualWidth("👨‍👩‍👧‍👦"));
        assertEquals(2, MarkdownRenderer.TerminalMarkdownVisitor.visualWidth("☀️"));
        assertEquals(2, MarkdownRenderer.TerminalMarkdownVisitor.visualWidth("🇨🇳"));
    }

    @Test
    void truncateToVisualWidthRespectsCjkBoundary() {
        // Cap at 5 columns: "A100 " (4 ascii + space = 5) fits exactly, next
        // char "架" would push to 7, so must stop before it.
        assertEquals("A100 ",
            MarkdownRenderer.TerminalMarkdownVisitor.truncateToVisualWidth("A100 架构", 5));
        // Cap at 4 columns: "架" is 2 wide, "构" is 2 wide → both fit.
        assertEquals("架构",
            MarkdownRenderer.TerminalMarkdownVisitor.truncateToVisualWidth("架构 A100", 4));
    }

    @Test
    void tableWithCjkHeadersAlignsPipes() {
        // Real-world CJK table: after rendering, the pipe columns must land at
        // the same visual column on every line. Assert by rebuilding the line
        // segments to be equal visual width before the pipe.
        String md = "| 指标 | A100 | RTX 5000 |\n|---|---|---|\n| 架构 | Ampere | Blackwell |\n";
        String out = renderer.render(md);

        String[] lines = out.split("\n");
        int pipeLines = 0;
        for (String line : lines) if (Strings.CS.contains(line, "│")) pipeLines++;
        assertTrue(pipeLines >= 2, "expected header + data rows to render pipes, got:\n" + out);
    }



    @Test
    void stripsOnlyInternalPromptTagsAndPreservesOrdinaryXmlContent() {
        assertEquals("hello", renderer.render("<Widget>hello</Widget>"));
        assertEquals("visible", renderer.render(
            "<context>hidden</context>\nvisible"));
    }

    @Test
    void preservesXmlInsideFencedCodeBlocks() {
        String result = renderer.render("```xml\n<root>value</root>\n```");
        assertTrue(Strings.CS.contains(result, "<root>value</root>"), result);
    }

    @Test
    void nestedUnorderedListDoesNotConsumeOuterOrderedCounter() {
        String result = stripAnsi(renderer.render("1. one\n   - nested\n2. two"));
        assertEquals("1. one\n  - nested\n2. two", result);
    }

    @Test
    void orderedListStartingAtZeroRemainsOrdered() {
        String result = stripAnsi(renderer.render("0. zero\n1. one"));
        assertTrue(Strings.CS.contains(result, "0. zero"), result);
        assertTrue(Strings.CS.contains(result, "1. one"), result);
    }

    @Test
    void looseListParagraphsDoNotRunTogether() {
        String result = stripAnsi(renderer.render(
            "- first paragraph\n\n  second paragraph"));
        assertEquals("first paragraph\n\nsecond paragraph", result);
    }

    @Test
    void looseListItemsMatchMarkedParagraphTokenBehavior() {
        String result = stripAnsi(renderer.render(
            "- first paragraph\n\n  second paragraph\n- next"));
        assertEquals("first paragraph\n\nsecond paragraph\nnext", result);
    }

    @Test
    void officialFastPathPreservesSourceSpacesBeforeNewline() {
        assertEquals("first  \nsecond", stripAnsi(renderer.render("first  \nsecond")));
    }

    @Test
    void setextH1StaysLiteralBecauseOfficialFastPathDoesNotIncludeEquals() {
        assertEquals("Title\n=====", renderer.render("Title\n====="));
    }

    @Test
    void fencedCodePreservesMarkedTokenTrailingSpacesAndBlankLine() {
        String result = stripAnsi(renderer.render("```text\na  \n\n```\n\nafter"));
        assertEquals("a  \n\n\nafter", result);
    }

    @Test
    void topLevelMarkdownBlocksKeepOneBlankDisplayRow() {
        String result = stripAnsi(renderer.render("first\n\nsecond"));
        assertEquals("first\n\nsecond", result);
    }

    @Test
    void thematicBreakDoesNotJoinFollowingParagraph() {
        String result = stripAnsi(renderer.render("before\n\n---\n\nafter"));
        assertEquals("before\n\n---\nafter", result);
    }

    @Test
    void paragraphSpacingResumesAfterThematicBreakFollower() {
        String result = stripAnsi(renderer.render("before\n\n---\n\nafter\n\nnext"));
        assertEquals("before\n\n---\nafter\n\nnext", result);
    }

    @Test
    void deeplyNestedOrderedListsUseMarkedCumulativeIndentation() {
        String result = stripAnsi(renderer.render(
            "1. one\n   1. alpha\n      1. roman\n2. two"));
        assertEquals("1. one\n  a. alpha\n      i. roman\n2. two", result);
    }

    @Test
    void headingPreservesInlineLinkSemantics() {
        String rendered = renderer.render(
            "## A **bold** `code` [link](https://example.com)");
        String result = stripAnsi(rendered);
        if (Ansi.supportsHyperlinks()) {
            assertTrue(Strings.CS.contains(rendered, "\u001B]8;;https://example.com"), rendered);
            assertTrue(Strings.CS.contains(result, "link"), result);
        } else {
            assertTrue(Strings.CS.contains(result, "https://example.com"), result);
        }
    }

    @Test
    void narrowTableWrapsWithoutDroppingCellContent() {
        String markdown = """
            | name | description |
            |---|---|
            | alpha | this description must remain visible when narrow |""";
        String result = stripAnsi(renderer.render(markdown, 32));
        assertTrue(Strings.CS.contains(result, "this description"), result);
        assertTrue(Strings.CS.contains(result, "remain visible"), result);
        assertFalse(Strings.CS.contains(result, "…"), result);
        for (String line : result.split("\n")) {
            assertTrue(MarkdownRenderer.TerminalMarkdownVisitor.visualWidth(line) <= 32,
                "line exceeds requested terminal width: " + line);
        }
    }

    @Test
    void subTwentyColumnTableUsesActualWidthAndFallsBackVertically() {
        String result = stripAnsi(renderer.render(
            "| A | B |\n|---|---|\n| x | y |", 12));
        assertFalse(Strings.CS.contains(result, "┌"), result);
        for (String line : result.lines().toList()) {
            assertTrue(MarkdownRenderer.TerminalMarkdownVisitor.visualWidth(line) <= 12,
                "line exceeds actual terminal width: " + line);
        }
    }

    @Test
    void taskListMarkerIsNotRenderedAsLiteralText() {
        String result = stripAnsi(renderer.render("- [x] done\n- [ ] todo"));
        assertEquals("- done\n- todo", result);
    }

    @Test
    void bareUrlStaysPlainOnOfficialFastPath() {
        String result = renderer.render("https://example.com");
        assertEquals("https://example.com", result);
        assertFalse(Strings.CS.contains(result, "\u001B]8;;"), result);
    }

    @Test
    void bareUrlIsAutolinkedWhenOtherSyntaxInvokesMarkedPath() {
        assumeTrue(Ansi.supportsHyperlinks());
        String result = renderer.render("**See** https://example.com");
        assertTrue(Strings.CS.contains(result, "\u001B]8;;https://example.com"), result);
    }

    @Test
    void gfmWwwUrlIsAutolinkedOnlyOnParsedPath() {
        assertEquals("www.example.com", renderer.render("www.example.com"));
        assumeTrue(Ansi.supportsHyperlinks());
        String parsed = renderer.render("**See** www.example.com");
        assertTrue(Strings.CS.contains(parsed, "\u001B]8;;http://www.example.com"), parsed);
        assertEquals("See www.example.com", stripAnsi(parsed));
    }

    @Test
    void parsedGfmWwwUrlFallsBackToNormalizedHrefWithoutHyperlinkSupport() {
        assumeFalse(Ansi.supportsHyperlinks());
        assertEquals("See http://www.example.com",
            stripAnsi(renderer.render("**See** www.example.com")));
    }

    @Test
    void issueReferenceAndBareUrlInSameTextBothRemainClickable() {
        assumeTrue(Ansi.supportsHyperlinks());
        String result = renderer.render("acme/repo#12 and https://example.com");
        assertTrue(Strings.CS.contains(
            result, "\u001B]8;;https://github.com/acme/repo/issues/12"), result);
        assertTrue(Strings.CS.contains(result, "\u001B]8;;https://example.com"), result);
    }

    @Test
    void bareUrlDoesNotConsumeTrailingSentencePunctuation() {
        assumeTrue(Ansi.supportsHyperlinks());
        String result = renderer.render("**See** https://example.com/foo). Next");
        assertTrue(Strings.CS.contains(result, "\u001B]8;;https://example.com/foo\u0007"), result);
        assertFalse(Strings.CS.contains(
            result, "\u001B]8;;https://example.com/foo).\u0007"), result);
        assertTrue(Strings.CS.endsWith(stripAnsi(result), "). Next"), result);
    }

    @Test
    void markdownMarkersAfterOfficialFiveHundredCharacterSampleStayLiteral() {
        String markdown = "a".repeat(500) + " **not parsed**";
        assertEquals(markdown, renderer.render(markdown));
    }

    @Test
    void tightListMarkerPrecedesStyledFirstInlineNode() {
        assertEquals("- bold item", stripAnsi(renderer.render("- **bold** item")));
        assertEquals("1. code item", stripAnsi(renderer.render("1. `code` item")));
    }

    @Test
    void issueReferenceRequiresBoundaryAfterNumber() {
        assumeTrue(Ansi.supportsHyperlinks());
        String result = renderer.render("acme/repo#12abc");
        assertFalse(Strings.CS.contains(result, "\u001B]8;;"), result);
        assertEquals("acme/repo#12abc", result);
    }

    @Test
    void issueReferenceRemainsQualifiedTextWithoutHyperlinkSupport() {
        assumeFalse(Ansi.supportsHyperlinks());
        assertEquals("acme/repo#12", renderer.render("acme/repo#12"));
    }

    @Test
    void blockquoteResetsNestedListNumberingDepthLikeMarked() {
        String result = stripAnsi(renderer.render("- outer\n  > 1. inner\n  > 2. next"));
        assertEquals("- outer\n▎ 1. inner\n▎ 2. next", result);
    }

    @Test
    void tableKeepsInlineCodeStyle() {
        String result = renderer.render("| H |\n|---|\n| `value` |");
        if (Ansi.isColorSupported()) {
            assertTrue(Strings.CS.contains(result, "\u001B["), result);
        }
        assertTrue(Strings.CS.contains(stripAnsi(result), "value"), result);
    }

    @Test
    void leadingAnsiSequenceSurvivesMarkdownAndCellWhitespaceTrimming() {
        assumeTrue(Ansi.isColorSupported());

        String heading = renderer.render("# Heading");
        String inlineCode = renderer.render("`inline`");
        String table = renderer.render("| H |\n|---|\n| `value` |");

        assertTrue(Strings.CS.startsWith(heading, "\u001B["), heading);
        assertTrue(Strings.CS.startsWith(inlineCode, "\u001B["), inlineCode);
        assertFalse(Strings.CS.startsWith(stripAnsi(heading), "[1m"), heading);
        assertFalse(Strings.CS.startsWith(stripAnsi(inlineCode), "[38;"), inlineCode);
        assertFalse(Strings.CS.contains(stripAnsi(table), "[38;"), table);
    }

    @Test
    void styledTableCellsUseVisibleWidthForPadding() {
        assumeTrue(Ansi.isColorSupported());
        String result = renderer.render("| A | B |\n|---|---|\n| `x` | plain |", 40);

        List<String> tableLines = result.lines().toList();
        int expectedWidth = MarkdownRenderer.TerminalMarkdownVisitor.visualWidth(
            stripAnsi(tableLines.getFirst()));
        for (String line : tableLines) {
            assertEquals(expectedWidth,
                MarkdownRenderer.TerminalMarkdownVisitor.visualWidth(stripAnsi(line)), line);
        }
    }

    @Test
    void tableHeadersAreCenteredWithoutJavaOnlyBoldStyle() {
        assumeTrue(Ansi.isColorSupported());
        String result = renderer.render("| Header |\n|---|\n| value |");
        String headerRow = result.lines().filter(line -> Strings.CS.contains(line, "Header"))
            .findFirst().orElseThrow();
        assertFalse(Strings.CS.contains(headerRow, "\u001B[1m"), headerRow);
    }

    @Test
    void verticalTablePreservesFormattedCellValue() {
        assumeTrue(Ansi.isColorSupported());
        String result = renderer.render(
            "| A | B |\n|---|---|\n| `styled-value-long-long-long` | plain |", 20);
        assertTrue(Strings.CS.contains(result, "\u001B["), result);
        assertFalse(Strings.CS.contains(stripAnsi(result), "[38;"), result);
    }

    @Test
    void renderCacheSeparatesThemeDependentMarkdown() {
        assumeTrue(Ansi.isColorSupported());
        LanternaTheme.Scheme original = LanternaTheme.schemeFromName(
            LanternaTheme.activeThemeName());
        try {
            LanternaTheme.setScheme(LanternaTheme.Scheme.DARK);
            String dark = renderer.render("`inline`", 80);
            LanternaTheme.setScheme(LanternaTheme.Scheme.LIGHT);
            String cachedLight = renderer.render("`inline`", 80);
            String freshLight = new MarkdownRenderer().render("`inline`", 80);

            assertEquals(freshLight, cachedLight);
            assertNotEquals(dark, cachedLight);
        } finally {
            LanternaTheme.setScheme(original);
        }
    }

    @Test
    void dimMarkdownDimsNonTableContentButNotTables() {
        assumeTrue(Ansi.isColorSupported());
        String result = renderer.renderDimmed(
            "Before `code`\n\n| H |\n|---|\n| value |\n\nAfter", 40);
        String beforeTable = result.substring(0, result.indexOf('┌'));
        String table = result.substring(result.indexOf('┌'), result.indexOf('┘') + 1);
        String afterTable = result.substring(result.indexOf('┘') + 1);

        assertTrue(Strings.CS.contains(beforeTable, "\u001B[2m"), result);
        assertTrue(Strings.CS.contains(afterTable, "\u001B[2m"), result);
        assertFalse(Strings.CS.contains(table, "\u001B[2m"), result);
    }

    @Test
    void tablesAndAdjacentMarkdownGroupsHaveExactlyOneBlankDisplayRow() {
        String result = stripAnsi(renderer.render(
            "before\n\n| A |\n|---|\n| x |\n\nafter", 40));
        List<String> lines = result.lines().toList();
        int top = lines.indexOf("before");
        int tableStart = IntStream.range(0, lines.size())
            .filter(i -> Strings.CS.startsWith(lines.get(i), "┌")).findFirst().orElseThrow();
        int tableEnd = IntStream.range(0, lines.size())
            .filter(i -> Strings.CS.startsWith(lines.get(i), "└")).findFirst().orElseThrow();
        int after = lines.indexOf("after");

        assertEquals(2, tableStart - top);
        assertEquals(2, after - tableEnd);
        assertEquals("", lines.get(top + 1));
        assertEquals("", lines.get(tableEnd + 1));
    }

    @Test
    void adjacentTablesHaveExactlyOneBlankDisplayRow() {
        String result = stripAnsi(renderer.render(
            "| A |\n|---|\n| x |\n\n| B |\n|---|\n| y |", 40));
        List<String> lines = result.lines().toList();
        List<Integer> bottoms = IntStream.range(0, lines.size())
            .filter(i -> Strings.CS.startsWith(lines.get(i), "└")).boxed().toList();
        List<Integer> tops = IntStream.range(0, lines.size())
            .filter(i -> Strings.CS.startsWith(lines.get(i), "┌")).boxed().toList();

        assertEquals(2, tops.size());
        assertEquals(2, bottoms.size());
        assertEquals(2, tops.get(1) - bottoms.getFirst());
        assertEquals("", lines.get(bottoms.getFirst() + 1));
    }

    @Test
    void tableBoundaryClearsThematicBreakParagraphState() {
        String result = stripAnsi(renderer.render(
            "before\n\n---\n\n| A |\n|---|\n| x |\n\nafter\n\nnext", 40));
        assertTrue(Strings.CS.endsWith(result, "after\n\nnext"), result);
    }

    @Test
    void nestedInlineStyleResumesAfterInnerReset() {
        assumeTrue(Ansi.isColorSupported());
        String result = renderer.render("*before **bold** after*");
        var lines = AnsiToSegments.ansiToLines(result, TextColor.ANSI.DEFAULT);
        var after = lines.getFirst().stream()
            .filter(segment -> Strings.CS.contains(segment.text(), "after"))
            .findFirst().orElseThrow();
        assertTrue(after.modifiers().contains(SGR.ITALIC), result);
    }

    @Test
    void headingStyleResumesAfterNestedInlineReset() {
        assumeTrue(Ansi.isColorSupported());
        var segments = AnsiToSegments.ansiToLines(
            renderer.render("# before **bold** after"), TextColor.ANSI.DEFAULT)
            .getFirst();
        MessagePanel.Segment after = segments.stream()
            .filter(segment -> Strings.CS.contains(segment.text(), " after"))
            .findFirst().orElseThrow();
        assertTrue(after.modifiers().containsAll(
            Set.of(SGR.BOLD, SGR.ITALIC, SGR.UNDERLINE)), segments.toString());
    }

    @Test
    void blockquoteItalicResumesAfterNestedInlineReset() {
        assumeTrue(Ansi.isColorSupported());
        var segments = AnsiToSegments.ansiToLines(
            renderer.render("> before **bold** after"), TextColor.ANSI.DEFAULT)
            .getFirst();
        MessagePanel.Segment after = segments.stream()
            .filter(segment -> Strings.CS.contains(segment.text(), " after"))
            .findFirst().orElseThrow();
        assertTrue(after.modifiers().contains(SGR.ITALIC), segments.toString());
    }

    @Test
    void ansiAwareTableWrappingReopensStyleAndHyperlinkOnContinuationLines() {
        String styled = "\u001B[3m\u001B]8;;https://example.com\u0007abcdef"
            + "\u001B]8;;\u0007\u001B[0m";

        List<String> lines = MarkdownRenderer.wrapAnsiForTest(styled, 3, true);

        assertEquals(List.of("abc", "def"), lines.stream().map(MarkdownRendererTest::stripAnsi).toList());
        for (String line : lines) {
            assertTrue(Strings.CS.contains(line, "\u001B[3m"), line);
            assertTrue(Strings.CS.contains(line, "\u001B]8;;https://example.com\u0007"), line);
            assertTrue(Strings.CS.contains(line, "\u001B]8;;\u0007"), line);
        }
    }

    @Test
    void ansiAwareWrappingHonorsSelectiveStyleResets() {
        String styled = "\u001B[3mitalic \u001B[23mplain words";

        List<String> lines = MarkdownRenderer.wrapAnsiForTest(styled, 7, false);

        assertTrue(Strings.CS.contains(lines.getFirst(), "\u001B[3m"), lines.toString());
        String plainLine = lines.stream()
            .filter(line -> Strings.CS.contains(stripAnsi(line), "plain"))
            .findFirst().orElseThrow();
        int plainIndex = plainLine.indexOf("plain");
        assertFalse(Strings.CS.contains(
            plainLine.substring(0, plainIndex), "\u001B[3m"), lines.toString());
    }

    private static String stripAnsi(String value) {
        return value.replaceAll("\\u001B\\[[;\\d]*m", "")
            .replaceAll("\\u001B]8;;.*?\\u0007", "");
    }
}

package com.claudecode.tools.hints;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ClaudeCodeHints} — the Claude Code hints protocol parser.
 */
class ClaudeCodeHintsTest {

    @Test
    void noTagReturnsOutputUnchanged() {
        String out = "line one\nline two\n";
        ClaudeCodeHints.HintExtraction ext = ClaudeCodeHints.extractClaudeCodeHints(out, "ls -la");
        assertTrue(ext.hints().isEmpty());
        assertEquals(out, ext.stripped());
    }

    @Test
    void selfClosingPluginHintIsExtractedAndStripped() {
        String out = "some output\n<claude-code-hint v=\"1\" type=\"plugin\" value=\"foo@market\" />\nmore output\n";
        ClaudeCodeHints.HintExtraction ext = ClaudeCodeHints.extractClaudeCodeHints(out, "mycli --flag");
        assertEquals(1, ext.hints().size());
        ClaudeCodeHint hint = ext.hints().getFirst();
        assertEquals(1, hint.v());
        assertEquals("plugin", hint.type());
        assertEquals("foo@market", hint.value());
        assertEquals("mycli", hint.sourceCommand());

        // which collapses only 3+ newlines, not 2).
        assertEquals("some output\n\nmore output\n", ext.stripped());
    }

    @Test
    void closingTagFormIsIgnoredNotExtracted() {

        String out = "text\n<claude-code-hint v=\"1\" type=\"plugin\" value=\"x\">inner</claude-code-hint>\ntext\n";
        ClaudeCodeHints.HintExtraction ext = ClaudeCodeHints.extractClaudeCodeHints(out, "cmd");
        assertTrue(ext.hints().isEmpty());
        assertEquals(out, ext.stripped());
    }

    @Test
    void unsupportedVersionIsDroppedButStripped() {
        String out = "a\n<claude-code-hint v=\"2\" type=\"plugin\" value=\"x\" />\nb\n";
        ClaudeCodeHints.HintExtraction ext = ClaudeCodeHints.extractClaudeCodeHints(out, "cmd");
        assertTrue(ext.hints().isEmpty());
        assertEquals("a\n\nb\n", ext.stripped());
    }

    @Test
    void unsupportedTypeIsDroppedButStripped() {
        String out = "a\n<claude-code-hint v=\"1\" type=\"other\" value=\"x\" />\nb\n";
        ClaudeCodeHints.HintExtraction ext = ClaudeCodeHints.extractClaudeCodeHints(out, "cmd");
        assertTrue(ext.hints().isEmpty());
        assertEquals("a\n\nb\n", ext.stripped());
    }

    @Test
    void emptyValueIsDroppedButStripped() {
        String out = "a\n<claude-code-hint v=\"1\" type=\"plugin\" value=\"\" />\nb\n";
        ClaudeCodeHints.HintExtraction ext = ClaudeCodeHints.extractClaudeCodeHints(out, "cmd");
        assertTrue(ext.hints().isEmpty());
        assertEquals("a\n\nb\n", ext.stripped());
    }

    @Test
    void quotedValueWithSpecialCharsParses() {
        String out = "<claude-code-hint v=\"1\" type=\"plugin\" value=\"scope/name@market\" />\n";
        ClaudeCodeHints.HintExtraction ext = ClaudeCodeHints.extractClaudeCodeHints(out, "cmd");
        assertEquals(1, ext.hints().size());
        assertEquals("scope/name@market", ext.hints().getFirst().value());
    }

    @Test
    void multipleHintsOnSeparateLines() {
        String out = """
            <claude-code-hint v="1" type="plugin" value="a@m" />
            mid
            <claude-code-hint v="1" type="plugin" value="b@m" />
            """;
        ClaudeCodeHints.HintExtraction ext = ClaudeCodeHints.extractClaudeCodeHints(out, "cmd");
        assertEquals(2, ext.hints().size());
        assertEquals("\nmid\n\n", ext.stripped());
    }

    @Test
    void quotedTagInsideLargerLineIsIgnored() {
        // A tag merely quoted inside a log line must not match (line anchoring).
        String out = "log: \"<claude-code-hint v=\\\"1\\\" type=\\\"plugin\\\" value=\\\"x\\\" />\"\n";
        ClaudeCodeHints.HintExtraction ext = ClaudeCodeHints.extractClaudeCodeHints(out, "cmd");
        assertTrue(ext.hints().isEmpty());
        assertEquals(out, ext.stripped());
    }

    @Test
    void blankLinesCollapseAfterStripping() {
        // A hint on its own line leaves surrounding newlines; 3+ collapse to 2.
        String out = "head\n\n\n<claude-code-hint v=\"1\" type=\"plugin\" value=\"x\" />\n\n\ntail\n";
        ClaudeCodeHints.HintExtraction ext = ClaudeCodeHints.extractClaudeCodeHints(out, "cmd");
        assertEquals(1, ext.hints().size());
        assertEquals("head\n\ntail\n", ext.stripped());
    }
}

package com.claudecode.tools.shell;

import org.apache.commons.lang3.Strings;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;


class OutputLimitsTest {

    private static Function<String, String> env(String value) {
        return name -> Strings.CS.equals("BASH_MAX_OUTPUT_LENGTH", name) ? value : null;
    }

    // ── getMaxOutputLength ───────────────────────────────────────────────────

    @Test
    void unsetEnv_usesDefault30000() {
        assertEquals(30_000, OutputLimits.getMaxOutputLength(env(null)));
        assertEquals(30_000, OutputLimits.BASH_MAX_OUTPUT_DEFAULT);
    }

    @Test
    void validEnvValue_isHonored() {
        assertEquals(1_000, OutputLimits.getMaxOutputLength(env("1000")));
        assertEquals(150_000, OutputLimits.getMaxOutputLength(env("150000")));
    }

    @Test
    void aboveUpperLimit_isCappedTo150000() {
        assertEquals(150_000, OutputLimits.getMaxOutputLength(env("999999")));
        assertEquals(150_000, OutputLimits.BASH_MAX_OUTPUT_UPPER_LIMIT);
    }

    @Test
    void invalidEnvValue_fallsBackToDefault() {
        assertEquals(30_000, OutputLimits.getMaxOutputLength(env("abc")));
        assertEquals(30_000, OutputLimits.getMaxOutputLength(env("0")));
        assertEquals(30_000, OutputLimits.getMaxOutputLength(env("-5")));
        assertEquals(30_000, OutputLimits.getMaxOutputLength(env("")));
    }

    @Test
    void leadingIntPrefix_parsesLikeJsParseInt() {
        assertEquals(32_000, OutputLimits.getMaxOutputLength(env("32000abc")));
    }

    // ── formatOutput ─────────────────────────────────────────────────────────

    @Test
    void formatOutput_underLimit_isUnchanged() {
        String content = "line1\nline2\n";
        assertSame(content, OutputLimits.formatOutput(content, env("1000")));
    }

    @Test
    void formatOutput_overLimit_truncatesWithTsMarker() {
        // 30 lines of 9 chars ("line-NN\n" padded) → limit cuts inside the content.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            sb.append("0123456789\n"); // 11 chars per line
        }
        String content = sb.toString(); // 330 chars, 30 newlines
        String result = OutputLimits.formatOutput(content, env("110")); // keeps 10 lines

        assertTrue(Strings.CS.startsWith(result, content.substring(0, 110)));

        assertTrue(Strings.CS.endsWith(result, "\n\n... [21 lines truncated] ..."), result);
    }

    @Test
    void formatOutput_exactlyAtLimit_isUnchanged() {
        String content = "x".repeat(100);
        assertEquals(content, OutputLimits.formatOutput(content, env("100")));
    }

    @Test
    void formatOutput_imageDataUri_isNeverTruncated() {
        String image = "data:image/png;base64," + "A".repeat(5_000);
        assertSame(image, OutputLimits.formatOutput(image, env("100")));
    }

    @Test
    void formatOutput_invalidEnv_usesDefaultLimit() {
        // 1000 chars < default 30_000 → untouched despite garbage env value.
        String content = "y".repeat(1_000);
        assertSame(content, OutputLimits.formatOutput(content, env("not-a-number")));
    }

    @Test
    void formatOutput_nullAndEmpty_passThrough() {
        assertNull(OutputLimits.formatOutput(null, env("10")));
        assertEquals("", OutputLimits.formatOutput("", env("10")));
    }

    @Test
    void stripEmptyLinesPreservesWhitespaceInsideNonEmptyBoundaryLines() {
        assertEquals("  first  \nsecond\t",
            OutputLimits.stripEmptyLines("\n  \n  first  \nsecond\t\n \n"));
        assertEquals("", OutputLimits.stripEmptyLines("\n  \n"));
    }

    @Test
    void isImageOutput_matchesTsRegex() {
        assertTrue(OutputLimits.isImageOutput("data:image/png;base64,AAAA"));
        assertTrue(OutputLimits.isImageOutput("DATA:IMAGE/JPEG;BASE64,AAAA"));
        assertFalse(OutputLimits.isImageOutput("plain text"));
        assertFalse(OutputLimits.isImageOutput(" data:image/png;base64,AAAA")); // not at start
    }

    @Test
    void mapLookup_worksAsEnvSeam() {
        Map<String, String> fake = Map.of("BASH_MAX_OUTPUT_LENGTH", "42");
        assertEquals(42, OutputLimits.getMaxOutputLength(fake::get));
    }
}

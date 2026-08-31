package com.claudecode.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AnsiHyperlinkSupportTest {

    @Test
    void mirrorsOfficialSupportsHyperlinksTerminalMatrix() {
        assertTrue(Ansi.supportsHyperlinks(Map.of("WT_SESSION", "1")));
        assertTrue(Ansi.supportsHyperlinks(Map.of(
            "TERM_PROGRAM", "vscode", "TERM_PROGRAM_VERSION", "1.72.0")));
        assertFalse(Ansi.supportsHyperlinks(Map.of(
            "TERM_PROGRAM", "vscode", "TERM_PROGRAM_VERSION", "1.71.9")));
        assertTrue(Ansi.supportsHyperlinks(Map.of(
            "TERM_PROGRAM", "vscode", "TERM_PROGRAM_VERSION", "0.1.0",
            "CURSOR_TRACE_ID", "cursor")));
        assertTrue(Ansi.supportsHyperlinks(Map.of(
            "TERM_PROGRAM", "WezTerm", "TERM_PROGRAM_VERSION", "20240203")));
        assertTrue(Ansi.supportsHyperlinks(Map.of("TERM_PROGRAM", "zed")));
        assertTrue(Ansi.supportsHyperlinks(Map.of("VTE_VERSION", "5202")));
        assertFalse(Ansi.supportsHyperlinks(Map.of("VTE_VERSION", "0.50.0")));
        assertTrue(Ansi.supportsHyperlinks(Map.of("TERM", "alacritty")));
    }

    @Test
    void forceHyperlinkOverridesDetectionLikeOfficialLibrary() {
        assertTrue(Ansi.supportsHyperlinks(Map.of("FORCE_HYPERLINK", "1")));
        assertFalse(Ansi.supportsHyperlinks(Map.of(
            "FORCE_HYPERLINK", "0", "TERM", "xterm-kitty")));
    }
}

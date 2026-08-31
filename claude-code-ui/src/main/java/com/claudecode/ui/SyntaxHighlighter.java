package com.claudecode.ui;

import com.claudecode.core.constants.AnsiStyle;

import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.theme.RgbColor;
import com.claudecode.ui.lanterna.features.settings.UiSettings;
import com.claudecode.ui.syntax.ScopeColorMap;
import com.claudecode.ui.syntax.TmTokenizer;
import java.util.EnumSet;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
/**
 * Syntax highlighter for fenced code blocks.
 */
public final class SyntaxHighlighter {

    private SyntaxHighlighter() {}

/**
     * Check if syntax highlighting is disabled via settings.
     */
    private static boolean isSyntaxHighlightingDisabled() {
        return UiSettings.readSyntaxHighlightingDisabled();
    }

    /**
     * Highlight {@code code} in {@code language}. Returns the input unchanged
     * when {@code language} isn't supported, when syntax highlighting is
     * disabled via settings, or when tokenization fails.
     */
    public static String highlight(String code, String language) {
        return highlight(code, language, Ansi.isColorSupported());
    }

    /**
     * Capability-injected rendering seam. Production callers use
     * {@link #highlight(String, String)}; tests pass {@code true} so the full
     * tokenizer-to-SGR pipeline is deterministic even in a non-TTY JVM.
     */
    static String highlight(String code, String language, boolean ansiEnabled) {
        if (StringUtils.isEmpty(code)) return "";
        if (isSyntaxHighlightingDisabled()) return code;
        if (!TmTokenizer.isSupported(language)) return code;
        if (!ansiEnabled) return code;

        TmTokenizer.TokenizedCode tokens = TmTokenizer.tokenize(code, language);
        if (tokens == null || tokens.isEmpty()) return code;

        String themeName = LanternaTheme.activeThemeName();
        String[] sourceLines = code.split("\n", -1);
        StringBuilder out = new StringBuilder(code.length() * 3 / 2);

        for (int lineIdx = 0; lineIdx < sourceLines.length; lineIdx++) {
            String line = sourceLines[lineIdx];
            var lineTokens = lineIdx < tokens.lines().size()
                ? tokens.lines().get(lineIdx)
                : List.<TmTokenizer.TmToken>of();

            if (lineTokens.isEmpty()) {
                out.append(line);
            } else {
                int cursor = 0;
                for (TmTokenizer.TmToken tok : lineTokens) {
                    // TM4E sometimes returns tokens past line length (rare,
                    // happens with trailing whitespace tokens). Clamp.
                    int start = Math.max(cursor, Math.min(tok.start(), line.length()));
                    int end   = Math.max(start,  Math.min(tok.end(),   line.length()));
                    if (start > cursor) {
                        out.append(line, cursor, start);
                    }
                    if (end > start) {
                        String segment = line.substring(start, end);
                        RgbColor color = ScopeColorMap.scopeColor(tok.scopes(), segment, themeName);
                        EnumSet<AnsiStyle> styles = ScopeColorMap.scopeStyle(tok.scopes());
                        if (styles.isEmpty()) {
                            out.append(Ansi.coloredAnsi(segment, color));
                        } else {
                            out.append(Ansi.styledAnsi(segment, color, styles.toArray(AnsiStyle[]::new)));
                        }
                        cursor = end;
                    }
                }
                if (cursor < line.length()) {
                    out.append(line, cursor, line.length());
                }
            }
            if (lineIdx < sourceLines.length - 1) out.append('\n');
        }
        return out.toString();
    }

    /**
     * Returns true if the given language alias has a TextMate grammar bundled.
     * Kept for backwards compatibility with callers of the old regex
     * implementation.
     */
    public static boolean isLanguageSupported(String language) {
        return TmTokenizer.isSupported(language);
    }
}

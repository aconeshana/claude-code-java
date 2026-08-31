package com.claudecode.ui;

import org.apache.commons.lang3.Strings;
import com.claudecode.core.constants.AnsiStyle;

import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.theme.RgbColor;
import com.claudecode.ui.syntax.ScopeColorMap;
import com.claudecode.ui.syntax.TmTokenizer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the TM4E-backed syntax highlighter end-to-end.
 */
class SyntaxHighlighterTest {

    /**
     * Returns the SGR opener that {@link Ansi#coloredRgb} actually emits for this
     * RgbColor under the current {@link LanternaTheme#chalkLevel}.
     * level=3 → 24-bit "38;2;R;G;B"; level<3 → 256-color "38;5;N".
     */
    private static String rgbSgr(RgbColor c) {
        if (LanternaTheme.chalkLevel() >= 3) {
            return "[38;2;" + c.r() + ";" + c.g() + ";" + c.b() + "m";
        }
        int idx = rgbToAnsi256(c.r(), c.g(), c.b());
        return "[38;5;" + idx + "m";
    }

    private static int rgbToAnsi256(int r, int g, int b) {
        if (r == g && g == b) {
            if (r < 8)   return 16;
            if (r > 248) return 231;
            return (int) Math.round((r - 8.0) / 247.0 * 24) + 232;
        }
        return 16
            + 36 * (int) Math.round(r / 255.0 * 5)
            +  6 * (int) Math.round(g / 255.0 * 5)
            +      (int) Math.round(b / 255.0 * 5);
    }

    @BeforeEach
    void resetTheme() { LanternaTheme.setScheme(LanternaTheme.Scheme.DARK); }
    @AfterEach
    void clearTheme()  { LanternaTheme.setScheme(LanternaTheme.Scheme.DARK); }

    // ── Guards ────────────────────────────────────────────────────────────

    @Test
    void highlightNullReturnsEmpty() {
        assertEquals("", SyntaxHighlighter.highlight(null, "java"));
    }

    @Test
    void highlightEmptyReturnsEmpty() {
        assertEquals("", SyntaxHighlighter.highlight("", "java"));
    }

    @Test
    void highlightUnknownLanguageReturnsPlainText() {
        String code = "some code here";
        assertEquals(code, SyntaxHighlighter.highlight(code, "brainfuck"));
    }

    @Test
    void highlightNullLanguageReturnsPlainText() {
        String code = "some code here";
        assertEquals(code, SyntaxHighlighter.highlight(code, null));
    }

    // ── Language coverage (the TM4E payoff) ───────────────────────────────

    @Test
    void supportsMoreThan20LanguagesIncludingRustGoKotlinJsonYaml() {
        // Before TM4E we had 5 hand-coded languages; bundling vscode grammars
        // takes us past 20, and crucially the languages the user pastes most
        // (rust/go/kotlin/json/yaml/markdown) are covered.
        assertTrue(TmTokenizer.knownLanguages().size() >= 20,
            "expected ≥20 aliases, got " + TmTokenizer.knownLanguages().size());
        for (String lang : new String[]{
                "java", "python", "py", "javascript", "js", "typescript", "ts",
                "rust", "rs", "go", "json", "yaml", "yml", "bash", "sh",
                "markdown", "md", "c", "cpp", "html", "css", "sql", "ruby", "rb"
        }) {
            assertTrue(SyntaxHighlighter.isLanguageSupported(lang),
                "expected '" + lang + "' to be supported");
        }
    }

    @Test
    void supportsExtendedLanguageSet_kotlinScalaLuaGroovyVueTomlIniNginx() {
        // After the 2026-06-26 grammar expansion these should all resolve.
        for (String lang : new String[]{
                "kotlin", "kt", "scala", "lua", "groovy",
                "vue",
                "toml", "ini", "env", "dotenv", "nginx", "cmake", "gradle", "properties",
                "graphql", "gql", "proto", "protobuf", "thrift",
                "latex", "tex",
                "batch", "bat", "cmd", "powershell", "awk",
                "vim", "diff", "patch", "crontab"
        }) {
            assertTrue(SyntaxHighlighter.isLanguageSupported(lang),
                "expected '" + lang + "' to be supported after expansion");
        }
    }

    @Test
    void supportsCommonAliasesUserMightType() {
        // c# / objc / objective-c / node / console / shell-session — common
        // mis-typings or hljs aliases that should not fall through to plain text.
        for (String lang : new String[]{
                "c#", "objc", "obj-c", "objective-c",
                "node", "console", "shell-session", "shellsession",
                "yml", "json5", "ps1"
        }) {
            assertTrue(SyntaxHighlighter.isLanguageSupported(lang),
                "expected alias '" + lang + "' to resolve");
        }
    }

    @Test
    void rustCodeIsActuallyHighlightedNowInsteadOfPlainText() {
        String code = "fn main() { let x = 42; }";
        String out = SyntaxHighlighter.highlight(code, "rust", true);
        assertNotEquals(code, out, "rust used to fall through as plain text");
        assertTrue(Strings.CS.contains(out, "\u001B"), "expected ANSI escape in output");
    }

    @Test
    void goCodeIsHighlighted() {
        String out = SyntaxHighlighter.highlight("package main\nfunc main() {}", "go", true);
        assertTrue(Strings.CS.contains(out, "\u001B"));
    }

    @Test
    void jsonCodeIsHighlighted() {
        String out = SyntaxHighlighter.highlight("{\"key\":\"value\",\"n\":42}", "json", true);
        assertTrue(Strings.CS.contains(out, "\u001B"));
    }



    @Test
    void darkThemeStringsAreMonokaiYellow() {
        // Monokai string foreground = rgb(230, 219, 116) per

        LanternaTheme.setScheme(LanternaTheme.Scheme.DARK);
        String out = SyntaxHighlighter.highlight("String s = \"hello\";", "java", true);
        RgbColor expected = new RgbColor(230, 219, 116);
        assertTrue(Strings.CS.contains(out, rgbSgr(expected)),
            "dark theme string should emit Monokai yellow rgb(230,219,116)");
    }

    @Test
    void lightThemeStringsAreGitHubDarkBlue() {
        // GitHub string foreground = rgb(24, 54, 145).
        LanternaTheme.setScheme(LanternaTheme.Scheme.LIGHT);
        String out = SyntaxHighlighter.highlight("String s = \"hello\";", "java", true);
        RgbColor expected = new RgbColor(24, 54, 145);
        assertTrue(Strings.CS.contains(out, rgbSgr(expected)),
            "light theme string should emit GitHub dark-blue rgb(24,54,145)");
    }

    @Test
    void darkThemeCommentsAreMonokaiDimGreen() {
        // Monokai comment = rgb(117, 113, 94).
        LanternaTheme.setScheme(LanternaTheme.Scheme.DARK);
        String out = SyntaxHighlighter.highlight("// hello\nint x = 1;", "java", true);
        RgbColor expected = new RgbColor(117, 113, 94);
        assertTrue(Strings.CS.contains(out, rgbSgr(expected)),
            "dark theme comment should emit Monokai dim rgb(117,113,94)");
    }

    @Test
    void darkThemeNumbersAreMonokaiPurple() {
        // Monokai number/literal = rgb(190, 132, 255).
        LanternaTheme.setScheme(LanternaTheme.Scheme.DARK);
        String out = SyntaxHighlighter.highlight("int x = 42;", "java", true);
        RgbColor expected = new RgbColor(190, 132, 255);
        assertTrue(Strings.CS.contains(out, rgbSgr(expected)),
            "dark theme number should emit Monokai purple rgb(190,132,255)");
    }

    @Test
    void darkThemeClassNamesAreMonokaiGreen() {
        // Monokai title.class = rgb(166, 226, 46).
        LanternaTheme.setScheme(LanternaTheme.Scheme.DARK);
        String out = SyntaxHighlighter.highlight("public class Foo {}", "java", true);
        RgbColor expected = new RgbColor(166, 226, 46);
        assertTrue(Strings.CS.contains(out, rgbSgr(expected)),
            "class identifier should emit Monokai green rgb(166,226,46)");
    }

    @Test
    void darkThemeKeywordControlIsMonokaiPink() {
        // Monokai keyword = rgb(249, 38, 114).
        LanternaTheme.setScheme(LanternaTheme.Scheme.DARK);
        String out = SyntaxHighlighter.highlight(
            "void m() { if (true) return; }", "java", true);
        RgbColor expected = new RgbColor(249, 38, 114);
        assertTrue(Strings.CS.contains(out, rgbSgr(expected)),
            "keyword.control should emit Monokai pink rgb(249,38,114)");
    }

    @Test
    void darkThemeStorageTypeIsMonokaiCyan() {
        // Monokai _storage = rgb(102, 217, 239). TextMate `storage.type.*`
        // (e.g. `function`, `class`, `const`) maps to _storage.
        LanternaTheme.setScheme(LanternaTheme.Scheme.DARK);
        String out = SyntaxHighlighter.highlight("const x = 1;", "js", true);
        RgbColor expected = new RgbColor(102, 217, 239);
        assertTrue(Strings.CS.contains(out, rgbSgr(expected)),
            "storage.type 'const' should emit Monokai cyan rgb(102,217,239)");
    }

    // ── Scope-mapping unit tests ──────────────────────────────────────────

    @Test
    void scopeMapper_commentVariants() {
        assertEquals("comment", ScopeColorMap.mapTmScopeToHljs("comment.line.double-slash.java", "//"));
        assertEquals("comment", ScopeColorMap.mapTmScopeToHljs("comment.block.python", "# ..."));
    }

    @Test
    void scopeMapper_keywordOperatorBeatsKeyword() {
        // keyword.operator.* must NOT fall back to plain keyword.
        assertEquals("operator", ScopeColorMap.mapTmScopeToHljs("keyword.operator.assignment.js", "="));
    }

    @Test
    void scopeMapper_entityNameFunctionMapsToTitleFunction() {
        assertEquals("title.function",
            ScopeColorMap.mapTmScopeToHljs("entity.name.function.java", "foo"));
    }

    @Test
    void scopeMapper_storageTypeMapsToStorage() {
        assertEquals("_storage",
            ScopeColorMap.mapTmScopeToHljs("storage.type.java", "String"));
    }

    @Test
    void scopeMapper_unknownReturnsNull() {
        assertNull(ScopeColorMap.mapTmScopeToHljs("foo.bar.baz", "x"));
        assertNull(ScopeColorMap.mapTmScopeToHljs(null, "x"));
    }

    @Test
    void scopeColor_emptyScopesUsesForeground() {
        // Monokai foreground = rgb(248,248,242).
        RgbColor color = ScopeColorMap.scopeColor(List.of(), "x", "dark");
        assertEquals(new RgbColor(248, 248, 242), color);
    }

    @Test
    void scopeColor_storageKeywordsPromoteFromKeywordToStorage() {
        // The STORAGE_KEYWORDS escape hatch: even when a grammar tags
        // 'const'/'function'/'class' as plain `keyword.*`, the colour map

// color-diff-napi scopeColor L466-468.
        RgbColor color = ScopeColorMap.scopeColor(
            List.of("source.foo", "keyword.control.lang.foo"), "function", "dark");
        assertEquals(new RgbColor(102, 217, 239), color,  // Monokai _storage
            "STORAGE_KEYWORDS hit should override keyword colour");
    }

    @Test
    void scopeColor_leafScopeWinsOverAncestor() {
        // chain = [root, more-general, more-specific] — most specific wins.
        RgbColor color = ScopeColorMap.scopeColor(
            List.of("source.java", "keyword.control.java"), "if", "dark");
        assertEquals(new RgbColor(249, 38, 114), color); // Monokai keyword
    }

    // ── Multi-line state ──────────────────────────────────────────────────

    @Test
    void highlightPreservesLineStructure() {
        String code = "line1\nline2\nline3";
        String result = SyntaxHighlighter.highlight(code, "java");
        assertEquals(3, result.split("\n", -1).length);
    }

    @Test
    void multilineStringStaysColouredAcrossLines() {
        // Python triple-quoted string must keep the string colour on every
        // line — proves IStateStack is threaded through lines correctly.
        LanternaTheme.setScheme(LanternaTheme.Scheme.DARK);
        String code = "x = \"\"\"hello\nworld\n\"\"\"";
        String out = SyntaxHighlighter.highlight(code, "python", true);
        RgbColor stringColor = new RgbColor(230, 219, 116);
        // String colour should appear at least 2 times — once for the open
        // quote line, once for the inner continuation. (Tightly inspecting
        // line-by-line would brittle-bind to grammar details.)
        int hits = 0, idx = 0;
        String marker = rgbSgr(stringColor);
        while ((idx = out.indexOf(marker, idx)) >= 0) { hits++; idx++; }
        assertTrue(hits >= 2,
            "multi-line string should keep string colour across newlines; hits=" + hits);
    }

    // ── Bridge-table extensions (2026-06-26 expansion) ───────────────────

    @Test
    void scopeMapper_punctuationDefinitionStringMapsToString() {
        // Without this rule the opening/closing quote chars of a string
        // would drop to the punctuation grey, breaking the visual run.
        assertEquals("string",
            ScopeColorMap.mapTmScopeToHljs("punctuation.definition.string.begin.java", "\""));
        assertEquals("string",
            ScopeColorMap.mapTmScopeToHljs("punctuation.definition.string.end.python", "\""));
    }

    @Test
    void scopeMapper_punctuationDefinitionCommentMapsToComment() {
        // `//` `/*` `*/` `#` should look like part of the comment, not punct.
        assertEquals("comment",
            ScopeColorMap.mapTmScopeToHljs("punctuation.definition.comment.java", "//"));
    }

    @Test
    void scopeMapper_storageAnnotationMapsToMeta() {
        // @Override / @Test etc.
        assertEquals("meta",
            ScopeColorMap.mapTmScopeToHljs("storage.type.annotation.java", "@"));
    }

    @Test
    void scopeMapper_invalidIsHighlightedNotInvisible() {
        // grammar-flagged invalid tokens should pop, not vanish.
        assertEquals("keyword",
            ScopeColorMap.mapTmScopeToHljs("invalid.illegal.foo", "??"));
        assertEquals("comment",
            ScopeColorMap.mapTmScopeToHljs("invalid.deprecated.bar", "old"));
    }

    @Test
    void scopeMapper_markupHeading() {
        assertEquals("title",
            ScopeColorMap.mapTmScopeToHljs("markup.heading.1.md", "# H1"));
    }

    // ── Style support (italic comments, bold headings) ───────────────────

    @Test
    void scopeStyle_commentsAreItalic() {
        var styles = ScopeColorMap.scopeStyle(List.of(
            "source.java", "comment.line.double-slash.java"));
        assertTrue(styles.contains(AnsiStyle.ITALIC),
            "comments must be italic to match Monokai/GitHub default");
    }

    @Test
    void scopeStyle_markdownBoldIsBold() {
        var styles = ScopeColorMap.scopeStyle(List.of(
            "text.html.markdown", "markup.bold.markdown"));
        assertTrue(styles.contains(AnsiStyle.BOLD));
    }

    @Test
    void scopeStyle_markdownLinksAreUnderlined() {
        var styles = ScopeColorMap.scopeStyle(List.of(
            "text.html.markdown", "markup.underline.link.markdown"));
        assertTrue(styles.contains(AnsiStyle.UNDERLINE));
    }

    @Test
    void scopeStyle_codeWithoutStyleScopesIsPlain() {
        var styles = ScopeColorMap.scopeStyle(List.of(
            "source.java", "keyword.control.java"));
        assertTrue(styles.isEmpty(),
            "plain keyword tokens should not carry italic/bold");
    }

    @Test
    void highlightEmitsItalicSgrOnComments() {
        // Smoke test that the end-to-end SyntaxHighlighter actually emits
        // \x1b[3m for a comment line, not just colour.
        LanternaTheme.setScheme(LanternaTheme.Scheme.DARK);
        String out = SyntaxHighlighter.highlight("// hello", "java", true);
        assertTrue(Strings.CS.contains(out, "\u001B[3m"),
            "expected italic SGR (\\x1b[3m) on // comment");
    }

    // ── New language coverage ────────────────────────────────────────────

    @Test
    void kotlinIsHighlighted() {
        LanternaTheme.setScheme(LanternaTheme.Scheme.DARK);
        String out = SyntaxHighlighter.highlight("fun main() { val x = 42 }", "kotlin", true);
        assertNotEquals("fun main() { val x = 42 }", out);
        assertTrue(Strings.CS.contains(out, "\u001B"));
    }

    @Test
    void tomlIsHighlighted() {
        LanternaTheme.setScheme(LanternaTheme.Scheme.DARK);
        String out = SyntaxHighlighter.highlight("[server]\nport = 8080\n", "toml", true);
        assertNotEquals("[server]\nport = 8080\n", out);
    }

    @Test
    void vueIsHighlighted() {
        LanternaTheme.setScheme(LanternaTheme.Scheme.DARK);
        String out = SyntaxHighlighter.highlight(
            "<template>\n  <div>Hello</div>\n</template>", "vue", true);
        assertTrue(Strings.CS.contains(out, "\u001B"));
    }

    // ── Embedded grammar (HTML → CSS / JS) ───────────────────────────────

    @Test
    void htmlStyleBlockTokenizesAsCss() {
        // CSS property name `color` should land on TextMate scope
        // `support.type.property-name.css` which our bridge maps to `type`
        // (Monokai green = rgb(166, 226, 46)).
        LanternaTheme.setScheme(LanternaTheme.Scheme.DARK);
        String html = "<html><style>body { color: red; }</style></html>";
        String out = SyntaxHighlighter.highlight(html, "html", true);
        RgbColor cssTypeColor = new RgbColor(166, 226, 46);
        assertTrue(Strings.CS.contains(out, rgbSgr(cssTypeColor)),
            "expected CSS property `color` to render in Monokai green via HTML→CSS embedded grammar");
    }

    @Test
    void htmlScriptBlockTokenizesAsJs() {

        LanternaTheme.setScheme(LanternaTheme.Scheme.DARK);
        String html = "<html><script>const x = 42;</script></html>";
        String out = SyntaxHighlighter.highlight(html, "html", true);
        RgbColor storageCyan = new RgbColor(102, 217, 239);
        assertTrue(Strings.CS.contains(out, rgbSgr(storageCyan)),
            "expected JS `const` in <script> to render in Monokai cyan via HTML→JS embedded grammar");
    }
}

package com.claudecode.ui;

import org.apache.commons.lang3.Strings;
import com.claudecode.core.diff.StructuredPatchHunk;
import com.claudecode.core.engine.SandboxConfig;
import com.claudecode.permissions.PermissionBehavior;
import com.claudecode.permissions.RuleSource;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.claudecode.ui.lanterna.features.settings.UiSettings;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for diff rendering with ANSI colors.
 * <p>
 * After the 2026-06-26 theme alignment, DiffRenderer emits 24-bit RGB SGR
 * pulled from {@code LanternaTheme.activeTheme.diffAdded / diffRemoved
 * / subtle} instead of the bare 8-color ANSI escapes. Tests assert the
 * theme RGB shows up in the output rather than the legacy {@code AnsiColor}.
 */
class DiffRendererTest {

    /**
     * Returns the SGR opener that {@link Ansi#coloredRgb} actually emits under
     * the current chalk level. level=3 -> 24-bit; level<3 -> 256-color.
     */
    private static String rgbSgr(int r, int g, int b) {
        if (LanternaTheme.chalkLevel() >= 3) {
            return "" + (char) 27 + "[38;2;" + r + ";" + g + ";" + b + "m";
        }
        int idx = rgbToAnsi256(r, g, b);
        return "" + (char) 27 + "[38;5;" + idx + "m";
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

    @Test
    void renderAddedLineInGreen() {
        String result = DiffRenderer.renderDiffLine("+added line");
        assertTrue(Strings.CS.contains(result, "added line"));
        if (Ansi.isColorSupported()) {
            var c = LanternaTheme.activeTheme().diffAdded();
            assertTrue(Strings.CS.contains(result, rgbSgr(c.r(), c.g(), c.b())),
                "Added lines should use theme.diffAdded RGB");
        }
    }

    @Test
    void renderRemovedLineInRed() {
        String result = DiffRenderer.renderDiffLine("-removed line");
        assertTrue(Strings.CS.contains(result, "removed line"));
        if (Ansi.isColorSupported()) {
            var c = LanternaTheme.activeTheme().diffRemoved();
            assertTrue(Strings.CS.contains(result, rgbSgr(c.r(), c.g(), c.b())),
                "Removed lines should use theme.diffRemoved RGB");
        }
    }

    @Test
    void renderContextLineUnchanged() {
        String result = DiffRenderer.renderDiffLine(" context line");

        assertTrue(Strings.CS.contains(result, "context line"));
        if (Ansi.isColorSupported()) {
            assertTrue(Strings.CS.startsWith(result, "" + (char) 27 + "["));
        } else {
            assertEquals(" context line", result);
        }
    }

    @Test
    void renderHunkHeaderInCyan() {
        String result = DiffRenderer.renderDiffLine("@@ -1,3 +1,4 @@");
        assertTrue(Strings.CS.contains(result, "@@ -1,3 +1,4 @@"));
        if (Ansi.isColorSupported()) {
            var c = LanternaTheme.activeTheme().subtle();
            assertTrue(Strings.CS.contains(result, rgbSgr(c.r(), c.g(), c.b())),
                "Hunk headers should use theme.subtle (dim divider) RGB");
        }
    }

    @Test
    void renderFileHeaderBold() {
        String result = DiffRenderer.renderDiffLine("--- a/file.txt");
        assertTrue(Strings.CS.contains(result, "--- a/file.txt"));
    }

    @Test
    void renderEmptyLineReturnsEmpty() {
        assertEquals("", DiffRenderer.renderDiffLine(""));
        assertEquals("", DiffRenderer.renderDiffLine(null));
    }

    @Test
    void renderUnifiedDiffNull() {
        assertEquals("", DiffRenderer.renderUnifiedDiff(null));
        assertEquals("", DiffRenderer.renderUnifiedDiff(""));
    }

    @Test
    void renderUnifiedDiffColorsAllLines() {
        String diff = """
                --- a/test.txt
                +++ b/test.txt
                @@ -1,3 +1,3 @@
                 context
                -old line
                +new line""";
        String result = DiffRenderer.renderUnifiedDiff(diff);
        assertTrue(Strings.CS.contains(result, "context"));
        assertTrue(Strings.CS.contains(result, "old line"));
        assertTrue(Strings.CS.contains(result, "new line"));
    }

    @Test
    void generateDiffIdenticalTexts() {
        String text = "line1\nline2\nline3";
        String diff = DiffRenderer.generateDiff(text, text, "file.txt", 3);
        // Identical texts should produce header but no change hunks
        assertTrue(Strings.CS.contains(diff, "--- a/file.txt"));
        assertTrue(Strings.CS.contains(diff, "+++ b/file.txt"));
        assertFalse(Strings.CS.contains(diff, "@@"), "No hunks for identical content");
    }

    @Test
    void generateDiffWithAddedLine() {
        String oldText = "line1\nline2";
        String newText = "line1\nline2\nline3";
        String diff = DiffRenderer.generateDiff(oldText, newText, "test.txt", 3);
        assertTrue(Strings.CS.contains(diff, "+line3"), "Should show added line");
    }

    @Test
    void generateDiffWithRemovedLine() {
        String oldText = "line1\nline2\nline3";
        String newText = "line1\nline3";
        String diff = DiffRenderer.generateDiff(oldText, newText, "test.txt", 3);
        assertTrue(Strings.CS.contains(diff, "-line2"), "Should show removed line");
    }

    @Test
    void generateDiffWithModifiedLine() {
        String oldText = "line1\nold\nline3";
        String newText = "line1\nnew\nline3";
        String diff = DiffRenderer.generateDiff(oldText, newText, "test.txt", 3);
        assertTrue(Strings.CS.contains(diff, "-old"), "Should show removed old line");
        assertTrue(Strings.CS.contains(diff, "+new"), "Should show added new line");
    }

    @Test
    void generateAndRenderDiffProducesColoredOutput() {
        String oldText = "hello\nworld";
        String newText = "hello\nearth";
        String result = DiffRenderer.generateAndRenderDiff(oldText, newText, "greet.txt");
        assertTrue(Strings.CS.contains(result, "world"));
        assertTrue(Strings.CS.contains(result, "earth"));
    }

    @Test
    void computeDiffEmptyInputs() {
        List<DiffRenderer.DiffLine> result = DiffRenderer.computeDiff(new String[]{}, new String[]{});
        assertTrue(result.isEmpty());
    }

    @Test
    void computeDiffAddToEmpty() {
        List<DiffRenderer.DiffLine> result = DiffRenderer.computeDiff(
                new String[]{}, new String[]{"new"});
        assertEquals(1, result.size());
        assertEquals(DiffRenderer.DiffType.ADDED, result.getFirst().type());
    }

    @Test
    void computeDiffRemoveAll() {
        List<DiffRenderer.DiffLine> result = DiffRenderer.computeDiff(
                new String[]{"old"}, new String[]{});
        assertEquals(1, result.size());
        assertEquals(DiffRenderer.DiffType.REMOVED, result.getFirst().type());
    }

    // ---- renderHunk (structured engine consumed by DiffDialog) --------------

    /** Flattens a line view's segments into a single text string (tests use it
     *  only when every segment is the same kind). */
    private static String segText(List<DiffRenderer.Segment> segs) {
        StringBuilder sb = new StringBuilder();
        for (var s : segs) sb.append(s.text());
        return sb.toString();
    }

    @Test
    void renderHunkContextAndChangeLines() {
        var hunk = new StructuredPatchHunk(1, 4, 1, 4, List.of(
                " old line",
                "-oldName",
                "+newName",
                " same"));
        List<DiffRenderer.DiffLineView> out = DiffRenderer.renderHunk(hunk);
        // 1 synthesized @@ header + 4 content lines.
        assertEquals(5, out.size());

        // Synthesized hunk header is the first line (single gutter, no lineNo).
        var header = out.getFirst();
        assertEquals('@', header.marker());
        assertNull(header.lineNo());
        assertEquals(DiffRenderer.SegKind.HUNK, header.segments().getFirst().kind());
        assertEquals("@@ -1,4 +1,4 @@", header.segments().getFirst().text());

        // Context line: single gutter number, COMMON segment.
        var ctx = out.get(1);
        assertEquals(1, ctx.lineNo());
        assertEquals(' ', ctx.marker());
        assertEquals(DiffRenderer.SegKind.COMMON, ctx.segments().getFirst().kind());
        assertEquals("old line", segText(ctx.segments()));

        // Removed line: single gutter number; "oldName" vs "newName" shares no
        // words so it falls back to a whole-line REMOVED segment.
        var rem = out.get(2);
        assertEquals(2, rem.lineNo());
        assertEquals('-', rem.marker());
        assertEquals(DiffRenderer.SegKind.REMOVED, rem.segments().getFirst().kind());
        assertEquals("oldName", segText(rem.segments()));

        // Added line: same gutter number as its paired remove (collapse), ADDED.
        var add = out.get(3);
        assertEquals(2, add.lineNo());
        assertEquals('+', add.marker());
        assertEquals(DiffRenderer.SegKind.ADDED, add.segments().getFirst().kind());
        assertEquals("newName", segText(add.segments()));

        // Final context line: gutter advanced past the change.
        var ctx2 = out.get(4);
        assertEquals(3, ctx2.lineNo());
        assertEquals(' ', ctx2.marker());
    }

    @Test
    void renderHunkWordLevelPairingColorsOnlyChangedWords() {
        // Shared words stay COMMON; only the differing identifier is colored.
        var hunk = new StructuredPatchHunk(1, 2, 1, 2, List.of(
                "-const foo = 1",
                "+const bar = 1"));
        List<DiffRenderer.DiffLineView> out = DiffRenderer.renderHunk(hunk);
        assertEquals(3, out.size());            // header + remove + add
        assertEquals('@', out.getFirst().marker());

        var rem = out.get(1);
        var add = out.get(2);
        // Both sides share: "const" + " " + " " + "=" + " " + "1"
        // Removed-only token: "foo"; Added-only token: "bar". Tokens:
        // [const, " ", foo, " ", =, " ", 1] -> 7 segments per side.
        assertEquals(List.of(
                DiffRenderer.SegKind.COMMON, DiffRenderer.SegKind.COMMON,
                DiffRenderer.SegKind.COMMON, DiffRenderer.SegKind.COMMON,
                DiffRenderer.SegKind.REMOVED, DiffRenderer.SegKind.ADDED,
                DiffRenderer.SegKind.COMMON, DiffRenderer.SegKind.COMMON,
                DiffRenderer.SegKind.COMMON, DiffRenderer.SegKind.COMMON,
                DiffRenderer.SegKind.COMMON, DiffRenderer.SegKind.COMMON,
                DiffRenderer.SegKind.COMMON, DiffRenderer.SegKind.COMMON),
            segKinds(rem.segments(), add.segments()));
        // The differing token text.
        assertEquals("foo", rem.segments().get(2).text());
        assertEquals("bar", add.segments().get(2).text());
    }

    @Test
    void renderHunkAddedOnlyLinesHaveGutterNumbers() {
        var hunk = new StructuredPatchHunk(1, 0, 1, 2, List.of(
                "+added1",
                "+added2"));
        List<DiffRenderer.DiffLineView> out = DiffRenderer.renderHunk(hunk);
        assertEquals(3, out.size());            // header + 2 added
        assertEquals('@', out.getFirst().marker());
        assertEquals(1, out.get(1).lineNo());
        assertEquals('+', out.get(1).marker());
        assertEquals(2, out.get(2).lineNo());
        assertEquals(DiffRenderer.SegKind.ADDED, out.get(1).segments().getFirst().kind());
    }

    @Test
    void renderHunkRemovedOnlyLinesHaveGutterNumbers() {
        var hunk = new StructuredPatchHunk(1, 2, 1, 0, List.of(
                "-removed1",
                "-removed2"));
        List<DiffRenderer.DiffLineView> out = DiffRenderer.renderHunk(hunk);
        assertEquals(3, out.size());            // header + 2 removed
        assertEquals('@', out.getFirst().marker());
        assertEquals(1, out.get(1).lineNo());
        assertEquals('-', out.get(1).marker());
        assertEquals(2, out.get(2).lineNo());
        assertEquals(DiffRenderer.SegKind.REMOVED, out.get(1).segments().getFirst().kind());
    }

    @Test
    void renderHunkHeaderLineIsHunkKind() {
        // Defensive branch: a stray @@ header in the lines list. The canonical
        // synthesized header is produced first; the embedded one is skipped and
        // does not consume a gutter number.
        var hunk = new StructuredPatchHunk(1, 1, 1, 1, List.of(
                "@@ -1,3 +1,4 @@",
                " context"));
        List<DiffRenderer.DiffLineView> out = DiffRenderer.renderHunk(hunk);
        assertEquals(2, out.size());
        assertEquals(DiffRenderer.SegKind.HUNK, out.getFirst().segments().getFirst().kind());
        assertNull(out.getFirst().lineNo());
        // Context line still numbered from the hunk start.
        assertEquals(1, out.get(1).lineNo());
        assertEquals(' ', out.get(1).marker());
    }

    @Test
    void renderHunkWithLanguageAddsSyntaxForegroundWithoutLosingDiffKinds() {
        var hunk = new StructuredPatchHunk(1, 1, 1, 1, List.of(
            "-public class OldName {}",
            "+public class NewName {}"));

        List<DiffRenderer.DiffLineView> out = DiffRenderer.renderHunk(hunk, "java");

        assertTrue(out.get(1).segments().stream().anyMatch(s -> s.foreground() != null));
        assertTrue(out.get(2).segments().stream().anyMatch(s -> s.foreground() != null));
        assertTrue(out.get(1).segments().stream()
            .anyMatch(s -> s.kind() == DiffRenderer.SegKind.REMOVED));
        assertTrue(out.get(2).segments().stream()
            .anyMatch(s -> s.kind() == DiffRenderer.SegKind.ADDED));
        assertEquals("public class OldName {}", segText(out.get(1).segments()));
        assertEquals("public class NewName {}", segText(out.get(2).segments()));
    }

    @Test
    void syntaxHighlightingSettingFallsBackToUncoloredStructuredDiff() {
        UiSettings.configure(new UiSettings.Backend() {
            @Override public boolean globalBoolean(String key, boolean defaultValue) { return defaultValue; }
            @Override public String globalString(String key, String defaultValue) { return defaultValue; }
            @Override public int globalInt(String key, int defaultValue) { return defaultValue; }
            @Override public JsonNode effectiveSetting(String key) {
                return Strings.CS.equals("syntaxHighlightingDisabled", key)
                    ? BooleanNode.TRUE : null;
            }
            @Override public void setGlobal(String key, Object value) { }
            @Override public boolean spinnerTipsEnabled() { return true; }
            @Override public boolean prefersReducedMotion() { return false; }
            @Override public Boolean policyBoolean(String key) { return null; }
            @Override public SandboxConfig sandboxConfig() {
                return SandboxConfig.disabled();
            }
            @Override public void addPermissionRule(String cwd,
                    PermissionBehavior behavior, String ruleString,
                    RuleSource tier) { }
            @Override public void removePermissionRule(String cwd,
                    PermissionBehavior behavior, String ruleString,
                    RuleSource tier) { }
        });
        try {
            var hunk = new StructuredPatchHunk(1, 1, 1, 1, List.of(
                "-public class OldName {}", "+public class NewName {}"));

            List<DiffRenderer.DiffLineView> out = DiffRenderer.renderHunk(hunk, "java");

            assertTrue(out.stream().skip(1).flatMap(line -> line.segments().stream())
                .allMatch(segment -> segment.foreground() == null));
        } finally {
            UiSettings.configure(null);
        }
    }

    /** Zips the kinds of the removed/added segment lists pairwise as a single
     *  interleaved list (index 2n -> removed, 2n+1 -> added) for pairing asserts. */
    private static List<DiffRenderer.SegKind> segKinds(
            List<DiffRenderer.Segment> removed, List<DiffRenderer.Segment> added) {
        List<DiffRenderer.SegKind> kinds = new ArrayList<>();
        for (int i = 0; i < Math.max(removed.size(), added.size()); i++) {
            if (i < removed.size()) kinds.add(removed.get(i).kind());
            if (i < added.size()) kinds.add(added.get(i).kind());
        }
        return kinds;
    }
}

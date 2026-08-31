package com.claudecode.ui.lanterna.transcript;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.claudecode.core.diff.StructuredPatchHunk;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.theme.Theme;
import com.googlecode.lanterna.TextColor;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The dimmed FileEdit diff path (197's {@code FileEditToolUseRejectedMessage},
 * where {@code dim=true}) must select the theme's {@code diffAddedDimmed} /
 * {@code diffRemovedDimmed} line backgrounds directly, not a blend of the
 * normal palette background. This pins {@code appendInlineDiffHunk}'s two-way
 * lookup: dimmed intent reads the dimmed theme keys; the normal path keeps the
 * structured palette.
 */
class RejectedDiffDimmedThemeTest {

    /** Reviewed via reflection: {@code appendInlineDiffHunk(MessagePanel, StructuredPatchHunk, String, boolean)}. */
    private static final Method APPEND = reflectAppend();

    private static Method reflectAppend() {
        try {
            Method m = LanternaMessageDispatcher.class
                .getDeclaredMethod("appendInlineDiffHunk",
                    MessagePanel.class, StructuredPatchHunk.class, String.class, Boolean.TYPE);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("appendInlineDiffHunk no longer exists", e);
        }
    }

    private static StructuredPatchHunk hunk() {
        return new StructuredPatchHunk(1, 4, 1, 4, List.of(
            " class Example {",
            "-  int oldValue;",
            "   int keep;",
            "+  int newValue;",
            " }"));
    }

    private static List<MessagePanel.Segment> segments(boolean dim) throws Exception {
        MessagePanel panel = new MessagePanel();
        APPEND.invoke(null, panel, hunk(), "java", dim);
        return panel.displayRowsForTest(160).stream()
            .flatMap(row -> row.segments().stream())
            .toList();
    }

    @Test
    void dimmedDiffAppliesTheDiffAddedDimmedLineBackground() throws Exception {
        // dim=true selects Theme.diffAddedDimmed/diffRemovedDimmed as the +/− line
        // backgrounds (197 FileEditToolUseRejectedMessage), and gutters carry that
        // exact value on every changed line.
        TextColor expectedAdded = LanternaTheme.diffAddedDimmed();
        TextColor expectedRemoved = LanternaTheme.diffRemovedDimmed();
        List<MessagePanel.Segment> segs = segments(true);
        boolean sawAdded = false;
        boolean sawRemoved = false;
        for (MessagePanel.Segment seg : segs) {
            if (expectedAdded.equals(seg.bgColor())) sawAdded = true;
            if (expectedRemoved.equals(seg.bgColor())) sawRemoved = true;
        }
        if (!sawAdded || !sawRemoved) {
            throw new AssertionError("dim=true must render both + and - gutter backgrounds as the "
                + "theme's dimmed keys (added=" + expectedAdded + " removed=" + expectedRemoved
                + "); saw distinct backgrounds="
                + segs.stream().map(s -> "" + s.bgColor()).distinct().toList());
        }
    }

    @Test
    void normalDiffKeepsTheStructuredPaletteRatherThanDimmedKeys() throws Exception {
        // dim=false renders +/− lines from diffRenderPalette() (a separate darker
        // palette), NOT from Theme.diffAdded/diffRemoved — and certainly never from
        // the dimmed theme keys. Assert both properties.
        LanternaTheme.DiffRenderPalette palette = LanternaTheme.diffRenderPalette();
        List<MessagePanel.Segment> segs = segments(false);
        boolean sawAddedBg = false;
        boolean sawRemovedBg = false;
        for (MessagePanel.Segment seg : segs) {
            TextColor bg = seg.bgColor();
            if (bg == null) continue;
            if (bg.equals(palette.addedLineBackground())) sawAddedBg = true;
            if (bg.equals(palette.removedLineBackground())) sawRemovedBg = true;
            assertNotEquals(LanternaTheme.diffAddedDimmed(), bg,
                "dim=false line background must not be the dimmed theme key");
            assertNotEquals(LanternaTheme.diffRemovedDimmed(), bg,
                "dim=false line background must not be the dimmed theme key");
        }
        if (!sawAddedBg || !sawRemovedBg) {
            throw new AssertionError("dim=false must render both + and - line backgrounds from "
                + "diffRenderPalette(); saw distinct backgrounds="
                + segs.stream().map(s -> "" + s.bgColor()).distinct().toList());
        }
    }

    @Test
    void dimmedBackgroundDiffersFromNormalPaletteBackground() {
        // Guard against an accidental no-op: the whole point is that the dimmed path
        // reads the Theme.diffAddedDimmed/diffRemovedDimmed keys, which differ from
        // the plain diffAdded/diffRemoved tuples. Compare the raw theme record
        // (pre-toLC): at 16/256-colors the distinct RGBs fold to the same Indexed
        // value and the toLC() TextColors would collide.
        Theme theme = LanternaTheme.activeTheme();
        assertNotEquals(theme.diffAddedDimmed(), theme.diffAdded(),
            "diffAddedDimmed must not equal diffAdded, else the two-way lookup is moot");
        assertNotEquals(theme.diffRemovedDimmed(), theme.diffRemoved(),
            "diffRemovedDimmed must not equal diffRemoved, else the two-way lookup is moot");
    }
}
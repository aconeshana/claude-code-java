package com.claudecode.ui.render;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.constants.Figures;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.transcript.MessagePanel;
import com.googlecode.lanterna.TextColor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the render-package indicator renderers and the HighlightedThinkingRenderer.
 *
 * <p>MessagePanel is tested in headless mode — we call append* methods and read back
 * the lines via reflection, without involving the Lanterna display thread.
 */
class IndicatorRenderersTest {

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Reads the internal {@code lines} list from a {@link MessagePanel} via
     * reflection so tests can assert on what was appended without a display.
     */
    @SuppressWarnings("unchecked")
    private static List<MessagePanel.StyledLine> linesOf(MessagePanel panel) throws Exception {
        Field f = MessagePanel.class.getDeclaredField("lines");
        f.setAccessible(true);
        return new ArrayList<>((List<MessagePanel.StyledLine>) f.get(panel));
    }

    // ── RenderingContext ────────────────────────────────────────────────────────

    @Test
    void renderingContext_NORMAL_isNotQueued() {
        assertFalse(RenderingContext.NORMAL.isInQueuedPreview());
        assertFalse(RenderingContext.NORMAL.isFirstInQueue());
        assertEquals(0, RenderingContext.NORMAL.paddingWidth());
    }

    @Test
    void renderingContext_queuedPreview_hasCorrectFields() {
        RenderingContext ctx = RenderingContext.queuedPreview(true, 4);
        assertTrue(ctx.isInQueuedPreview());
        assertTrue(ctx.isFirstInQueue());
        assertEquals(4, ctx.paddingWidth());
    }

    @Test
    void renderingContext_queuedPreview_notFirst() {
        RenderingContext ctx = RenderingContext.queuedPreview(false, 4);
        assertTrue(ctx.isInQueuedPreview());
        assertFalse(ctx.isFirstInQueue());
    }

    // ── ThinkingStyle ────────────────────────────────────────────────────────

    @Test
    void thinkingStyle_NORMAL_labelIsYouColor() {
        assertEquals("briefLabelYou", ThinkingStyle.NORMAL.labelStyle());
        assertEquals("text",          ThinkingStyle.NORMAL.tokenStyle());
    }

    @Test
    void thinkingStyle_DIM_labelIsSubtle() {
        assertEquals("subtle", ThinkingStyle.DIM.labelStyle());
        assertEquals("subtle", ThinkingStyle.DIM.tokenStyle());
    }

    @Test
    void thinkingStyle_forContext_returnsDimWhenQueued() {
        assertSame(ThinkingStyle.DIM,    ThinkingStyle.forContext(RenderingContext.queuedPreview(true, 4)));
        assertSame(ThinkingStyle.NORMAL, ThinkingStyle.forContext(RenderingContext.NORMAL));
    }

    // ── ToolUseIndicatorRenderer.pick ────────────────────────────────────────

    @Test
    void pick_returnsDotRendererForQueued() {
        assertSame(DotIndicatorRenderer.INSTANCE,
            ToolUseIndicatorRenderer.pick(RenderingContext.queuedPreview(true, 4)));
    }

    @Test
    void pick_returnsSpinnerRendererForNormal() {
        assertSame(SpinnerIndicatorRenderer.INSTANCE,
            ToolUseIndicatorRenderer.pick(RenderingContext.NORMAL));
    }

    // ── DotIndicatorRenderer ─────────────────────────────────────────────────

    @Test
    void dotRenderer_dotTextContainsBlackCircle() {
        // DOT_TEXT = Figures.BLACK_CIRCLE + " "
        assertTrue(Strings.CS.startsWith(DotIndicatorRenderer.DOT_TEXT, Figures.BLACK_CIRCLE),
            "DOT_TEXT must start with BLACK_CIRCLE");
        assertEquals(Figures.BLACK_CIRCLE.length() + 1, DotIndicatorRenderer.DOT_TEXT.length(),
            "DOT_TEXT must be BLACK_CIRCLE + one trailing space (minWidth=2 box)");
    }

    @Test
    void dotRenderer_render_appendsOneDimLine() throws Exception {
        MessagePanel panel = new MessagePanel();
        DotIndicatorRenderer.INSTANCE.render(panel, RenderingContext.queuedPreview(true, 4));

        List<MessagePanel.StyledLine> lines = linesOf(panel);
        assertEquals(1, lines.size(), "render() must append exactly one line");

        MessagePanel.StyledLine line = lines.getFirst();
        assertEquals(1, line.segments().size(), "line must have exactly one segment");
        assertEquals(DotIndicatorRenderer.DOT_TEXT, line.segments().getFirst().text());
    }

    @Test
    void dotRenderer_segment_hasDimColor() throws Exception {
        MessagePanel panel = new MessagePanel();
        DotIndicatorRenderer.INSTANCE.render(panel, RenderingContext.NORMAL);

        List<MessagePanel.StyledLine> lines = linesOf(panel);
        TextColor color = lines.getFirst().segments().getFirst().color();
// dim color must equal LanternaTheme.welcomeDim which is inactive
        assertNotNull(color);
        assertNotEquals(TextColor.ANSI.DEFAULT, color,
            "dot segment must not use terminal-default color (must be dim)");
    }

    // ── SpinnerIndicatorRenderer ──────────────────────────────────────────────

    @Test
    void spinnerRenderer_render_appendsOneBlinkingLine() throws Exception {
        MessagePanel panel = new MessagePanel();
        SpinnerIndicatorRenderer.INSTANCE.render(panel, RenderingContext.NORMAL);

        List<MessagePanel.StyledLine> lines = linesOf(panel);
        assertEquals(1, lines.size(), "render() must append exactly one line");

        MessagePanel.StyledLine line = lines.getFirst();
        assertFalse(line.segments().isEmpty(), "line must have at least one segment");
        // The "on" segment must contain the BLACK_CIRCLE glyph
        String text = line.segments().getFirst().text();
        assertTrue(Strings.CS.contains(text, Figures.BLACK_CIRCLE),
            "spinner on-segment must contain BLACK_CIRCLE: " + text);
    }

    @Test
    void spinnerRenderer_hasBrandColor() throws Exception {
        MessagePanel panel = new MessagePanel();
        SpinnerIndicatorRenderer.INSTANCE.render(panel, RenderingContext.NORMAL);

        List<MessagePanel.StyledLine> lines = linesOf(panel);
        TextColor color = lines.getFirst().segments().getFirst().color();
        // Should match assistantDot() / claude() color, not dim
        assertEquals(LanternaTheme.assistantDot(), color,
            "spinner on-segment must use assistantDot() brand color");
    }

    // ── HighlightedThinkingRenderer ───────────────────────────────────────────

    @Test
    void highlightedThinking_render_emitsLabelAndTokenLines() throws Exception {
        MessagePanel panel = new MessagePanel();
        HighlightedThinkingRenderer.INSTANCE.render(
            "Hello thinking", panel, RenderingContext.NORMAL);

        List<MessagePanel.StyledLine> lines = linesOf(panel);
        // Expect: line 0 = label "∴ Thinking", line 1 = indented token text
        assertTrue(lines.size() >= 2, "must emit at least 2 lines (label + token)");
        assertEquals(HighlightedThinkingRenderer.LABEL, lines.getFirst().text());
    }

    @Test
    void highlightedThinking_render_tokenLineHasIndent() throws Exception {
        MessagePanel panel = new MessagePanel();
        HighlightedThinkingRenderer.INSTANCE.render(
            "some thought", panel, RenderingContext.NORMAL);

        List<MessagePanel.StyledLine> lines = linesOf(panel);
        // Second line should be a multi-segment line: indent + token text
        MessagePanel.StyledLine tokenLine = lines.get(1);
        assertEquals(2, tokenLine.segments().size(),
            "token line must have 2 segments: indent + text");
        assertEquals(HighlightedThinkingRenderer.INDENT, tokenLine.segments().getFirst().text());
        assertEquals("some thought", tokenLine.segments().get(1).text());
    }

    @Test
    void highlightedThinking_normalContext_usesYouLabelColor() throws Exception {
        MessagePanel panel = new MessagePanel();
        HighlightedThinkingRenderer.INSTANCE.render(
            "thought", panel, RenderingContext.NORMAL);

        List<MessagePanel.StyledLine> lines = linesOf(panel);
        TextColor labelColor = lines.getFirst().color();
        assertEquals(LanternaTheme.briefLabelYou(), labelColor,
            "normal context must use briefLabelYou() for the label");
    }

    @Test
    void highlightedThinking_queuedContext_usesDimColor() throws Exception {
        MessagePanel panel = new MessagePanel();
        HighlightedThinkingRenderer.INSTANCE.render(
            "thought", panel, RenderingContext.queuedPreview(true, 4));

        List<MessagePanel.StyledLine> lines = linesOf(panel);
        TextColor labelColor = lines.getFirst().color();
        assertEquals(LanternaTheme.queuedText(), labelColor,
            "queued context must use queuedText() (dim/subtle) for the label");
    }

    @Test
    void highlightedThinking_emptyInput_appendsNothing() throws Exception {
        MessagePanel panel = new MessagePanel();
        HighlightedThinkingRenderer.INSTANCE.render("", panel, RenderingContext.NORMAL);
        HighlightedThinkingRenderer.INSTANCE.render(null, panel, RenderingContext.NORMAL);

        List<MessagePanel.StyledLine> lines = linesOf(panel);
        assertEquals(0, lines.size(), "empty/null input must produce no output");
    }

    @Test
    void highlightedThinking_multilineText_emitsMultipleTokenLines() throws Exception {
        MessagePanel panel = new MessagePanel();
        HighlightedThinkingRenderer.INSTANCE.render(
            "line one\nline two", panel, RenderingContext.NORMAL);

        List<MessagePanel.StyledLine> lines = linesOf(panel);
        // 1 label + 2 token lines
        assertEquals(3, lines.size(), "must emit label + 2 token lines for 2-line input");
    }

    // ── HighlightedThinkingRenderer color resolution ─────────────────────────

    @Test
    void resolveLabelColor_normalStyle_isBriefLabelYou() {
        assertEquals(LanternaTheme.briefLabelYou(),
            HighlightedThinkingRenderer.resolveLabelColor(ThinkingStyle.NORMAL));
    }

    @Test
    void resolveLabelColor_dimStyle_isQueuedText() {
        assertEquals(LanternaTheme.queuedText(),
            HighlightedThinkingRenderer.resolveLabelColor(ThinkingStyle.DIM));
    }

    @Test
    void resolveTokenColor_normalStyle_isInputText() {
        assertEquals(LanternaTheme.inputText(),
            HighlightedThinkingRenderer.resolveTokenColor(ThinkingStyle.NORMAL));
    }

    @Test
    void resolveTokenColor_dimStyle_isQueuedText() {
        assertEquals(LanternaTheme.queuedText(),
            HighlightedThinkingRenderer.resolveTokenColor(ThinkingStyle.DIM));
    }
}

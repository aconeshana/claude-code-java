package com.claudecode.ui.lanterna.transcript;

import com.claudecode.core.message.ThinkingBlock;
import com.claudecode.ui.MarkdownRenderer;
import com.claudecode.ui.lanterna.components.AnsiToSegments;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.render.HighlightedThinkingRenderer;
import com.claudecode.ui.render.RenderingContext;
import com.googlecode.lanterna.SGR;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;

/**
 * Renders completed thinking blocks and decides which of them a view may show: hidden in
 * the normal view, dimmed Markdown under a {@code ∴} gutter in verbose/transcript mode,
 * and in the transcript only the single most recent block unless the reader asks for
 * history.
 *
 * <ul>
 *   <li>{@code src/components/messages/AssistantThinkingMessage.tsx} — the {@code ∴}
 *       gutter column, dim italic body, no label row.</li>
 *   <li>{@code src/utils/messages.ts} — {@code findLastThinkingBlockId}: transcript replay
 *       shows only the last completed thinking block.</li>
 *   <li>{@code src/components/messages/QueuedThinkingPreview.tsx} — queued-preview
 *       context delegates to {@link HighlightedThinkingRenderer}.</li>
 * </ul>
 */
final class ThinkingRenderer {

    /** Visibility flags owned by the dispatcher. */
    interface Host {
        boolean verbose();
        boolean transcriptMode();
    }

    private static final MarkdownRenderer MARKDOWN_RENDERER = MarkdownRenderer.shared();

    private final Host host;

    ThinkingRenderer(Host host) {
        this.host = host;
    }

    /** Two-column gutter carrying the thinking glyph on the first body row. */
    private static final String THINKING_GUTTER = "∴ ";
    /** Continuation rows align under the gutter. */
    private static final String THINKING_INDENT = "  ";

    /** Whether transcript replay should hide every completed thinking block except one. */
    private boolean hidePastThinking = false;

    private String visibleTranscriptThinkingBlockId;

    void showOnlyBlock(String blockId) {
        hidePastThinking = true;
        visibleTranscriptThinkingBlockId = blockId;
    }

    /**
     * Renders a completed thinking block, adapting glyph/token colors based on {@code ctx}.
     *
     * <p>In queued-preview context, delegates to {@link HighlightedThinkingRenderer}
     * which uses {@link com.claudecode.ui.render.ThinkingStyle#forContext(RenderingContext)}
     * to pick dim/subtle styling. Outside that context the body is hidden entirely unless
     * {@code host.verbose()} or {@code host.transcriptMode()} is on, and is then laid out as a
     * {@link #THINKING_GUTTER} column followed by dim Markdown.
     */
    void render(ThinkingBlock thinking, MessagePanel panel, RenderingContext ctx) {

        // completed thinking is hidden in the normal non-host.verbose() view;
        // host.verbose()/transcript renders the full dimmed Markdown body.
        String thinkingText = thinking.thinking();
        if (StringUtils.isEmpty(thinkingText)) return;

        // Queued-preview: use HighlightedThinkingRenderer with context-driven color selection.
        if (ctx.isInQueuedPreview()) {
            HighlightedThinkingRenderer.INSTANCE.render(thinkingText, panel, ctx);
            return;
        }

        if (!host.verbose() && !host.transcriptMode()) return;
        // Verbose/transcript: a 2-column dim+italic "∴" gutter on the FIRST body row,
        // then the dim Markdown column. No label word and no gap row — the "Thinking"
        // wording belongs to the spinner and the queued-preview renderer, not here.
        String rendered = MARKDOWN_RENDERER.renderDimmed(
            thinkingText.trim(), markdownWidth(panel) - 2);
        List<List<MessagePanel.Segment>> mdLines = AnsiToSegments.ansiToLines(rendered, LanternaTheme.welcomeDim());
        int last = mdLines.size();
        while (last > 0 && mdLines.get(last - 1).isEmpty()) last--;
        MessagePanel.Segment gutter = new MessagePanel.Segment(THINKING_GUTTER,
            LanternaTheme.welcomeDim(), null, null, Set.of(SGR.ITALIC));
        if (last == 0) {
            // An all-whitespace body still renders the gutter: the glyph is an
            // unconditional column, not a decoration on the first text row.
            panel.appendMixed(List.of(gutter));
            return;
        }
        for (int i = 0; i < last; i++) {
            List<MessagePanel.Segment> line = mdLines.get(i);
            List<MessagePanel.Segment> indented = new ArrayList<>(line.size() + 1);
            indented.add(i == 0 ? gutter
                : new MessagePanel.Segment(THINKING_INDENT, LanternaTheme.welcomeDim()));
            for (MessagePanel.Segment segment : line) {
                indented.add(new MessagePanel.Segment(segment.text(),
                    LanternaTheme.welcomeDim(), segment.bgColor(),
                    segment.hyperlinkUrl(), segment.modifiers()));
            }
            panel.appendMixed(indented);
        }
    }

    boolean shouldRenderCompleted(String blockId) {
        if (!host.verbose() && !host.transcriptMode()) return false;
        return !host.transcriptMode()
            || !hidePastThinking
            || Objects.equals(visibleTranscriptThinkingBlockId, blockId);
    }

    static int markdownWidth(MessagePanel panel) {
        var size = panel.getSize();
        int panelWidth = size == null || size.getColumns() <= 0 ? 80 : size.getColumns();
        return Math.max(1, panelWidth - 2); // assistant dot/hanging gutter
    }
}

package com.claudecode.ui.render;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.transcript.MessagePanel;
import com.googlecode.lanterna.TextColor;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a thinking-block label and token text into a {@link MessagePanel}, adapting label and
 * token colors based on {@link RenderingContext} via {@link
 * ThinkingStyle#forContext(RenderingContext)}.
 */
public final class HighlightedThinkingRenderer {

    /** Singleton — this renderer is stateless. */
    public static final HighlightedThinkingRenderer INSTANCE = new HighlightedThinkingRenderer();

/**
     * Label text shown before the thinking token.
     */
    static final String LABEL = "∴ Thinking";

/**
     * Indent prefix applied to the token text lines.
     */
    static final String INDENT = "  ";

    private HighlightedThinkingRenderer() {}

    /**
     * Emits the thinking label and token text into {@code panel}, using
     * colors derived from {@link ThinkingStyle#forContext(RenderingContext)}.
     *
     * <p>Output format (two lines):
     * <pre>
     *   ∴ Thinking          ← label color
     *     &lt;token text&gt;      ← token color, 2-space indent
     * </pre>
     *
     * @param thinkingText the raw thinking text content; must not be {@code null}
     * @param panel        the target panel; must not be {@code null}
     * @param ctx          the current rendering context; must not be {@code null}
     */
    public void render(String thinkingText, MessagePanel panel, RenderingContext ctx) {
        if (StringUtils.isEmpty(thinkingText)) return;

        ThinkingStyle style = ThinkingStyle.forContext(ctx);
        TextColor labelColor = resolveLabelColor(style);
        TextColor tokenColor = resolveTokenColor(style);

        // Emit label line
        panel.appendLine(LABEL, labelColor);

        // Emit token text lines with 2-space indent; split on embedded newlines
        String[] lines = thinkingText.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1);
        int last = lines.length;
        while (last > 0 && lines[last - 1].isEmpty()) last--;

        for (int i = 0; i < last; i++) {
            List<MessagePanel.Segment> segments = new ArrayList<>(2);
            segments.add(new MessagePanel.Segment(INDENT, labelColor));
            segments.add(new MessagePanel.Segment(lines[i], tokenColor));
            panel.appendMixed(segments);
        }
    }

    /**
     * Maps the {@code ThinkingStyle.labelStyle} token to a Lanterna {@link TextColor}.
     */
    static TextColor resolveLabelColor(ThinkingStyle style) {
        return Strings.CS.equals("briefLabelYou", style.labelStyle())
            ? LanternaTheme.briefLabelYou()
            : LanternaTheme.queuedText();
    }

    /**
     * Maps the {@code ThinkingStyle.tokenStyle} token to a Lanterna {@link TextColor}.
     */
    static TextColor resolveTokenColor(ThinkingStyle style) {
        return Strings.CS.equals("text", style.tokenStyle())
            ? LanternaTheme.inputText()
            : LanternaTheme.queuedText();
    }
}

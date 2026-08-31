package com.claudecode.ui.render;

import com.claudecode.core.constants.Figures;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.transcript.MessagePanel;

import java.util.List;

/**
 * Renders the queued-preview indicator: a static dim filled circle (BLACK_CIRCLE).
 */
public final class DotIndicatorRenderer implements ToolUseIndicatorRenderer {

    /** Singleton — this renderer is stateless. */
    public static final DotIndicatorRenderer INSTANCE = new DotIndicatorRenderer();

    /**
     * Trailing space after the circle.
     */
    static final String DOT_TEXT = Figures.BLACK_CIRCLE + " ";

    private DotIndicatorRenderer() {}

    /**
     * {@inheritDoc} Emits BLACK_CIRCLE (⏺ / ●) followed by a single space with dim/subtle style via
     * {@link MessagePanel#appendMixed(List)}.
     */
    @Override
    public void render(MessagePanel panel, RenderingContext ctx) {
        panel.appendMixed(List.of(
            new MessagePanel.Segment(DOT_TEXT, LanternaTheme.welcomeDim())
        ));
    }
}

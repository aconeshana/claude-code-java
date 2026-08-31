package com.claudecode.ui.render;

import com.claudecode.core.constants.Figures;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.transcript.MessagePanel;

import java.util.List;

/**
 * Renders the normal in-progress indicator: an animated spinner (blinking BLACK_CIRCLE).
 */
public final class SpinnerIndicatorRenderer implements ToolUseIndicatorRenderer {

    /** Singleton — this renderer is stateless. */
    public static final SpinnerIndicatorRenderer INSTANCE = new SpinnerIndicatorRenderer();

    private SpinnerIndicatorRenderer() {}

    /**
     * {@inheritDoc} Appends an in-progress tool indicator and starts blinking it.
     */
    @Override
    public void render(MessagePanel panel, RenderingContext ctx) {
        // Append the blinking dot line and immediately start the blink animation.
        // The "on" segments show the BLACK_CIRCLE in the active/claude color;
        // startBlinkLine blanks the first segment for the "off" frame automatically.
        List<MessagePanel.Segment> onSegs = List.of(
            new MessagePanel.Segment(Figures.BLACK_CIRCLE + " ", LanternaTheme.assistantDot())
        );
        int lineIdx = panel.snapshotLineCount();
        panel.appendMixed(onSegs);
        panel.startBlinkLine(lineIdx, onSegs);
    }
}

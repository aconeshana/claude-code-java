package com.claudecode.ui.lanterna.status;

import com.claudecode.ui.lanterna.components.AnsiToSegments;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.AbstractComponent;
import com.googlecode.lanterna.gui2.ComponentRenderer;
import com.googlecode.lanterna.gui2.TextGUIGraphics;

import java.util.List;
import com.claudecode.ui.lanterna.components.SpinnerComponent;
import org.apache.commons.lang3.StringUtils;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.transcript.MessagePanel;





public final class StatusLineComponent extends AbstractComponent<StatusLineComponent> {

    private List<List<MessagePanel.Segment>> lines = List.of();
    private int padding = 0;

    /**
     * Sets the rendered text from raw command stdout (may contain ANSI codes
     * and multiple lines) and the horizontal padding. Must run on the GUI thread.
     */
    public void setStatusText(String ansiText, int padding) {
        this.padding = Math.max(0, padding);
        this.lines = (StringUtils.isEmpty(ansiText))
            ? List.of()
            : AnsiToSegments.ansiToLines(ansiText, LanternaTheme.welcomeDim());
        invalidate();
    }

    /** Clears the status line (collapses to zero height). */
    public void clear() {
        this.lines = List.of();
        invalidate();
    }

    boolean hasText() { return !lines.isEmpty(); }

    @Override
    protected ComponentRenderer<StatusLineComponent> createDefaultRenderer() {
        return new Renderer();
    }

    private final class Renderer implements ComponentRenderer<StatusLineComponent> {

        @Override
        public TerminalSize getPreferredSize(StatusLineComponent c) {
            if (lines.isEmpty()) return new TerminalSize(0, 0);
            int cols = 0;
            for (List<MessagePanel.Segment> line : lines) {
                int w = padding;
                for (MessagePanel.Segment s : line) w += s.text().length();
                cols = Math.max(cols, w + padding);
            }
            // Width is stretched to the footer via Alignment.FILL; preferred
            // height is what matters (one row per output line).
            return new TerminalSize(Math.max(1, cols), lines.size());
        }

        @Override
        public void drawComponent(TextGUIGraphics g, StatusLineComponent c) {
            if (lines.isEmpty()) return;
            g.fill(' ');
            int maxCols = g.getSize().getColumns();
            int limit = Math.max(0, maxCols - padding);  // right inset

            for (int row = 0; row < lines.size() && row < g.getSize().getRows(); row++) {
                int col = padding;
                for (MessagePanel.Segment seg : lines.get(row)) {
                    TextColor fg = seg.color() != null ? seg.color() : LanternaTheme.welcomeDim();
                    TextColor bg = seg.bgColor() != null ? seg.bgColor() : TextColor.ANSI.DEFAULT;
                    String text = seg.text();
                    for (int i = 0; i < text.length(); i++) {
                        if (col >= limit) break;
                        char ch = text.charAt(i);
                        // Lanterna's TextCharacter rejects control bytes (0x1B etc.)
                        // and throws mid-draw. The status line runs arbitrary user
                        // commands, so defensively skip any control char that slips
                        // through the ANSI parser (zero-width — don't advance col).
                        if (ch < 0x20 && ch != '\t') continue;
                        g.setCharacter(col, row, TextCharacter.fromCharacter(ch, fg, bg));
                        col++;
                    }
                    if (col >= limit) break;
                }
            }
        }
    }
}

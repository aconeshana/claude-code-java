package com.claudecode.ui.lanterna.dialog.question;

import com.claudecode.ui.lanterna.transcript.MessagePanel.Segment;
import com.googlecode.lanterna.TerminalTextUtils;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import java.util.List;
import org.apache.commons.lang3.StringUtils;

/**
 * Blits lines of styled {@link Segment}s into a component's graphics.
 *
 * <p>No bundle counterpart: Ink paints a component tree, so the layout code there never touches
 * cells. The views in this package emit segment lines instead of drawing directly, which keeps
 * their geometry unit-testable; this class is the one place that turns those lines into cells.
 *
 * <p>The cell rules follow {@code MessagePanel.drawSegments}: control characters are skipped, a
 * double-width glyph advances two columns, and a segment's hyperlink is attached per character.
 */
final class SegmentPainter {

    private SegmentPainter() {}

    /** Paints {@code lines} from the top-left corner, clipping to the assigned size. */
    static void paint(TextGUIGraphics graphics, List<List<Segment>> lines) {
        int rows = graphics.getSize().getRows();
        int columns = graphics.getSize().getColumns();
        int limit = Math.min(lines.size(), rows);
        for (int y = 0; y < limit; y++) paintRow(graphics, y, lines.get(y), columns);
    }

    private static void paintRow(
            TextGUIGraphics graphics, int y, List<Segment> segments, int columns) {
        int x = 0;
        for (Segment segment : segments) {
            TextColor color = segment.color() != null ? segment.color() : TextColor.ANSI.DEFAULT;
            TextColor background =
                segment.bgColor() != null ? segment.bgColor() : TextColor.ANSI.DEFAULT;
            String text = segment.text();
            for (int index = 0; index < text.length() && x < columns; index++) {
                char glyph = text.charAt(index);
                if (Character.isISOControl(glyph)) continue;
                TextCharacter character = TextCharacter.fromCharacter(glyph, color, background);
                if (!segment.modifiers().isEmpty()) {
                    character = character.withModifiers(segment.modifiers());
                }
                if (StringUtils.isNotEmpty(segment.hyperlinkUrl())) {
                    character = character.withHyperlink(segment.hyperlinkUrl());
                }
                graphics.setCharacter(x, y, character);
                x += TerminalTextUtils.isCharDoubleWidth(glyph) ? 2 : 1;
            }
        }
    }
}

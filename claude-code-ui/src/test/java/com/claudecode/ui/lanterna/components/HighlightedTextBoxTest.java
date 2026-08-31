package com.claudecode.ui.lanterna.components;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.claudecode.ui.lanterna.input.PromptTextLayout;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.BasicTextImage;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.gui2.TextGUIGraphicsBridge;
import java.util.List;
import org.junit.jupiter.api.Test;

class HighlightedTextBoxTest {
    @Test void resolvesPriorityAndOverlapLikeTsSegmenter() {
        var low = new HighlightedTextBox.Highlight(0, 4, TextColor.ANSI.RED, false, 1);
        var high = new HighlightedTextBox.Highlight(0, 2, TextColor.ANSI.BLUE, false, 5);
        var overlap = new HighlightedTextBox.Highlight(1, 3, TextColor.ANSI.GREEN, false, 9);
        var later = new HighlightedTextBox.Highlight(4, 6, TextColor.ANSI.YELLOW, false, 0);
        assertEquals(List.of(high, later),
            HighlightedTextBox.resolveHighlights(List.of(low, overlap, later, high)));
    }

    @Test void replacingTextResetsStaleHorizontalViewport() {
        var box = new HighlightedTextBox(new TerminalSize(8, 1), TextBox.Style.SINGLE_LINE, List::of);
        var renderer = box.getRenderer();
        renderer.setViewTopLeft(TerminalPosition.of(5, 0));

        box.setText("abc");

        assertEquals(TerminalPosition.of(0, 0), renderer.getViewTopLeft());
    }

    @Test void visualLayoutPaintsSoftWrappedRowsWithoutMutatingText() {
        var box = new HighlightedTextBox(new TerminalSize(7, 2), TextBox.Style.MULTI_LINE,
            List::of);
        PromptTextLayout layout = PromptTextLayout.create("alpha beta", 7);
        box.setVisualLayoutSupplier(() -> layout, () -> 9);
        box.setText("alpha beta");
        box.setSize(new TerminalSize(7, 2));
        var image = new BasicTextImage(new TerminalSize(7, 2));

        box.getRenderer().drawComponent(
            TextGUIGraphicsBridge.wrap(null, image.newTextGraphics()), box);

        assertEquals("alpha  ", rowText(image, 0));
        assertEquals("beta   ", rowText(image, 1));
        assertEquals("alpha beta", box.getText());
        assertEquals(TerminalPosition.of(3, 1), box.getRenderer().getCursorLocation(box));
    }

    @Test void visualCursorUsesDisplayColumnsForWideCharacters() {
        var box = new HighlightedTextBox(new TerminalSize(4, 2), TextBox.Style.MULTI_LINE,
            List::of);
        PromptTextLayout layout = PromptTextLayout.create("中文A", 5);
        box.setVisualLayoutSupplier(() -> layout, () -> 1);
        box.setText("中文A");

        assertEquals(TerminalPosition.of(2, 0), box.getRenderer().getCursorLocation(box));
    }

    private static String rowText(BasicTextImage image, int row) {
        StringBuilder text = new StringBuilder(image.getSize().getColumns());
        for (int column = 0; column < image.getSize().getColumns(); column++) {
            text.append(image.getCharacterAt(column, row).getCharacter());
        }
        return text.toString();
    }
}

package com.claudecode.ui.lanterna.input;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.TextBox;
import org.junit.jupiter.api.Test;

class TextBoxOffsetAdapterTest {

    @Test
    void convertsSecondLineCaretToAbsoluteOffset() {
        TextBox box = multiline("abc\nDEF");
        box.setCaretPosition(1, 1);

        assertEquals(5, TextBoxOffsetAdapter.offset(box));
    }

    @Test
    void setsAbsoluteOffsetOnSecondLine() {
        TextBox box = multiline("abc\nDEF");

        TextBoxOffsetAdapter.setOffset(box, 6);

        assertEquals(1, box.getCaretPosition().getRow());
        assertEquals(2, box.getCaretPosition().getColumn());
    }

    @Test
    void distinguishesBeforeAndAfterNewline() {
        TextBox box = multiline("abc\nDEF");

        TextBoxOffsetAdapter.setOffset(box, 3);
        assertEquals(0, box.getCaretPosition().getRow());
        assertEquals(3, box.getCaretPosition().getColumn());

        TextBoxOffsetAdapter.setOffset(box, 4);
        assertEquals(1, box.getCaretPosition().getRow());
        assertEquals(0, box.getCaretPosition().getColumn());
    }

    @Test
    void reportsCurrentLogicalLineBounds() {
        TextBox box = multiline("abc\nDEF\nghi");
        box.setCaretPosition(1, 1);

        assertEquals(4, TextBoxOffsetAdapter.logicalLineStart(box));
        assertEquals(7, TextBoxOffsetAdapter.logicalLineEnd(box));
    }

    private static TextBox multiline(String text) {
        TextBox box = new TextBox(new TerminalSize(20, 4), TextBox.Style.MULTI_LINE);
        box.setText(text);
        return box;
    }
}

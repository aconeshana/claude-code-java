package com.claudecode.ui.lanterna.input;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.gui2.TextBox;





final class TextBoxOffsetAdapter {

    private TextBoxOffsetAdapter() {}

    static int offset(TextBox textBox) {
        if (textBox == null) return 0;
        TerminalPosition caret = textBox.getCaretPosition();
        int row = Math.max(0, Math.min(caret.getRow(), textBox.getLineCount() - 1));
        int result = 0;
        for (int i = 0; i < row; i++) {
            result += textBox.getLine(i).length() + 1;
        }
        int column = Math.max(0, Math.min(caret.getColumn(), textBox.getLine(row).length()));
        return Math.min(result + column, textBox.getText().length());
    }

    static void setOffset(TextBox textBox, int offset) {
        if (textBox == null) return;
        int target = Math.max(0, Math.min(offset, textBox.getText().length()));
        int consumed = 0;
        int lastRow = Math.max(0, textBox.getLineCount() - 1);
        for (int row = 0; row <= lastRow; row++) {
            int lineLength = textBox.getLine(row).length();
            if (target <= consumed + lineLength) {
                textBox.setCaretPosition(row, target - consumed);
                return;
            }
            consumed += lineLength;
            if (row < lastRow) consumed++;
        }
        textBox.setCaretPosition(lastRow, textBox.getLine(lastRow).length());
    }

    static int logicalLineStart(TextBox textBox) {
        int caret = offset(textBox);
        String text = textBox.getText();
        if (caret > 0 && text.charAt(caret - 1) == '\n') return caret - 1;
        int newline = text.lastIndexOf('\n', Math.max(0, caret - 1));
        return newline < 0 ? 0 : newline + 1;
    }

    static int logicalLineEnd(TextBox textBox) {
        int caret = offset(textBox);
        String text = textBox.getText();
        if (caret < text.length() && text.charAt(caret) == '\n') return caret + 1;
        int newline = text.indexOf('\n', caret);
        return newline < 0 ? text.length() : newline;
    }
}

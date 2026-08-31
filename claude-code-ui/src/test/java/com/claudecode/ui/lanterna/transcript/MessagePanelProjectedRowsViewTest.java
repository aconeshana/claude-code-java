package com.claudecode.ui.lanterna.transcript;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.googlecode.lanterna.TextColor;
import org.junit.jupiter.api.Test;

class MessagePanelProjectedRowsViewTest {

    @Test
    void projectedRowsDoNotDuplicateTheWholeFlattenedTranscript() {
        MessagePanel panel = new MessagePanel();
        for (int i = 0; i < 500; i++) {
            panel.appendLine("history " + i + " ".repeat(80), TextColor.ANSI.DEFAULT);
        }

        panel.displayRowsForTest(40);
        panel.appendLine("streamed tail", TextColor.ANSI.DEFAULT);
        panel.displayRowsForTest(40);

        assertTrue(panel.usesProjectedRowsViewForTest(40),
            "the cached projection should be an indexed view over source rows, not a second "
                + "full list copied on every streamed revision");
    }
}

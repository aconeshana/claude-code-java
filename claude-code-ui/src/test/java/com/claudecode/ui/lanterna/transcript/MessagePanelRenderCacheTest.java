package com.claudecode.ui.lanterna.transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.googlecode.lanterna.TextColor;
import org.junit.jupiter.api.Test;

class MessagePanelRenderCacheTest {

    @Test
    void unchangedTranscriptReusesWrappedRowsAcrossInputFrames() {
        MessagePanel panel = new MessagePanel();
        for (int i = 0; i < 200; i++) {
            panel.appendLine("long transcript row " + i + " ".repeat(20),
                TextColor.ANSI.DEFAULT);
        }

        var first = panel.displayRowsForTest(80);
        var second = panel.displayRowsForTest(80);

        assertSame(first, second);
        assertEquals(1, panel.renderLayoutBuildCountForTest());
    }

    @Test
    void contentAndWidthChangesInvalidateWrappedRows() {
        MessagePanel panel = new MessagePanel();
        panel.appendLine("one line", TextColor.ANSI.DEFAULT);

        panel.displayRowsForTest(80);
        panel.displayRowsForTest(40);
        panel.appendLine("another line", TextColor.ANSI.DEFAULT);
        panel.displayRowsForTest(40);

        assertEquals(3, panel.renderLayoutBuildCountForTest());
    }

    @Test
    void messageActionSelectionInvalidatesBackgroundProjection() {
        MessagePanel panel = new MessagePanel();
        panel.appendLine("prompt", TextColor.ANSI.DEFAULT);
        panel.registerLogicalMessage("user", MessagePanel.LogicalMessageKind.USER,
            0, 0, "prompt", "prompt", null, null, false);
        panel.displayRowsForTest(80);

        panel.enterMessageActions();
        panel.displayRowsForTest(80);

        assertEquals(2, panel.renderLayoutBuildCountForTest());
    }

    @Test
    void streamedTailAppendReusesAllPreviouslyProjectedSourceRows() {
        MessagePanel panel = new MessagePanel();
        for (int i = 0; i < 500; i++) {
            panel.appendLine("history " + i + " ".repeat(40), TextColor.ANSI.DEFAULT);
        }
        panel.displayRowsForTest(80);
        assertEquals(500, panel.sourceProjectionBuildCountForTest());

        panel.appendLine("new streamed tail", TextColor.ANSI.DEFAULT);
        panel.displayRowsForTest(80);

        assertEquals(501, panel.sourceProjectionBuildCountForTest());
    }
}

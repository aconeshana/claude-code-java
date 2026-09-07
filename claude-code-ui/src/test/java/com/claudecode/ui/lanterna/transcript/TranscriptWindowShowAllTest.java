package com.claudecode.ui.lanterna.transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.UserMessage;
import com.googlecode.lanterna.input.KeyStroke;
import org.junit.jupiter.api.Test;

/**
 * 2.1.236 parity for {@code showAllInTranscript}: without virtual scroll the
 * transcript renders only the last 30 messages, and Ctrl+E toggles the rest.
 */
class TranscriptWindowShowAllTest {

    private static final int CAP = 30;

    private static KeyStroke ctrl(char c) { return new KeyStroke(c, true, false); }

    private static SDKMessage user(String uuid, String text) {
        return new SDKMessage.User(new UserMessage(uuid, MessageContent.ofText(text)));
    }

    @Test
    void historyBeyondCapIsHiddenUntilCtrlE() {
        MessageHistory history = new MessageHistory();
        for (int i = 0; i < CAP + 10; i++) {
            history.record(user("u" + i, "message " + i));
        }

        TranscriptWindow win = new TranscriptWindow(history, () -> { });
        MessagePanel panel = win.transcriptPanel();

        assertEquals(1, panel.searchLines("ctrl+e to show 10 previous messages").size(),
            "the hidden-history hint must render once; panel: " + win.footerText());
        assertTrue(panel.searchLines("message 0").isEmpty(),
            "messages older than the 30-message cap must not render");
        assertFalse(panel.searchLines("message 39").isEmpty(),
            "the newest message must render");

        win.handleInput(ctrl('e'));

        assertEquals(1, panel.searchLines("ctrl+e to hide 10 previous messages").size(),
            "showing all flips the hint to 'hide'");
        assertFalse(panel.searchLines("message 0").isEmpty(),
            "ctrl+e must reveal the full history");
    }

    @Test
    void historyWithinCapHasNoHint() {
        MessageHistory history = new MessageHistory();
        for (int i = 0; i < CAP; i++) {
            history.record(user("u" + i, "message " + i));
        }

        TranscriptWindow win = new TranscriptWindow(history, () -> { });
        MessagePanel panel = win.transcriptPanel();

        assertTrue(panel.searchLines("previous messages").isEmpty(),
            "no hint when nothing is hidden");
        assertFalse(panel.searchLines("message 0").isEmpty(),
            "everything fits, so even the oldest message renders");
    }
}

package com.claudecode.ui.lanterna.input;

import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.overlay.OverlayHost;
import com.claudecode.ui.lanterna.transcript.MessagePanel;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowInputRouterTest {

    @Test
    void resizeInvokesTheProjectionRefreshCallback() {
        AtomicInteger refreshes = new AtomicInteger();
        WindowInputRouter router = new WindowInputRouter(
            null, null, null, null, () -> { }, null, refreshes::incrementAndGet);

        router.onResized(null, TerminalSize.of(80, 24), TerminalSize.of(100, 30));

        assertEquals(1, refreshes.get());
    }

    @Test
    void theWheelStillScrollsTheTranscriptWhileAModalOverlayHoldsTheKeyboard() {
        // The released client keeps scrolling the transcript behind a pending AskUserQuestion
        // card: the wheel is a terminal gesture, not a keybinding. The overlay host therefore
        // declines wheel events so this router reaches its own MOUSE_EVENT branch.
        // Each direction gets its own router: MouseScrollHandler defers an immediate reversal as
        // a trackpad bounce, which would swallow the second event for reasons unrelated to the
        // overlay.
        RecordingPanel up = new RecordingPanel();
        GreedyOverlay overUp = new GreedyOverlay();
        routerOver(up, overUp).onInput(null, wheel(MouseActionType.SCROLL_UP),
            new AtomicBoolean(true));
        assertEquals(1, up.scrolls, "the wheel event reached the transcript");
        assertTrue(up.lastDelta > 0, "SCROLL_UP scrolls back through the transcript");
        assertEquals(0, overUp.keys.get(), "the card never sees the wheel");

        RecordingPanel down = new RecordingPanel();
        GreedyOverlay overDown = new GreedyOverlay();
        routerOver(down, overDown).onInput(null, wheel(MouseActionType.SCROLL_DOWN),
            new AtomicBoolean(true));
        assertEquals(1, down.scrolls);
        assertTrue(down.lastDelta < 0, "SCROLL_DOWN scrolls forward again");
        assertEquals(0, overDown.keys.get());
    }

    @Test
    void theKeyboardStaysExclusiveToTheOverlay() {
        RecordingPanel panel = new RecordingPanel();
        GreedyOverlay overlay = new GreedyOverlay();
        WindowInputRouter router = routerOver(panel, overlay);
        AtomicBoolean deliver = new AtomicBoolean(true);

        router.onInput(null, new KeyStroke(KeyType.PAGE_UP), deliver);

        assertEquals(1, overlay.keys.get(), "the card claims PageUp before the scroll switch");
        assertEquals(0, panel.scrolls, "the transcript must not page while the card is up");
        assertFalse(deliver.get());
    }

    private static WindowInputRouter routerOver(MessagePanel panel, InlineOverlay overlay) {
        OverlayHost host = new OverlayHost();
        host.register(overlay);
        return new WindowInputRouter(host, panel, null, null, () -> { }, null, () -> { });
    }

    private static MouseAction wheel(MouseActionType type) {
        return new MouseAction(type, 0, new TerminalPosition(4, 4));
    }

    /** Records scroll requests instead of laying out a real transcript. */
    private static final class RecordingPanel extends MessagePanel {
        private int scrolls;
        private int lastDelta;

        @Override
        public void scrollUp(int lines) {
            scrolls++;
            lastDelta = lines;
        }
    }

    /** An always-active overlay that swallows every key it is given. */
    private static final class GreedyOverlay implements InlineOverlay {
        private final AtomicInteger keys = new AtomicInteger();

        @Override public boolean isActive() { return true; }

        @Override public void handleKey(KeyStroke key, AtomicBoolean deliver) {
            keys.incrementAndGet();
            deliver.set(false);
        }
    }
}

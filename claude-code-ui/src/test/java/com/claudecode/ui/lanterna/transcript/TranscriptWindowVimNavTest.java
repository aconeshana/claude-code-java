package com.claudecode.ui.lanterna.transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.commons.lang3.Strings;


class TranscriptWindowVimNavTest {

    private MessageHistory history;
    private TranscriptWindow win;
    private MessagePanel panel;
    private AtomicBoolean closed;

    @BeforeEach
    void setUp() {
        history = new MessageHistory();
        closed = new AtomicBoolean(false);
        win = new TranscriptWindow(history, () -> closed.set(true));
        panel = win.transcriptPanel();
        // Pin viewport to 40 rows so half=20 / full=40 are deterministic.
        // 200 seeded lines → maxOffset = 200 - 40 = 160.
        panel.setSize(new TerminalSize(80, 40));
        for (int i = 0; i < 200; i++) {
            panel.appendLine("line " + i + " filler text for transcript pager test",
                TextColor.ANSI.DEFAULT);
        }
    }

    private static KeyStroke chr(char c) { return new KeyStroke(c, false, false); }
    private static KeyStroke ctrl(char c) { return new KeyStroke(c, true, false); }
    private static KeyStroke special(KeyType t) { return new KeyStroke(t, false, false); }

    /** Reflective read of MessagePanel.scrollOffset (private int). */
    private static int offset(MessagePanel p) {
        try {
            Field f = MessagePanel.class.getDeclaredField("scrollOffset");
            f.setAccessible(true);
            return (int) f.get(p);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot read scrollOffset", e);
        }
    }

    /** Force a known scroll position (and clear autoScroll) for delta assertions. */
    private static void setOffset(MessagePanel p, int v) {
        try {
            Field f = MessagePanel.class.getDeclaredField("scrollOffset");
            f.setAccessible(true);
            f.set(p, v);
            Field a = MessagePanel.class.getDeclaredField("autoScroll");
            a.setAccessible(true);
            a.set(p, false);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot write scrollOffset", e);
        }
    }



    @Test
    void qExitsTranscript() {
        win.handleInput(chr('q'));
        assertTrue(closed.get(), "q exits transcript (less/tmux pager convention)");
    }

    @Test
    void ctrlCExitsTranscript() {
        win.handleInput(ctrl('c'));
        assertTrue(closed.get(), "ctrl+c exits transcript (TS transcript:exit)");
    }

    @Test
    void ctrlOExitsTranscript() {
        win.handleInput(ctrl('o'));
        assertTrue(closed.get());
    }

    @Test
    void escapeExitsTranscript() {
        win.handleInput(special(KeyType.ESCAPE));
        assertTrue(closed.get());
    }

    // ── line navigation ──

    @Test
    void jAndKScrollOneLine() {
        // Constructor scrolls to bottom → offset 0, autoScroll on.
        win.handleInput(chr('k'));
        assertEquals(1, offset(panel), "k scrolls up one line");
        win.handleInput(chr('j'));
        assertEquals(0, offset(panel), "j scrolls down one line");
    }

    @Test
    void ctrlNAndCtrlPScrollOneLine() {
        setOffset(panel, 80);
        win.handleInput(ctrl('n'));
        assertEquals(79, offset(panel), "ctrl+n scrolls down one line");
        win.handleInput(ctrl('p'));
        assertEquals(80, offset(panel), "ctrl+p scrolls up one line");
    }

    @Test
    void gScrollsToTop_GScrollsToBottom() {
        // g lands at maxOffset (lines - visibleRows = 200 - 40 = 160), the
// in-bounds top — not lines.size-1 (the overshoot scrollToTop used).
        win.handleInput(chr('g'));
        assertEquals(160, offset(panel), "g → top (maxOffset, in-bounds)");
        win.handleInput(chr('G'));
        assertEquals(0, offset(panel), "G → bottom");
    }

    @Test
    void kittyShiftGScrollsToBottom() {

        win.handleInput(new KeyStroke('g', false, true));
        assertEquals(0, offset(panel), "shift+g (kitty) → bottom");
    }



    @Test
    void ctrlUAndCtrlDHalfPage() {
        setOffset(panel, 80);
        win.handleInput(ctrl('u'));
        assertEquals(100, offset(panel), "ctrl+u scrolls up half page (20 rows)");
        win.handleInput(ctrl('d'));
        assertEquals(80, offset(panel), "ctrl+d scrolls down half page (20 rows)");
    }

    @Test
    void pageUpAndPageDownAreHalfPage() {

        setOffset(panel, 80);
        win.handleInput(special(KeyType.PAGE_UP));
        assertEquals(100, offset(panel), "PageUp scrolls up half page (20 rows)");
        win.handleInput(special(KeyType.PAGE_DOWN));
        assertEquals(80, offset(panel), "PageDown scrolls down half page (20 rows)");
    }

    // ── full-page (ctrl b/f, bare b, space) ──

    @Test
    void ctrlBAndCtrlFFullPage() {
        setOffset(panel, 80);
        win.handleInput(ctrl('b'));
        assertEquals(120, offset(panel), "ctrl+b scrolls up full page (40 rows)");
        win.handleInput(ctrl('f'));
        assertEquals(80, offset(panel), "ctrl+f scrolls down full page (40 rows)");
    }

    @Test
    void bareBAndSpaceFullPage() {
        // less pager: bare b = page up, space = page down (full viewport).
        setOffset(panel, 80);
        win.handleInput(chr('b'));
        assertEquals(120, offset(panel), "b scrolls up full page (40 rows)");
        win.handleInput(chr(' '));
        assertEquals(80, offset(panel), "space scrolls down full page (40 rows)");
    }

    // ── search-mode guard (letters are query chars, not navigation) ──

    @Test
    void vimKeysDoNotNavigateDuringSearch() {
        win.handleInput(chr('g'));
        assertEquals(160, offset(panel));

        win.handleInput(chr('/'));
        assertTrue(win.isSearching());
        win.handleInput(chr('g'));
        win.handleInput(chr('j'));
        assertEquals(160, offset(panel), "vim keys must NOT scroll during search");
        assertTrue(Strings.CS.contains(win.footerText(), "/gj"), "got: " + win.footerText());
    }

    @Test
    void scrollContextKeysStillScrollDuringSearch() {

        // PageUp/PageDown/ctrl+home/ctrl+end scroll even with search bar open.
        setOffset(panel, 80);
        win.handleInput(chr('/'));
        assertTrue(win.isSearching());

        win.handleInput(special(KeyType.PAGE_UP));
        assertEquals(100, offset(panel), "PageUp scrolls (half) during search");
        win.handleInput(special(KeyType.PAGE_DOWN));
        assertEquals(80, offset(panel), "PageDown scrolls (half) during search");

        win.handleInput(new KeyStroke(KeyType.HOME, true, false));
        assertEquals(160, offset(panel), "ctrl+home → top during search");
        win.handleInput(new KeyStroke(KeyType.END, true, false));
        assertEquals(0, offset(panel), "ctrl+end → bottom during search");
    }

    @Test
    void gDoesNotOvershootSoLineScrollWorksImmediately() {
// g must land within maxOffset (160), not at lines.size-1 (199),
        // otherwise the next 'j' would be a no-op until the excess drains.
        win.handleInput(chr('g'));
        int top = offset(panel);
        assertTrue(top <= 160, "g must not overshoot past maxOffset, got " + top);
        // From top, one 'j' must move the view down by one line.
        win.handleInput(chr('j'));
        assertEquals(top - 1, offset(panel), "j moves immediately after g");
    }

    @Test
    void fullPageClampsToBoundaryWithoutException() {
        win.handleInput(ctrl('b')); // up 40  → 40
        assertEquals(40, offset(panel));
        win.handleInput(ctrl('b')); // up 40  → 80
        assertEquals(80, offset(panel));
        win.handleInput(ctrl('b')); // up 40  → 120
        assertEquals(120, offset(panel));
        win.handleInput(ctrl('b')); // up 40  → 160
        assertEquals(160, offset(panel));
        win.handleInput(ctrl('b')); // clamped at 160
        assertEquals(160, offset(panel));

        win.handleInput(ctrl('f')); // down 40 → 120
        assertEquals(120, offset(panel));
        win.handleInput(ctrl('f')); // down 40 → 80
        assertEquals(80, offset(panel));
        win.handleInput(ctrl('f')); // down 40 → 40
        assertEquals(40, offset(panel));
        win.handleInput(ctrl('f')); // down 40 → 0
        assertEquals(0, offset(panel));
        win.handleInput(ctrl('f')); // clamped at 0
        assertEquals(0, offset(panel));
    }
}

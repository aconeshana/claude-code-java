package com.claudecode.ui.lanterna.dialog;

import com.claudecode.tools.hints.ClaudeCodeHint;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link PluginHintMenu}'s key handling and the 30s auto-dismiss timer (shortened via
 * {@link TimedChoiceOverlay#setTimeoutMs} so the test does not wait 30s).
 */
class PluginHintMenuTest {

    private static final ClaudeCodeHint HINT =
        new ClaudeCodeHint(1, "plugin", "acme-plugin", "acme");

    private record Res(PluginHintMenu.Response response, boolean timedOut) {}

    private PluginHintMenu show(long timeoutMs) {
        PluginHintMenu d = new PluginHintMenu();
        d.setTimeoutMs(timeoutMs);
        return d;
    }

    private Res press(PluginHintMenu d, KeyStroke key) throws Exception {
        ArrayBlockingQueue<Res> q = new ArrayBlockingQueue<>(1);
        d.show(HINT, (r, t) -> q.add(new Res(r, t)), null, null);
        d.handleKey(key, new AtomicBoolean(true));
        return q.poll(2, TimeUnit.SECONDS);
    }

    @Test
    void inactiveUntilShown() {
        PluginHintMenu d = new PluginHintMenu();
        assertFalse(d.isActive());
        d.setTimeoutMs(60_000);
        d.show(HINT, (_, _) -> {}, null, null);
        assertTrue(d.isActive());
    }

    @Test
    void enterOnFirstOptionInstalls() throws Exception {
        Res got = press(show(60_000), new KeyStroke(KeyType.ENTER));
        assertEquals(PluginHintMenu.Response.INSTALL, got.response);
        assertFalse(got.timedOut);
    }

    @Test
    void numberKeysMapToResponses() throws Exception {
        assertEquals(PluginHintMenu.Response.INSTALL,
            press(show(60_000), new KeyStroke('1', false, false)).response);
        assertEquals(PluginHintMenu.Response.NOT_NOW,
            press(show(60_000), new KeyStroke('2', false, false)).response);
        assertEquals(PluginHintMenu.Response.DONT_ASK_AGAIN,
            press(show(60_000), new KeyStroke('3', false, false)).response);
    }

    @Test
    void arrowDownThenEnterSelectsNotNow() throws Exception {
        PluginHintMenu d = show(60_000);
        ArrayBlockingQueue<Res> q = new ArrayBlockingQueue<>(1);
        d.show(HINT, (r, t) -> q.add(new Res(r, t)), null, null);
        d.handleKey(new KeyStroke(KeyType.ARROW_DOWN), new AtomicBoolean(true));
        d.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));
        assertEquals(PluginHintMenu.Response.NOT_NOW, q.poll(2, TimeUnit.SECONDS).response);
    }

    @Test
    void escapeDismissesAsNotNowNotTimedOut() throws Exception {
        Res got = press(show(60_000), new KeyStroke(KeyType.ESCAPE));
        assertEquals(PluginHintMenu.Response.NOT_NOW, got.response);
        assertFalse(got.timedOut);
    }

    @Test
    void timeoutAutoDismissesAsNotNowTimedOut() throws Exception {
        PluginHintMenu d = show(150);
        ArrayBlockingQueue<Res> q = new ArrayBlockingQueue<>(1);
        d.show(HINT, (r, t) -> q.add(new Res(r, t)), null, null);
        // Nothing resolves immediately (only the timer should).
        assertNull(q.poll());
        // Wait for the timer to fire (gui == null path resolves directly).
        Res got = q.poll(2, TimeUnit.SECONDS);
        assertEquals(PluginHintMenu.Response.NOT_NOW, got.response);
        assertTrue(got.timedOut);
        assertFalse(d.isActive());
    }
}

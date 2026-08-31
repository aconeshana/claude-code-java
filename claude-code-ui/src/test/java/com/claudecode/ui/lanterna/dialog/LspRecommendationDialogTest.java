package com.claudecode.ui.lanterna.dialog;

import com.claudecode.core.lsp.LspPluginRecommendation;
import com.claudecode.core.lsp.LspRecommendationResponse;
import com.claudecode.keybindings.UserKeybindingsStore;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link LspRecommendationDialog}'s key handling and the 30s auto-dismiss timer
 * (shortened via {@link #setTimeoutMs} so the test does not wait 30s).
 */
class LspRecommendationDialogTest {

    private static final LspPluginRecommendation REC = new LspPluginRecommendation(
        "typescript@marketplace", "typescript", "TypeScript LSP",
        List.of(".ts", ".tsx"), "typescript-language-server", true, "marketplace");

    private record Res(LspRecommendationResponse response, boolean timedOut) {}

    private LspRecommendationDialog show(long timeoutMs) {
        LspRecommendationDialog d = new LspRecommendationDialog();
        d.setTimeoutMs(timeoutMs);
        return d;
    }

    private Res press(LspRecommendationDialog d, KeyStroke key) throws Exception {
        ArrayBlockingQueue<Res> q = new ArrayBlockingQueue<>(1);
        d.show(REC, (r, t) -> q.add(new Res(r, t)), null, null);
        d.handleKey(key, new AtomicBoolean(true));
        return q.poll(2, TimeUnit.SECONDS);
    }

    @Test
    void inactiveUntilShown() {
        LspRecommendationDialog d = new LspRecommendationDialog();
        assertFalse(d.isActive());
        d.setTimeoutMs(60_000);
        d.show(REC, (_, _) -> {}, null, null);
        assertTrue(d.isActive());
    }

    @Test
    void enterOnFirstOptionInstalls() throws Exception {
        Res got = press(show(60_000), new KeyStroke(KeyType.ENTER));
        assertEquals(LspRecommendationResponse.YES, got.response);
        assertFalse(got.timedOut);
    }

    @Test
    void numberKeysMapToResponses() throws Exception {
        assertEquals(LspRecommendationResponse.NO,
            press(show(60_000), new KeyStroke('2', false, false)).response);
        assertEquals(LspRecommendationResponse.NEVER,
            press(show(60_000), new KeyStroke('3', false, false)).response);
        assertEquals(LspRecommendationResponse.DISABLE,
            press(show(60_000), new KeyStroke('4', false, false)).response);
    }

    @Test
    void arrowDownThenEnterSelectsNotNow() throws Exception {
        LspRecommendationDialog d = show(60_000);
        ArrayBlockingQueue<Res> q = new ArrayBlockingQueue<>(1);
        d.show(REC, (r, t) -> q.add(new Res(r, t)), null, null);
        d.handleKey(new KeyStroke(KeyType.ARROW_DOWN), new AtomicBoolean(true));
        d.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));
        assertEquals(LspRecommendationResponse.NO, q.poll(2, TimeUnit.SECONDS).response);
    }

    @Test
    void escapeDismissesAsNoNotTimedOut() throws Exception {
        Res got = press(show(60_000), new KeyStroke(KeyType.ESCAPE));
        assertEquals(LspRecommendationResponse.NO, got.response);
        assertFalse(got.timedOut);
    }

    @Test
    void timeoutAutoDismissesAsNoTimedOut() throws Exception {
        LspRecommendationDialog d = show(150);
        ArrayBlockingQueue<Res> q = new ArrayBlockingQueue<>(1);
        d.show(REC, (r, t) -> q.add(new Res(r, t)), null, null);
        // Nothing resolves immediately (only the timer should).
        assertNull(q.poll());
        // Wait for the timer to fire (gui == null path resolves directly).
        Res got = q.poll(2, TimeUnit.SECONDS);
        assertEquals(LspRecommendationResponse.NO, got.response);
        assertTrue(got.timedOut);
        assertFalse(d.isActive());
    }

    @Test
    void selectContextSupportsCustomBindingsAndNullUnbinding(
            @TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("keybindings.json");
        Files.writeString(file, """
            [{"context":"Select","bindings":{
              "x":"select:next",
              "z":"select:accept",
              "enter":null
            }}]
            """);
        UserKeybindingsStore store = createStore(file);
        try {
            LspRecommendationDialog d = show(60_000);
            d.setKeybindingsStore(store);
            ArrayBlockingQueue<Res> q = new ArrayBlockingQueue<>(1);
            d.show(REC, (r, t) -> q.add(new Res(r, t)), null, null);
            d.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));
            assertNull(q.poll());
            d.handleKey(new KeyStroke('x', false, false), new AtomicBoolean(true));
            d.handleKey(new KeyStroke('z', false, false), new AtomicBoolean(true));
            assertEquals(LspRecommendationResponse.NO,
                q.poll(2, TimeUnit.SECONDS).response);
        } finally {
            store.dispose();
        }
    }

    private static UserKeybindingsStore createStore(Path file) throws Exception {
        Method create = UserKeybindingsStore.class
            .getDeclaredMethod("create", Path.class, boolean.class);
        create.setAccessible(true);
        return (UserKeybindingsStore) create.invoke(null, file, true);
    }
}

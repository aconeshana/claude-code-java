package com.claudecode.ui.lanterna.dialog;

import com.claudecode.keybindings.UserKeybindingsStore;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

import java.nio.file.Path;
import java.nio.file.Files;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * State-machine tests for {@link TrustFolderDialog}, driven directly (no real GUI
 * thread) — same pattern as {@link AddDirDialogTest}.
 */
class TrustFolderDialogTest {

    private static final Path SOME_DIR = Path.of("/abs/working/dir").toAbsolutePath().normalize();

    @Test
    void idle_hasZeroPreferredSize() {
        TrustFolderDialog d = new TrustFolderDialog();
        assertFalse(d.isActive());
        assertEquals(new TerminalSize(0, 0), d.calculatePreferredSize());
    }

    @Test
    void prompt_activates() {
        TrustFolderDialog d = new TrustFolderDialog();
        d.prompt(SOME_DIR, () -> {}, () -> {});
        assertTrue(d.isActive());
    }

    @Test
    void defaultSelection_isTrust_andEnterTrusts() {
        TrustFolderDialog d = new TrustFolderDialog();
        AtomicBoolean trusted = new AtomicBoolean();
        AtomicBoolean exited = new AtomicBoolean();
        d.prompt(SOME_DIR, () -> trusted.set(true), () -> exited.set(true));

        // Default highlighted option is "Yes, I trust this folder".
        d.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));

        assertTrue(trusted.get(), "Enter on the default option must trust");
        assertFalse(exited.get());
        assertFalse(d.isActive(), "dialog must close after resolving");
    }

    @Test
    void arrowDownThenEnter_exits() {
        TrustFolderDialog d = new TrustFolderDialog();
        AtomicBoolean trusted = new AtomicBoolean();
        AtomicBoolean exited = new AtomicBoolean();
        d.prompt(SOME_DIR, () -> trusted.set(true), () -> exited.set(true));

        d.handleKey(new KeyStroke(KeyType.ARROW_DOWN), new AtomicBoolean(true));
        d.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));

        assertFalse(trusted.get());
        assertTrue(exited.get(), "down + Enter must decline and exit");
        assertFalse(d.isActive());
    }

    @Test
    void arrowUpWrapsToExit_thenEnterExits() {
        TrustFolderDialog d = new TrustFolderDialog();
        AtomicBoolean trusted = new AtomicBoolean();
        AtomicBoolean exited = new AtomicBoolean();
        d.prompt(SOME_DIR, () -> trusted.set(true), () -> exited.set(true));

        // Up from the first option wraps to the last ("No, exit").
        d.handleKey(new KeyStroke(KeyType.ARROW_UP), new AtomicBoolean(true));
        d.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));

        assertFalse(trusted.get());
        assertTrue(exited.get());
    }

    @Test
    void escape_exits() {
        TrustFolderDialog d = new TrustFolderDialog();
        AtomicBoolean trusted = new AtomicBoolean();
        AtomicBoolean exited = new AtomicBoolean();
        d.prompt(SOME_DIR, () -> trusted.set(true), () -> exited.set(true));

        d.handleKey(new KeyStroke(KeyType.ESCAPE), new AtomicBoolean(true));

        assertFalse(trusted.get());
        assertTrue(exited.get(), "Esc must decline and exit (mirrors TS)");
        assertFalse(d.isActive());
    }

    @Test
    void jAndK_navigateLikeArrows() {
        TrustFolderDialog d = new TrustFolderDialog();
        AtomicBoolean trusted = new AtomicBoolean();
        AtomicBoolean exited = new AtomicBoolean();
        d.prompt(SOME_DIR, () -> trusted.set(true), () -> exited.set(true));

        // 'j' moves down to the exit option, Enter declines.
        d.handleKey(new KeyStroke('j', false, false), new AtomicBoolean(true));
        d.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));

        assertFalse(trusted.get());
        assertTrue(exited.get());
    }

    @Test
    void selectAndConfirmationBindingsCanBeRebound(@TempDir Path tmp) throws Exception {
        TrustFolderDialog d = new TrustFolderDialog();
        AtomicBoolean trusted = new AtomicBoolean();
        AtomicBoolean exited = new AtomicBoolean();
        var store = createStore(tmp.resolve("keybindings.json"), """
            [
              {"context":"Select","bindings":{"ctrl+j":"select:next","down":null}},
              {"context":"Confirmation","bindings":{"ctrl+g":"confirm:no","escape":null}}
            ]
            """);
        try {
            d.setKeybindingsStore(store);
            d.prompt(SOME_DIR, () -> trusted.set(true), () -> exited.set(true));
            d.handleKey(new KeyStroke(KeyType.ARROW_DOWN), new AtomicBoolean(true));
            d.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));
            assertTrue(trusted.get(), "null-unbound Down leaves trust selected");

            trusted.set(false);
            d.prompt(SOME_DIR, () -> trusted.set(true), () -> exited.set(true));
            d.handleKey(new KeyStroke('j', true, false), new AtomicBoolean(true));
            d.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));
            assertTrue(exited.get(), "custom Select next reaches No, exit");

            exited.set(false);
            d.prompt(SOME_DIR, () -> trusted.set(true), () -> exited.set(true));
            d.handleKey(new KeyStroke(KeyType.ESCAPE), new AtomicBoolean(true));
            assertTrue(d.isActive(), "null-unbound Escape is consumed");
            d.handleKey(new KeyStroke('g', true, false), new AtomicBoolean(true));
            assertTrue(exited.get(), "custom Confirmation cancel exits");
        } finally {
            store.dispose();
        }
    }

    private static UserKeybindingsStore createStore(
            Path file, String json) throws Exception {
        Files.writeString(file, json);
        Method create = UserKeybindingsStore.class
            .getDeclaredMethod("create", Path.class, boolean.class);
        create.setAccessible(true);
        return (UserKeybindingsStore) create.invoke(null, file, true);
    }

    @Test
    void handleKey_consumesKeysWhileActive() {
        TrustFolderDialog d = new TrustFolderDialog();
        d.prompt(SOME_DIR, () -> {}, () -> {});
        AtomicBoolean deliver = new AtomicBoolean(true);

        d.handleKey(new KeyStroke(KeyType.ARROW_DOWN), deliver);

        assertFalse(deliver.get(), "key must be consumed while the dialog is active");
    }

    @Test
    void handleKey_noOpWhileIdle() {
        TrustFolderDialog d = new TrustFolderDialog();
        AtomicBoolean deliver = new AtomicBoolean(true);

        d.handleKey(new KeyStroke(KeyType.ENTER), deliver);

        assertTrue(deliver.get(), "idle dialog must not consume keys");
        assertFalse(d.isActive());
    }

    @Test
    void resolvingTwice_isNoOp() {
        TrustFolderDialog d = new TrustFolderDialog();
        AtomicReference<Integer> trustCount = new AtomicReference<>(0);
        AtomicReference<Integer> exitCount = new AtomicReference<>(0);
        d.prompt(SOME_DIR, () -> trustCount.set(trustCount.get() + 1),
            () -> exitCount.set(exitCount.get() + 1));

        d.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));
        d.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true)); // already hidden

        assertEquals(1, trustCount.get());
        assertEquals(0, exitCount.get());
    }

// ── P5: Ctrl+C / Ctrl+D double-press-to-exit.

    private static KeyStroke ctrl(char c) {
        return new KeyStroke(c, true, false); // character + ctrl, no alt
    }

    @Test
    void singleCtrlC_doesNotExit_firstPressOnlyPending() {
        TrustFolderDialog d = new TrustFolderDialog();
        AtomicBoolean trusted = new AtomicBoolean();
        AtomicBoolean exited = new AtomicBoolean();
        d.prompt(SOME_DIR, () -> trusted.set(true), () -> exited.set(true));

        d.handleKey(ctrl('c'), new AtomicBoolean(true));

        assertFalse(exited.get(), "first Ctrl+C must only set pending, not exit");
        assertTrue(d.isActive(), "dialog stays open after first Ctrl+C");
        assertFalse(trusted.get());
    }

    @Test
    void doubleCtrlC_exits_onSecondPress() {
        TrustFolderDialog d = new TrustFolderDialog();
        AtomicBoolean trusted = new AtomicBoolean();
        AtomicBoolean exited = new AtomicBoolean();
        d.prompt(SOME_DIR, () -> trusted.set(true), () -> exited.set(true));

        d.handleKey(ctrl('c'), new AtomicBoolean(true));
        d.handleKey(ctrl('c'), new AtomicBoolean(true));

        assertTrue(exited.get(), "second Ctrl+C must exit");
        assertFalse(d.isActive());
        assertFalse(trusted.get());
    }

    @Test
    void arrowClearsPendingCtrlCState() {
        TrustFolderDialog d = new TrustFolderDialog();
        AtomicBoolean exited = new AtomicBoolean();
        d.prompt(SOME_DIR, () -> {}, () -> exited.set(true));

        d.handleKey(ctrl('c'), new AtomicBoolean(true));     // pending
        d.handleKey(new KeyStroke(KeyType.ARROW_DOWN), new AtomicBoolean(true)); // clears pending
        assertFalse(exited.get());
        assertTrue(d.isActive());
        // Re-arm: one pending, then a matching second press exits.
        d.handleKey(ctrl('c'), new AtomicBoolean(true));
        d.handleKey(ctrl('c'), new AtomicBoolean(true));
        assertTrue(exited.get());
    }

    @Test
    void ctrlCPendingIsKeySpecific_ctrlDThenCtrlCDoesNotExit() {
        TrustFolderDialog d = new TrustFolderDialog();
        AtomicBoolean exited = new AtomicBoolean();
        d.prompt(SOME_DIR, () -> {}, () -> exited.set(true));

        d.handleKey(ctrl('c'), new AtomicBoolean(true)); // pending 'c'
        d.handleKey(ctrl('d'), new AtomicBoolean(true)); // switches pending to 'd', no exit
        assertFalse(exited.get());
        assertTrue(d.isActive());
        d.handleKey(ctrl('d'), new AtomicBoolean(true)); // matching second press exits
        assertTrue(exited.get());
    }

    @Test
    void ctrlCGapBeyondTimeout_requiresThirdPress() throws Exception {
        TrustFolderDialog d = new TrustFolderDialog();
        AtomicBoolean exited = new AtomicBoolean();
        d.prompt(SOME_DIR, () -> {}, () -> exited.set(true));

        d.handleKey(ctrl('c'), new AtomicBoolean(true));
        Thread.sleep(900); // exceeds the 800ms double-press window
        d.handleKey(ctrl('c'), new AtomicBoolean(true)); // re-arms, must NOT exit
        assertFalse(exited.get(), "second Ctrl+C after timeout must re-arm, not exit");
        assertTrue(d.isActive());
        d.handleKey(ctrl('c'), new AtomicBoolean(true)); // third, within window → exits
        assertTrue(exited.get());
    }
}

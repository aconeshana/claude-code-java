package com.claudecode.ui.lanterna.dialog;

import com.claudecode.keybindings.UserKeybindingsStore;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.reflect.Method;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * State-machine tests for {@link ClaudeMdExternalIncludesDialog}, driven directly
 * (no real GUI thread) — same pattern as {@link TrustFolderDialogTest}.
 */
class ClaudeMdExternalIncludesDialogTest {

    private static final Path SOME_DIR = Path.of("/abs/working/dir").toAbsolutePath().normalize();
    private static final List<String> EXTERNALS = List.of("/outside/other/CLAUDE.md");

    @Test
    void idle_hasZeroPreferredSize() {
        ClaudeMdExternalIncludesDialog d = new ClaudeMdExternalIncludesDialog();
        assertFalse(d.isActive());
        assertEquals(new TerminalSize(0, 0), d.calculatePreferredSize());
    }

    @Test
    void prompt_activates() {
        ClaudeMdExternalIncludesDialog d = new ClaudeMdExternalIncludesDialog();
        d.prompt(SOME_DIR, EXTERNALS, () -> {}, () -> {}, () -> {});
        assertTrue(d.isActive());
    }

    @Test
    void defaultSelection_isAllow_andEnterAllows() {
        ClaudeMdExternalIncludesDialog d = new ClaudeMdExternalIncludesDialog();
        AtomicBoolean allowed = new AtomicBoolean();
        AtomicBoolean disabled = new AtomicBoolean();
        AtomicBoolean exited = new AtomicBoolean();
        d.prompt(SOME_DIR, EXTERNALS, () -> allowed.set(true), () -> disabled.set(true), () -> exited.set(true));

        d.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));

        assertTrue(allowed.get(), "Enter on default option must allow");
        assertFalse(disabled.get());
        assertFalse(exited.get());
        assertFalse(d.isActive(), "dialog must close after resolving");
    }

    @Test
    void arrowDownThenEnter_disablesAndContinues() {
        ClaudeMdExternalIncludesDialog d = new ClaudeMdExternalIncludesDialog();
        AtomicBoolean allowed = new AtomicBoolean();
        AtomicBoolean disabled = new AtomicBoolean();
        d.prompt(SOME_DIR, EXTERNALS, () -> allowed.set(true), () -> disabled.set(true), () -> {});

        d.handleKey(new KeyStroke(KeyType.ARROW_DOWN), new AtomicBoolean(true));
        d.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));

        assertFalse(allowed.get());
        assertTrue(disabled.get(), "down + Enter must disable and continue (does NOT exit)");
        assertFalse(d.isActive());
    }

    @Test
    void escape_disablesAndContinues() {
        ClaudeMdExternalIncludesDialog d = new ClaudeMdExternalIncludesDialog();
        AtomicBoolean allowed = new AtomicBoolean();
        AtomicBoolean disabled = new AtomicBoolean();
        AtomicBoolean exited = new AtomicBoolean();
        d.prompt(SOME_DIR, EXTERNALS, () -> allowed.set(true), () -> disabled.set(true), () -> exited.set(true));

        d.handleKey(new KeyStroke(KeyType.ESCAPE), new AtomicBoolean(true));

        assertFalse(allowed.get());
        assertTrue(disabled.get(), "Esc must decline (mirrors TS onCancel)");
        assertFalse(exited.get(), "Esc must NOT exit — only double Ctrl+C does");
        assertFalse(d.isActive());
    }

    @Test
    void handleKey_consumesKeysWhileActive() {
        ClaudeMdExternalIncludesDialog d = new ClaudeMdExternalIncludesDialog();
        d.prompt(SOME_DIR, EXTERNALS, () -> {}, () -> {}, () -> {});
        AtomicBoolean deliver = new AtomicBoolean(true);

        d.handleKey(new KeyStroke(KeyType.ARROW_DOWN), deliver);

        assertFalse(deliver.get(), "key must be consumed while the dialog is active");
    }

    @Test
    void handleKey_noOpWhileIdle() {
        ClaudeMdExternalIncludesDialog d = new ClaudeMdExternalIncludesDialog();
        AtomicBoolean deliver = new AtomicBoolean(true);

        d.handleKey(new KeyStroke(KeyType.ENTER), deliver);

        assertTrue(deliver.get(), "idle dialog must not consume keys");
        assertFalse(d.isActive());
    }

    @Test
    void doubleCtrlC_exits() {
        ClaudeMdExternalIncludesDialog d = new ClaudeMdExternalIncludesDialog();
        AtomicBoolean exited = new AtomicBoolean();
        d.prompt(SOME_DIR, EXTERNALS, () -> {}, () -> {}, () -> exited.set(true));

        d.handleKey(new KeyStroke('c', true, false), new AtomicBoolean(true)); // pending
        assertFalse(exited.get());
        assertTrue(d.isActive());
        d.handleKey(new KeyStroke('c', true, false), new AtomicBoolean(true)); // exit
        assertTrue(exited.get());
        assertFalse(d.isActive());
    }

    @Test
    void ctrlCGapBeyondTimeout_requiresThirdPress() throws Exception {
        ClaudeMdExternalIncludesDialog d = new ClaudeMdExternalIncludesDialog();
        AtomicBoolean exited = new AtomicBoolean();
        d.prompt(SOME_DIR, EXTERNALS, () -> {}, () -> {}, () -> exited.set(true));

        d.handleKey(new KeyStroke('c', true, false), new AtomicBoolean(true));
        Thread.sleep(900); // exceeds the 800ms double-press window
        d.handleKey(new KeyStroke('c', true, false), new AtomicBoolean(true)); // re-arm, no exit
        assertFalse(exited.get(), "second Ctrl+C after timeout must re-arm, not exit");
        assertTrue(d.isActive());
        d.handleKey(new KeyStroke('c', true, false), new AtomicBoolean(true)); // third → exit
        assertTrue(exited.get());
    }

    @Test
    void selectContextSupportsRebindingAndNullUnbinding(
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
            AtomicBoolean disabled = new AtomicBoolean();
            ClaudeMdExternalIncludesDialog d = new ClaudeMdExternalIncludesDialog();
            d.setKeybindingsStore(store);
            d.prompt(SOME_DIR, EXTERNALS, () -> {}, () -> disabled.set(true), () -> {});
            d.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));
            assertTrue(d.isActive());
            d.handleKey(new KeyStroke('x', false, false), new AtomicBoolean(true));
            d.handleKey(new KeyStroke('z', false, false), new AtomicBoolean(true));
            assertTrue(disabled.get());
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

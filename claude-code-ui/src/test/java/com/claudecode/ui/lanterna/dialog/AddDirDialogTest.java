package com.claudecode.ui.lanterna.dialog;

import com.claudecode.keybindings.UserKeybindingsStore;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.PasteKeyStroke;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * State-machine tests for {@link AddDirDialog}, driven directly (no real GUI
 * thread) — same pattern as {@code ThemePickerDialogTest}.
 */
class AddDirDialogTest {

    @TempDir Path tempDir;

    private AddDirDialog.ValidationOutcome alwaysValid(String path) {
        return new AddDirDialog.ValidationOutcome(path, null);
    }

    @Test
    void idle_hasZeroPreferredSize() {
        AddDirDialog d = new AddDirDialog();
        assertFalse(d.isActive());
        assertEquals(new TerminalSize(0, 0), d.calculatePreferredSize());
    }

    @Test
    void showWithNullPath_opensInputPhase() {
        AddDirDialog d = new AddDirDialog();
        d.show(null, this::alwaysValid, (_, _) -> {});
        assertTrue(d.isActive());
    }

    @Test
    void showWithPath_opensConfirmPhaseDirectly() {
        AddDirDialog d = new AddDirDialog();
        AtomicReference<Object[]> result = new AtomicReference<>();
        d.show("/some/resolved/path", this::alwaysValid, (p, r) -> result.set(new Object[]{p, r}));

        // Confirm phase: Enter on the first option ("Yes, for this session") resolves immediately.
        d.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));

        assertArrayEquals(new Object[]{"/some/resolved/path", Boolean.FALSE}, result.get());
    }

    // ── INPUT phase: typing + validation ────────────────────────────────────

    @Test
    void inputPhase_typingAppendsCharacters() {
        AddDirDialog d = new AddDirDialog();
        AtomicReference<String> submittedPath = new AtomicReference<>();
        d.show(null, path -> {
            submittedPath.set(path);
            return new AddDirDialog.ValidationOutcome(path, null);
        }, (_, _) -> {});

        // A path with no matching directory-completion candidates, so Enter
        // submits the raw typed text rather than a suggestion (see the
        // dedicated suggestion-interaction tests below for that branch).
        String noSuchPath = tempDir + "/does-not-exist-xyz";
        for (char c : noSuchPath.toCharArray()) {
            d.handleKey(new KeyStroke(c, false, false), new AtomicBoolean(true));
        }
        d.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));

        assertEquals(noSuchPath, submittedPath.get());
    }

    @Test
    void inputPhase_backspaceRemovesLastCharacter() {
        AddDirDialog d = new AddDirDialog();
        AtomicReference<String> submittedPath = new AtomicReference<>();
        d.show(null, path -> {
            submittedPath.set(path);
            return new AddDirDialog.ValidationOutcome(path, null);
        }, (_, _) -> {});

        d.handleKey(new KeyStroke('a', false, false), new AtomicBoolean(true));
        d.handleKey(new KeyStroke('b', false, false), new AtomicBoolean(true));
        d.handleKey(new KeyStroke(KeyType.BACKSPACE), new AtomicBoolean(true));
        d.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));

        assertEquals("a", submittedPath.get());
    }

    @Test
    void inputPhase_pasteInsertsTextAndConsumesTheKey() {
        // Regression test: dragging file(s) onto the terminal (or a plain
        // clipboard paste) arrives as a single PasteKeyStroke, not a run of
        // CHARACTER events. Before this fix the dialog had no branch for
        // KeyType.PASTE, so the event fell through undelivered=false-unset and
// compatibility baselineto whatever held real Lanterna focus underneath (the main
        // chat input), instead of landing in this dialog's own field.
        AddDirDialog d = new AddDirDialog();
        AtomicReference<String> submittedPath = new AtomicReference<>();
        d.show(null, path -> {
            submittedPath.set(path);
            return new AddDirDialog.ValidationOutcome(path, null);
        }, (_, _) -> {});

        AtomicBoolean deliver = new AtomicBoolean(true);
        d.handleKey(new PasteKeyStroke("/Users/x/one /Users/x/two"), deliver);
        d.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));

        assertFalse(deliver.get(), "a paste inside the dialog must be consumed, not leaked to the main input");
        assertEquals("/Users/x/one /Users/x/two", submittedPath.get());
    }

    @Test
    void inputPhase_pasteStripsNewlines() {
        AddDirDialog d = new AddDirDialog();
        AtomicReference<String> submittedPath = new AtomicReference<>();
        d.show(null, path -> {
            submittedPath.set(path);
            return new AddDirDialog.ValidationOutcome(path, null);
        }, (_, _) -> {});

        d.handleKey(new PasteKeyStroke("/Users/x/one\n/Users/x/two"), new AtomicBoolean(true));
        d.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));

        assertEquals("/Users/x/one /Users/x/two", submittedPath.get());
    }

    @Test
    void inputPhase_validSubmit_resolvesSessionOnlyWithoutRememberChoice() {

        // the input form is added directly (remember=false) — the remember
        // choice list is never shown in this mode.
        AddDirDialog d = new AddDirDialog();
        AtomicReference<Object[]> result = new AtomicReference<>();
        d.show(null, this::alwaysValid, (p, r) -> result.set(new Object[]{p, r}));

        d.handleKey(new KeyStroke('x', false, false), new AtomicBoolean(true));
        d.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));

        assertArrayEquals(new Object[]{"x", Boolean.FALSE}, result.get());
        assertFalse(d.isActive());
    }

    @Test
    void inputPhase_invalidSubmit_showsErrorAndStaysOpen() {
        AddDirDialog d = new AddDirDialog();
        AtomicReference<Object[]> result = new AtomicReference<>();
        d.show(null, path -> new AddDirDialog.ValidationOutcome(null, "boom: " + path),
            (p, r) -> result.set(new Object[]{p, r}));

        d.handleKey(new KeyStroke('x', false, false), new AtomicBoolean(true));
        d.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));

        assertNull(result.get(), "an invalid path must not resolve");
        assertTrue(d.isActive(), "the form must stay open for another attempt");
    }

    @Test
    void inputPhase_escape_cancelsWithNullPath() {
        AddDirDialog d = new AddDirDialog();
        AtomicReference<Object[]> result = new AtomicReference<>();
        d.show(null, this::alwaysValid, (p, r) -> result.set(new Object[]{p, r}));

        d.handleKey(new KeyStroke('x', false, false), new AtomicBoolean(true));
        d.handleKey(new KeyStroke(KeyType.ESCAPE), new AtomicBoolean(true));

        assertArrayEquals(new Object[]{null, null}, result.get(),
            "cancelling from the input phase must report no path at all");
    }

    @Test
    void inputPhase_cancelUsesSettingsContextAndSupportsNullUnbind(@TempDir Path bindingsDir)
            throws Exception {
        Path file = bindingsDir.resolve("keybindings.json");
        Files.writeString(file, """
            [{"context":"Settings","bindings":{
              "x":"confirm:no",
              "escape":null
            }}]
            """);
        UserKeybindingsStore store = createStore(file);
        try {
            AtomicReference<Object[]> result = new AtomicReference<>();
            AddDirDialog d = new AddDirDialog();
            d.setKeybindingsStore(store);
            d.show(null, this::alwaysValid, (p, r) -> result.set(new Object[]{p, r}));

            d.handleKey(new KeyStroke(KeyType.ESCAPE), new AtomicBoolean(true));
            assertTrue(d.isActive());
            assertNull(result.get());

            d.handleKey(new KeyStroke('x', false, false), new AtomicBoolean(true));
            assertFalse(d.isActive());
            assertArrayEquals(new Object[]{null, null}, result.get());
        } finally {
            store.dispose();
        }
    }

    // ── INPUT phase: directory completion ───────────────────────────────────

    @Test
    void inputPhase_tab_acceptsSuggestionIntoField(@TempDir Path unused) throws IOException {
        Path sub = Files.createDirectory(tempDir.resolve("sub-project"));
        AddDirDialog d = new AddDirDialog();
        AtomicReference<String> submittedPath = new AtomicReference<>();
        d.show(null, path -> {
            submittedPath.set(path);
            return new AddDirDialog.ValidationOutcome(path, null);
        }, (_, _) -> {});

        for (char c : (tempDir + "/sub").toCharArray()) {
            d.handleKey(new KeyStroke(c, false, false), new AtomicBoolean(true));
        }
        d.handleKey(new KeyStroke(KeyType.TAB), new AtomicBoolean(true));
        d.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));

        assertEquals(sub + "/", submittedPath.get());
    }

    @Test
    void inputPhase_enterWithSuggestionsOpen_submitsHighlightedSuggestionDirectly() throws IOException {
        Path sub = Files.createDirectory(tempDir.resolve("another-project"));
        AddDirDialog d = new AddDirDialog();
        AtomicReference<String> submittedPath = new AtomicReference<>();
        d.show(null, path -> {
            submittedPath.set(path);
            return new AddDirDialog.ValidationOutcome(path, null);
        }, (_, _) -> {});

        for (char c : (tempDir + "/anoth").toCharArray()) {
            d.handleKey(new KeyStroke(c, false, false), new AtomicBoolean(true));
        }
// Enter with a suggestion list open submits the suggestion directly.
        d.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));

        assertEquals(sub + "/", submittedPath.get());
    }

    @Test
    void inputPhase_ctrlNAndCtrlP_navigateSuggestionsLikeArrowKeys() throws IOException {

        // aliases for Down/Up when the suggestion list is open.
        Files.createDirectory(tempDir.resolve("alpha-project"));
        Files.createDirectory(tempDir.resolve("beta-project"));
        AddDirDialog d = new AddDirDialog();
        AtomicReference<String> submittedPath = new AtomicReference<>();
        d.show(null, path -> {
            submittedPath.set(path);
            return new AddDirDialog.ValidationOutcome(path, null);
        }, (_, _) -> {});

        for (char c : (tempDir + "/").toCharArray()) {
            d.handleKey(new KeyStroke(c, false, false), new AtomicBoolean(true));
        }
        d.handleKey(new KeyStroke('n', true, false), new AtomicBoolean(true)); // Ctrl+N
        d.handleKey(new KeyStroke('p', true, false), new AtomicBoolean(true)); // Ctrl+P (back to index 0)
        d.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));

        assertEquals(tempDir.resolve("alpha-project") + "/", submittedPath.get());
    }

    // ── CONFIRM phase: remember choice ──────────────────────────────────────

    @Test
    void confirmPhase_arrowDown_thenEnter_choosesRemember() {
        AddDirDialog d = new AddDirDialog();
        AtomicReference<Object[]> result = new AtomicReference<>();
        d.show("/abs/path", this::alwaysValid, (p, r) -> result.set(new Object[]{p, r}));

        d.handleKey(new KeyStroke(KeyType.ARROW_DOWN), new AtomicBoolean(true));
        d.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));

        assertArrayEquals(new Object[]{"/abs/path", Boolean.TRUE}, result.get());
    }

    @Test
    void confirmPhase_noOption_cancelsWithPathKnown() {
        AddDirDialog d = new AddDirDialog();
        AtomicReference<Object[]> result = new AtomicReference<>();
        d.show("/abs/path", this::alwaysValid, (p, r) -> result.set(new Object[]{p, r}));

        d.handleKey(new KeyStroke(KeyType.ARROW_DOWN), new AtomicBoolean(true));
        d.handleKey(new KeyStroke(KeyType.ARROW_DOWN), new AtomicBoolean(true));
        d.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));

        assertArrayEquals(new Object[]{"/abs/path", null}, result.get());
    }

    @Test
    void confirmPhase_escape_cancelsWithPathKnown() {
        AddDirDialog d = new AddDirDialog();
        AtomicReference<Object[]> result = new AtomicReference<>();
        d.show("/abs/path", this::alwaysValid, (p, r) -> result.set(new Object[]{p, r}));

        d.handleKey(new KeyStroke(KeyType.ESCAPE), new AtomicBoolean(true));

        assertArrayEquals(new Object[]{"/abs/path", null}, result.get(),
            "cancelling from the confirm phase must carry the already-known path");
    }

    @Test
    void confirmPhase_arrowWrapsAround() {
        AddDirDialog d = new AddDirDialog();
        AtomicReference<Object[]> result = new AtomicReference<>();
        d.show("/abs/path", this::alwaysValid, (p, r) -> result.set(new Object[]{p, r}));

        d.handleKey(new KeyStroke(KeyType.ARROW_UP), new AtomicBoolean(true)); // wraps to last ("No")
        d.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));

        assertArrayEquals(new Object[]{"/abs/path", null}, result.get());
    }

    @Test
    void confirmPhase_usesSelectContextAndSupportsNullUnbind(@TempDir Path bindingsDir)
            throws Exception {
        Path file = bindingsDir.resolve("keybindings.json");
        Files.writeString(file, """
            [{"context":"Select","bindings":{
              "x":"select:next",
              "z":"select:accept",
              "escape":null
            }}]
            """);
        UserKeybindingsStore store = createStore(file);
        try {
            AtomicReference<Object[]> result = new AtomicReference<>();
            AddDirDialog d = new AddDirDialog();
            d.setKeybindingsStore(store);
            d.show("/abs/path", this::alwaysValid, (p, r) -> result.set(new Object[]{p, r}));

            d.handleKey(new KeyStroke(KeyType.ESCAPE), new AtomicBoolean(true));
            assertTrue(d.isActive());
            d.handleKey(new KeyStroke('x', false, false), new AtomicBoolean(true));
            d.handleKey(new KeyStroke('z', false, false), new AtomicBoolean(true));

            assertArrayEquals(new Object[]{"/abs/path", Boolean.TRUE}, result.get());
        } finally {
            store.dispose();
        }
    }

    // ── consumption ──────────────────────────────────────────────────────────

    @Test
    void handleKey_consumesKeysWhileActive() {
        AddDirDialog d = new AddDirDialog();
        d.show(null, this::alwaysValid, (_, _) -> {});
        AtomicBoolean deliver = new AtomicBoolean(true);

        d.handleKey(new KeyStroke('x', false, false), deliver);

        assertFalse(deliver.get());
    }

    @Test
    void handleKey_noOpWhileIdle() {
        AddDirDialog d = new AddDirDialog();
        AtomicBoolean deliver = new AtomicBoolean(true);

        d.handleKey(new KeyStroke(KeyType.ENTER), deliver);

        assertTrue(deliver.get());
        assertFalse(d.isActive());
    }

    private static UserKeybindingsStore createStore(Path file) throws Exception {
        Method create = UserKeybindingsStore.class
            .getDeclaredMethod("create", Path.class, boolean.class);
        create.setAccessible(true);
        return (UserKeybindingsStore) create.invoke(null, file, true);
    }
}

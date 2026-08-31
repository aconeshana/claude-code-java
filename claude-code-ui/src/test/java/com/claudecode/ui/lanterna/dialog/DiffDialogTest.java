package com.claudecode.ui.lanterna.dialog;

import com.claudecode.commands.diff.DiffData;
import com.claudecode.commands.diff.TurnDiffExtractor.TurnDiff;
import com.claudecode.commands.diff.TurnDiffExtractor.TurnFileDiff;
import com.claudecode.core.diff.StructuredPatchHunk;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DiffDialogTest {

    private static DiffData gitDiff() {
        return new DiffData(
            new DiffData.Stats(2, 5, 1),
            List.of(
                new DiffData.DiffFile("a.txt", 3, 1, false, false, false, false, false),
                new DiffData.DiffFile("b.txt", 2, 0, false, false, false, false, false)),
            Map.of(
                "a.txt", List.of(new StructuredPatchHunk(1, 2, 1, 4,
                    List.of(" ctx", "+one", "+two", "-gone", " ctx2"))),
                "b.txt", List.of(new StructuredPatchHunk(1, 0, 1, 2,
                    List.of("+x", "+y")))));
    }

    private static TurnDiff turn(int index) {
        return new TurnDiff(index, "fix the bug",
            List.of(new TurnFileDiff("c.txt",
                List.of(new StructuredPatchHunk(1, 1, 1, 1, List.of("-old", "+new"))),
                false, 1, 1)),
            new DiffData.Stats(1, 1, 1));
    }

    private static void press(DiffDialog dialog, KeyType type) {
        dialog.handleKey(new KeyStroke(type), new AtomicBoolean(true));
    }

    @Test
    void idle_zeroSizeAndKeysFallThrough() {
        DiffDialog dialog = new DiffDialog(40);
        assertEquals(0, dialog.calculatePreferredSize().getRows());
        AtomicBoolean deliver = new AtomicBoolean(true);
        dialog.handleKey(new KeyStroke(KeyType.ENTER), deliver);
        assertTrue(deliver.get());
    }

    @Test
    void show_activatesWithNonZeroHeight() {
        DiffDialog dialog = new DiffDialog(40);
        dialog.show(gitDiff(), List.of(), () -> {});
        assertTrue(dialog.isActive());
        assertTrue(dialog.calculatePreferredSize().getRows() > 5);
    }

    @Test
    void escInList_closesAndFiresCallback() {
        AtomicBoolean closed = new AtomicBoolean();
        DiffDialog dialog = new DiffDialog(40);
        dialog.show(gitDiff(), List.of(), () -> closed.set(true));
        press(dialog, KeyType.ESCAPE);
        assertTrue(closed.get());
        assertFalse(dialog.isActive());
    }

    @Test
    void escInDetail_collapsesToListNotClose() {
        AtomicBoolean closed = new AtomicBoolean();
        DiffDialog dialog = new DiffDialog(40);
        dialog.show(gitDiff(), List.of(), () -> closed.set(true));
        press(dialog, KeyType.ENTER);   // list → detail
        press(dialog, KeyType.ESCAPE);  // detail → list
        assertFalse(closed.get());
        assertTrue(dialog.isActive());
        press(dialog, KeyType.ESCAPE);  // list → close
        assertTrue(closed.get());
    }

    @Test
    void leftArrowInDetail_returnsToList() {
        AtomicBoolean closed = new AtomicBoolean();
        DiffDialog dialog = new DiffDialog(40);
        dialog.show(gitDiff(), List.of(), () -> closed.set(true));
        press(dialog, KeyType.ENTER);
        press(dialog, KeyType.ARROW_LEFT);
        assertTrue(dialog.isActive(), "← in detail goes back to list, not close");
        assertFalse(closed.get());
    }

    @Test
    void sourceSwitching_boundedNoWraparound() {
        DiffDialog dialog = new DiffDialog(40);
        dialog.show(gitDiff(), List.of(turn(2), turn(1)), () -> {});
        // At Current; ← must stay (no wraparound).
        press(dialog, KeyType.ARROW_LEFT);
        assertTrue(dialog.isActive());
        // → twice to T1(source idx 1), then attempts past the end stay put.
        press(dialog, KeyType.ARROW_RIGHT);
        press(dialog, KeyType.ARROW_RIGHT);
        press(dialog, KeyType.ARROW_RIGHT);
        assertTrue(dialog.isActive());
        // Esc still closes from any source.
        press(dialog, KeyType.ESCAPE);
        assertFalse(dialog.isActive());
    }

    @Test
    void enterWithNoFiles_staysInList() {
        DiffDialog dialog = new DiffDialog(40);
        dialog.show(new DiffData(new DiffData.Stats(0, 0, 0), List.of(), Map.of()),
            List.of(), () -> {});
        press(dialog, KeyType.ENTER);
        // No files → no detail view; Esc closes directly (still list mode).
        AtomicBoolean closed = new AtomicBoolean();
        assertTrue(dialog.isActive());
    }

    @Test
    void nullGitDiff_showsEmptyStateWithoutCrash() {
        DiffDialog dialog = new DiffDialog(40);
        assertDoesNotThrow(() -> dialog.show(null, List.of(), () -> {}));
        assertTrue(dialog.isActive());
        assertTrue(dialog.calculatePreferredSize().getRows() > 0);
    }

    @Test
    void pasteKey_isConsumed() {
        DiffDialog dialog = new DiffDialog(40);
        dialog.show(gitDiff(), List.of(), () -> {});
        AtomicBoolean deliver = new AtomicBoolean(true);
        dialog.handleKey(new KeyStroke(KeyType.PASTE), deliver);
        assertFalse(deliver.get());
        assertTrue(dialog.isActive());
    }

    @Test
    void ctrlC_closes() {
        AtomicBoolean closed = new AtomicBoolean();
        DiffDialog dialog = new DiffDialog(40);
        dialog.show(gitDiff(), List.of(), () -> closed.set(true));
        dialog.handleKey(new KeyStroke('c', true, false), new AtomicBoolean(true));
        assertTrue(closed.get());
    }

    @Test
    void customDismissBindingWorksAndEscapeCanBeUnbound(
            @TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("keybindings.json");
        Files.writeString(file, """
            [
              {"context":"DiffDialog","bindings":{
                "x":"diff:dismiss",
                "escape":null
              }}
            ]
            """);
        UserKeybindingsStore store = createStore(file);
        try {
            AtomicBoolean closed = new AtomicBoolean();
            DiffDialog dialog = new DiffDialog(40);
            dialog.setKeybindingsStore(store);
            dialog.show(gitDiff(), List.of(), () -> closed.set(true));

            press(dialog, KeyType.ESCAPE);
            assertTrue(dialog.isActive());
            assertFalse(closed.get());

            dialog.handleKey(new KeyStroke('x', false, false), new AtomicBoolean(true));
            assertTrue(closed.get());
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

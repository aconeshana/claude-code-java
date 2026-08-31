package com.claudecode.ui.lanterna.dialog;

import com.claudecode.runtime.memory.MemoryCatalog;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemorySelectorDialogTest {

    @TempDir Path tempDir;

    @Test
    void autoMemoryOffStillShowsToggleAndHidesAllMemoryFolders() throws Exception {
        FakeCatalog catalog = new FakeCatalog(false, true, true);
        MemorySelectorDialog dialog = dialog(catalog);

        dialog.show(_ -> {});
        awaitPicking(dialog);

        assertFalse(dialog.autoMemoryOn());
        assertEquals(1, dialog.toggleCount());
        assertEquals(2, dialog.rowCount(), "only User/Project (new) rows remain");
    }

    @Test
    void togglingAutoMemoryPersistsAndEnablesAutoTeamAndAgentFolders() throws Exception {
        FakeCatalog catalog = new FakeCatalog(false, true, true);
        MemorySelectorDialog dialog = dialog(catalog);
        dialog.show(_ -> {});
        awaitPicking(dialog);

        send(dialog, KeyType.ARROW_UP); // list first -> Auto-memory toggle
        assertEquals(0, dialog.focusedToggle());
        send(dialog, KeyType.ENTER);

        assertTrue(catalog.autoMemory);
        assertTrue(dialog.autoMemoryOn());
        assertEquals(5, dialog.rowCount(), "2 files + auto + team + agent folders");
        assertEquals(1, dialog.toggleCount(),
            "TS showDreamRow is captured when the dialog first mounts");
    }

    @Test
    void autoDreamRowShowsStatusAndCanBeToggled() throws Exception {
        FakeCatalog catalog = new FakeCatalog(true, false, false);
        MemorySelectorDialog dialog = dialog(catalog);
        dialog.show(_ -> {});
        awaitPicking(dialog);

        assertEquals(2, dialog.toggleCount());
        assertEquals("never", dialog.dreamStatus());
        send(dialog, KeyType.ARROW_UP); // first list row -> last toggle (auto-dream)
        assertEquals(1, dialog.focusedToggle());
        send(dialog, KeyType.ENTER);

        assertTrue(catalog.autoDream);
        assertTrue(dialog.autoDreamOn());
    }

    @Test
    void selectAndConfirmationBindingsCanBeReboundIndependently() throws Exception {
        Path file = tempDir.resolve("keybindings.json");
        Files.writeString(file, """
            [
              {"context":"Select","bindings":{
                "x":"select:next","p":"select:previous",
                "down":null,"up":null,"enter":null,"escape":null
              }},
              {"context":"Confirmation","bindings":{
                "y":"confirm:yes","q":"confirm:no",
                "enter":null,"escape":null
              }}
            ]
            """);
        UserKeybindingsStore store = createStore(file);
        try {
            FakeCatalog catalog = new FakeCatalog(false, false, false);
            MemorySelectorDialog dialog = dialog(catalog);
            dialog.setKeybindingsStore(store);
            dialog.show(_ -> {});
            awaitPicking(dialog);

            send(dialog, KeyType.ARROW_DOWN);
            assertEquals(0, dialog.focusedIndex(), "unbound Down must not move the Select");
            dialog.handleKey(new KeyStroke('x', false, false), new AtomicBoolean(true));
            assertEquals(1, dialog.focusedIndex());

            dialog.handleKey(new KeyStroke('p', false, false), new AtomicBoolean(true));
            dialog.handleKey(new KeyStroke('p', false, false), new AtomicBoolean(true));
            assertEquals(0, dialog.focusedToggle(), "Select previous enters the toggle block");

            send(dialog, KeyType.ENTER);
            assertFalse(catalog.autoMemory, "unbound Enter must not confirm the toggle");
            dialog.handleKey(new KeyStroke('y', false, false), new AtomicBoolean(true));
            assertTrue(catalog.autoMemory);

            send(dialog, KeyType.ESCAPE);
            assertTrue(dialog.isActive(), "unbound Escape must not cancel");
            dialog.handleKey(new KeyStroke('q', false, false), new AtomicBoolean(true));
            assertFalse(dialog.isActive());
        } finally {
            store.dispose();
        }
    }

    private MemorySelectorDialog dialog(FakeCatalog catalog) {
        return new MemorySelectorDialog(catalog, tempDir, tempDir,
            tempDir.resolve(".claude"), () -> List.of(
                new MemorySelectorDialog.AgentMemoryFolder(
                    "Open Explore agent memory", tempDir.resolve("agent"), "user scope")));
    }

    private static void send(MemorySelectorDialog dialog, KeyType type) {
        dialog.handleKey(new KeyStroke(type), new AtomicBoolean(true));
    }

    private static void awaitPicking(MemorySelectorDialog dialog) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (dialog.visibleState() != MemorySelectorDialog.PublicState.PICKING_S
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(MemorySelectorDialog.PublicState.PICKING_S, dialog.visibleState());
    }

    private static UserKeybindingsStore createStore(Path file) throws Exception {
        Method create = UserKeybindingsStore.class
            .getDeclaredMethod("create", Path.class, boolean.class);
        create.setAccessible(true);
        return (UserKeybindingsStore) create.invoke(null, file, true);
    }

    private static final class FakeCatalog implements MemoryCatalog {
        private boolean autoMemory;
        private boolean autoDream;
        private final boolean teamMemory;

        private FakeCatalog(boolean autoMemory, boolean autoDream, boolean teamMemory) {
            this.autoMemory = autoMemory;
            this.autoDream = autoDream;
            this.teamMemory = teamMemory;
        }

        @Override public List<File> scan(Path cwd) { return List.of(); }
        @Override public boolean autoMemoryEnabled() { return autoMemory; }
        @Override public void setAutoMemoryEnabled(boolean enabled) { autoMemory = enabled; }
        @Override public Path autoMemoryDirectory(Path cwd) { return cwd.resolve("auto"); }
        @Override public boolean autoDreamEnabled() { return autoDream; }
        @Override public void setAutoDreamEnabled(boolean enabled) { autoDream = enabled; }
        @Override public long lastDreamAtMillis(Path cwd) { return 0L; }
        @Override public boolean teamMemoryEnabled() { return teamMemory; }
        @Override public Path teamMemoryDirectory(Path cwd) { return cwd.resolve("team"); }
    }
}

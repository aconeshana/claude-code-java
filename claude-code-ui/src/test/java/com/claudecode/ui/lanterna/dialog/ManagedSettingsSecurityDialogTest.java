package com.claudecode.ui.lanterna.dialog;

import com.claudecode.keybindings.UserKeybindingsStore;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import org.junit.jupiter.api.Assertions;
import com.googlecode.lanterna.TerminalSize;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Regression tests for the managed-settings startup approval overlay. */
class ManagedSettingsSecurityDialogTest {

    @Test
    void idleChildRendererCanBeMeasuredBeforeFirstPrompt() {
        ManagedSettingsSecurityDialog dialog = new ManagedSettingsSecurityDialog();

        assertFalse(dialog.isActive());
        assertEquals(new TerminalSize(0, 0), dialog.calculatePreferredSize());
        TerminalSize childSize = assertDoesNotThrow(
            () -> dialog.getChildren().iterator().next().getPreferredSize(),
            "Lanterna measures child renderers while the overlay is still idle");
        assertEquals(new TerminalSize(0, 0), childSize);
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
            AtomicBoolean exited = new AtomicBoolean();
            ManagedSettingsSecurityDialog d = new ManagedSettingsSecurityDialog();
            d.setKeybindingsStore(store);
            d.prompt(tmp, List.of("dangerous"), () -> {}, () -> exited.set(true));
            d.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));
            assertFalse(exited.get());
            Assertions.assertTrue(d.isActive());
            d.handleKey(new KeyStroke('x', false, false), new AtomicBoolean(true));
            d.handleKey(new KeyStroke('z', false, false), new AtomicBoolean(true));
            assertFalse(d.isActive());
            Assertions.assertTrue(exited.get());
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

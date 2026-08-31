package com.claudecode.ui.lanterna.dialog;

import com.claudecode.keybindings.UserKeybindingsStore;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThinkingToggleDialogTest {

    @Test
    void selectingDifferentValueAtSessionStartAppliesImmediately() {
        ThinkingToggleDialog dialog = new ThinkingToggleDialog();
        AtomicReference<Boolean> result = new AtomicReference<>();
        dialog.show(true, false, result::set);

        dialog.handleKey(key(KeyType.ARROW_DOWN), deliver());
        dialog.handleKey(key(KeyType.ENTER), deliver());

        assertFalse(dialog.isActive());
        assertEquals(Boolean.FALSE, result.get());
    }

    @Test
    void changingValueMidConversationRequiresConfirmation() {
        ThinkingToggleDialog dialog = new ThinkingToggleDialog();
        AtomicReference<Boolean> result = new AtomicReference<>();
        dialog.show(true, true, result::set);

        dialog.handleKey(key(KeyType.ARROW_DOWN), deliver());
        dialog.handleKey(key(KeyType.ENTER), deliver());

        assertTrue(dialog.isActive());
        assertTrue(dialog.isConfirmationPendingForTest());
        assertNull(result.get());

        dialog.handleKey(key(KeyType.ENTER), deliver());
        assertFalse(dialog.isActive());
        assertEquals(Boolean.FALSE, result.get());
    }

    @Test
    void cancelDuringConfirmationReturnsToPickerThenCancelsDialog() {
        ThinkingToggleDialog dialog = new ThinkingToggleDialog();
        AtomicReference<Boolean> result = new AtomicReference<>();
        dialog.show(true, true, result::set);
        dialog.handleKey(key(KeyType.ARROW_DOWN), deliver());
        dialog.handleKey(key(KeyType.ENTER), deliver());

        dialog.handleKey(key(KeyType.ESCAPE), deliver());
        assertTrue(dialog.isActive());
        assertFalse(dialog.isConfirmationPendingForTest());

        dialog.handleKey(key(KeyType.ESCAPE), deliver());
        assertFalse(dialog.isActive());
        assertNull(result.get());
    }

    @Test
    void choosingCurrentValueMidConversationDoesNotWarn() {
        ThinkingToggleDialog dialog = new ThinkingToggleDialog();
        AtomicReference<Boolean> result = new AtomicReference<>();
        dialog.show(true, true, result::set);

        dialog.handleKey(key(KeyType.ENTER), deliver());

        assertFalse(dialog.isActive());
        assertEquals(Boolean.TRUE, result.get());
    }

    @Test
    void selectAndConfirmationBindingsSupportRebindAndNullUnbind(@TempDir Path tmp)
            throws Exception {
        Path file = tmp.resolve("keybindings.json");
        Files.writeString(file, """
            [
              {"context":"Select","bindings":{"x":"select:next","z":"select:accept","enter":null}},
              {"context":"Confirmation","bindings":{"a":"confirm:yes","b":"confirm:no","escape":null}}
            ]
            """);
        UserKeybindingsStore store = createStore(file);
        try {
            ThinkingToggleDialog dialog = new ThinkingToggleDialog();
            dialog.setKeybindingsStore(store);
            AtomicReference<Boolean> result = new AtomicReference<>();
            dialog.show(true, true, result::set);

            dialog.handleKey(new KeyStroke('x', false, false), deliver());
            dialog.handleKey(key(KeyType.ENTER), deliver());
            assertTrue(dialog.isActive(), "null-unbound Enter must not accept the picker");

            dialog.handleKey(new KeyStroke('z', false, false), deliver());
            assertTrue(dialog.isConfirmationPendingForTest());
            dialog.handleKey(key(KeyType.ESCAPE), deliver());
            assertTrue(dialog.isConfirmationPendingForTest(),
                "null-unbound Escape must not cancel confirmation");

            dialog.handleKey(new KeyStroke('b', false, false), deliver());
            assertFalse(dialog.isConfirmationPendingForTest());
            dialog.handleKey(new KeyStroke('z', false, false), deliver());
            dialog.handleKey(new KeyStroke('a', false, false), deliver());
            assertFalse(dialog.isActive());
            assertEquals(Boolean.FALSE, result.get());
        } finally {
            store.dispose();
        }
    }

    @Test
    void idleCollapsesToZeroSize() {
        ThinkingToggleDialog dialog = new ThinkingToggleDialog();
        assertEquals(new TerminalSize(0, 0), dialog.calculatePreferredSize());
    }

    private static KeyStroke key(KeyType type) {
        return new KeyStroke(type);
    }

    private static AtomicBoolean deliver() {
        return new AtomicBoolean(true);
    }

    private static UserKeybindingsStore createStore(Path file) throws Exception {
        Method create = UserKeybindingsStore.class
            .getDeclaredMethod("create", Path.class, boolean.class);
        create.setAccessible(true);
        return (UserKeybindingsStore) create.invoke(null, file, true);
    }
}

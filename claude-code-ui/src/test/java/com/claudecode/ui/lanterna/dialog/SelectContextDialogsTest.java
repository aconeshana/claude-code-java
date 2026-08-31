package com.claudecode.ui.lanterna.dialog;


import com.claudecode.commands.impl.terminal.CopyCommand.CodeBlock;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;


class SelectContextDialogsTest {

    @Test
    void themeOutputStyleAndCopyConsumeCustomSelectBindings(@TempDir Path tmp)
            throws Exception {
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
            AtomicReference<String> themeResult = new AtomicReference<>();
            ThemePickerDialog theme = new ThemePickerDialog();
            theme.setKeybindingsStore(store);
            theme.show("dark", _ -> {}, themeResult::set);
            press(theme, new KeyStroke(KeyType.ENTER));
            assertTrue(theme.isActive());
            press(theme, key('x'));
            press(theme, key('z'));
            assertEquals("light", themeResult.get());

            AtomicReference<String> styleResult = new AtomicReference<>();
            OutputStylePickerDialog style = new OutputStylePickerDialog();
            style.setKeybindingsStore(store);
            style.show("default", styleResult::set);
            press(style, new KeyStroke(KeyType.ENTER));
            assertTrue(style.isActive());
            press(style, key('x'));
            press(style, key('z'));
            assertEquals("Explanatory", styleResult.get());

            AtomicReference<CopyPickerDialog.CopySelection> copyResult =
                new AtomicReference<>();
            CopyPickerDialog copy = new CopyPickerDialog();
            copy.setKeybindingsStore(store);
            copy.show("full", List.of(new CodeBlock("code", "text")), copyResult::set);
            press(copy, new KeyStroke(KeyType.ENTER));
            assertTrue(copy.isActive());
            press(copy, key('x'));
            press(copy, key('z'));
            assertEquals(new CopyPickerDialog.CopySelection(0, false, false),
                copyResult.get());
        } finally {
            store.dispose();
        }
    }

    private static KeyStroke key(char value) {
        return new KeyStroke(value, false, false);
    }

    private static void press(InlineOverlay dialog,
                              KeyStroke key) {
        dialog.handleKey(key, new AtomicBoolean(true));
    }

    private static UserKeybindingsStore createStore(Path file) throws Exception {
        Method create = UserKeybindingsStore.class
            .getDeclaredMethod("create", Path.class, boolean.class);
        create.setAccessible(true);
        return (UserKeybindingsStore) create.invoke(null, file, true);
    }
}

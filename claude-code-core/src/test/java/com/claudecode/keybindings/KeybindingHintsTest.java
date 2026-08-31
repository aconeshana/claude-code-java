package com.claudecode.keybindings;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KeybindingHintsTest {

    @TempDir
    Path tempDir;

    private UserKeybindingsStore store;

    @AfterEach
    void tearDown() {
        if (store != null) store.dispose();
    }

    @Test
    void expandUsesReleasedFallbackWithoutAStore() {
        assertEquals(DefaultBindings.EXPAND_HINT, KeybindingHints.expand(null));
    }

    @Test
    void expandUsesLastLiveUserBinding() throws IOException {
        Path file = tempDir.resolve("keybindings.json");
        Files.writeString(file, """
            {
              "bindings": [
                { "context": "Global", "bindings": {
                  "ctrl+x": "app:toggleTranscript"
                } }
              ]
            }
            """);
        store = UserKeybindingsStore.create(file, true);

        assertEquals("(ctrl+x to expand)", KeybindingHints.expand(store));
    }

    @Test
    void shortcutFallsBackWhenActionHasNoBinding() throws IOException {
        Path file = tempDir.resolve("keybindings.json");
        Files.writeString(file, "{ \"bindings\": [] }");
        store = UserKeybindingsStore.create(file, true);

        assertEquals("alt+z", KeybindingHints.shortcut(
            store, "missing:action", "Global", "alt+z"));
    }
}

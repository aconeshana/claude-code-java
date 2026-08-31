package com.claudecode.ui.lanterna.dialog;

import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.tools.worktree.WorktreeSession;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class WorktreeExitDialogKeybindingsTest {

    @Test
    void selectCancelCanBeReboundAndEscapeUnbound(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("keybindings.json");
        Files.writeString(file, """
            [{"context":"Select","bindings":{
              "x":"select:cancel",
              "escape":null
            }}]
            """);
        UserKeybindingsStore store = createStore(file);
        try {
            Process gitInit = new ProcessBuilder("git", "init", "-q")
                .directory(tmp.toFile()).start();
            assertEquals(0, gitInit.waitFor());
            Files.writeString(tmp.resolve("uncommitted.txt"), "changed");
            AtomicReference<WorktreeExitDialog.Result> result = new AtomicReference<>();
            WorktreeExitDialog d = new WorktreeExitDialog();
            d.setKeybindingsStore(store);
            d.show(new WorktreeSession(tmp.toString(), tmp.toString(), "w", "branch",
                "main", "missing", "session", null, false, 0, false), result::set);

            long deadline = System.currentTimeMillis() + 2_000;
            while (System.currentTimeMillis() < deadline && result.get() == null) {
                d.handleKey(new KeyStroke(KeyType.ESCAPE), new AtomicBoolean(true));
                if (!d.isActive()) break;
                d.handleKey(new KeyStroke('x', false, false), new AtomicBoolean(true));
                if (result.get() == null) Thread.sleep(10);
            }
            assertNotNull(result.get());
            assertFalse(result.get().proceedExit());
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

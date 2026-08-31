package com.claudecode.ui.lanterna.dialog;

import com.claudecode.keybindings.UserKeybindingsStore;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ExportDialogKeybindingsTest {

    @Test
    void fileSaveRunsOffTheCallingThreadAndPublishesCompletionLater(@TempDir Path tmp)
            throws Exception {
        ExportDialog dialog = new ExportDialog();
        CountDownLatch writerStarted = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        ConcurrentLinkedQueue<Runnable> guiTasks = new ConcurrentLinkedQueue<>();
        AtomicBoolean completed = new AtomicBoolean();
        dialog.setGuiInvoker(guiTasks::add);
        dialog.setFileWriter((path, content) -> {
            writerStarted.countDown();
            releaseWriter.await();
            Files.writeString(path, content);
        });
        dialog.show("content", "export.txt", tmp.toString(),
            (_, saved) -> completed.set(saved));
        dialog.handleKey(new KeyStroke(KeyType.ARROW_DOWN), new AtomicBoolean(true));
        dialog.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));

        dialog.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));

        assertTrue(writerStarted.await(1, TimeUnit.SECONDS));
        assertTrue(dialog.isActive());
        assertFalse(completed.get());
        releaseWriter.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (guiTasks.isEmpty() && System.nanoTime() < deadline) Thread.onSpinWait();
        Runnable completion = guiTasks.poll();
        assertTrue(completion != null, "save completion was not posted to the GUI");
        completion.run();
        assertTrue(completed.get());
        assertFalse(dialog.isActive());
        assertEquals("content", Files.readString(tmp.resolve("export.txt")));
    }

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
            AtomicBoolean called = new AtomicBoolean();
            ExportDialog d = new ExportDialog();
            d.setKeybindingsStore(store);
            d.show("content", "export.txt", tmp.toString(),
                (_, _) -> called.set(true));
            d.handleKey(new KeyStroke(KeyType.ESCAPE), new AtomicBoolean(true));
            assertTrue(d.isActive());
            assertFalse(called.get());
            d.handleKey(new KeyStroke('x', false, false), new AtomicBoolean(true));
            assertFalse(d.isActive());
            assertTrue(called.get());
        } finally {
            store.dispose();
        }
    }

    @Test
    void filenameBackUsesSettingsContextAndSupportsNullUnbind(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("keybindings.json");
        Files.writeString(file, """
            [{"context":"Settings","bindings":{
              "x":"confirm:no",
              "escape":null
            }}]
            """);
        UserKeybindingsStore store = createStore(file);
        try {
            ExportDialog d = new ExportDialog();
            d.setKeybindingsStore(store);
            d.show("content", "export.txt", tmp.toString(), (_, _) -> {});
            d.handleKey(new KeyStroke(KeyType.ARROW_DOWN), new AtomicBoolean(true));
            d.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));
            assertEquals(ExportDialog.State.FILENAME, d.stateForTest());

            d.handleKey(new KeyStroke(KeyType.ESCAPE), new AtomicBoolean(true));
            assertEquals(ExportDialog.State.FILENAME, d.stateForTest(),
                "null-unbound Escape must not use the hard-coded fallback");

            d.handleKey(new KeyStroke('x', false, false), new AtomicBoolean(true));
            assertEquals(ExportDialog.State.PICKER, d.stateForTest());
            assertTrue(d.isActive());
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

package com.claudecode.ui.lanterna.dialog;

import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.engine.PermissionAskContext;
import com.claudecode.core.serialization.JsonUtils;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.SameTextGUIThread;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PermissionPreviewPreparerTest {

    @Test
    void snapshotsInputAndFileContentBeforePublishingThePrompt(@TempDir Path temp)
            throws Exception {
        Path file = temp.resolve("Example.java");
        Files.writeString(file, "class Example { int oldValue; }\n");
        var input = JsonUtils.getMapper().createObjectNode()
            .put("file_path", file.toString())
            .put("old_string", "int oldValue;")
            .put("new_string", "int newValue;");
        PermissionAskContext context = PermissionAskContext.simple(
            "Edit", input, "toolu_edit");

        PreparedPermissionPrompt prepared = PermissionPreviewPreparer.standard().prepare(context);
        input.put("new_string", "int mutatedValue;");
        Files.writeString(file, "class Example { int diskChanged; }\n");

        assertNotSame(input, prepared.context().input());
        assertEquals("int newValue;", prepared.context().input().path("new_string").asText());
        assertTrue(prepared.rejectedFileChangePreview().hunks().getFirst().lines().stream()
            .anyMatch(line -> Strings.CS.contains(line, "int newValue;")));
        assertTrue(prepared.rejectedFileChangePreview().hunks().getFirst().lines().stream()
            .noneMatch(line -> Strings.CS.contains(line, "diskChanged")));
    }

    @Test
    void readsTheFileOnlyOnceForDialogAndRejectionSnapshots() {
        AtomicInteger reads = new AtomicInteger();
        PermissionPreviewPreparer preparer = new PermissionPreviewPreparer(_ -> {
            reads.incrementAndGet();
            return FileSnapshotReader.FileSnapshot.present("before\n");
        });
        var input = JsonUtils.getMapper().createObjectNode()
            .put("file_path", "/tmp/one-read.txt")
            .put("old_string", "before")
            .put("new_string", "after");

        PreparedPermissionPrompt prepared = preparer.prepare(
            PermissionAskContext.simple("Edit", input, "toolu_one_read"));

        assertEquals(1, reads.get());
        assertTrue(prepared.rejectedFileChangePreview().hunks().stream()
            .flatMap(hunk -> hunk.lines().stream())
            .anyMatch(line -> Strings.CS.contains(line, "after")));
    }

    @Test
    void blockedSnapshotReadDoesNotBlockTheGuiThread() throws Exception {
        CountDownLatch readStarted = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        PermissionPreviewPreparer preparer = new PermissionPreviewPreparer(_ -> {
            readStarted.countDown();
            try {
                releaseRead.await();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            return FileSnapshotReader.FileSnapshot.present("before\n");
        });
        var input = JsonUtils.getMapper().createObjectNode()
            .put("file_path", "/tmp/blocked-preview.txt")
            .put("old_string", "before")
            .put("new_string", "after");
        CompletableFuture<PreparedPermissionPrompt> prepared = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> prepared.complete(preparer.prepare(
            PermissionAskContext.simple("Edit", input, "toolu_blocked"))));
        assertTrue(readStarted.await(1, TimeUnit.SECONDS));

        var terminal = new DefaultVirtualTerminal(new TerminalSize(80, 24));
        var screen = new TerminalScreen(terminal);
        screen.startScreen();
        var gui = new MultiWindowTextGUI(new SameTextGUIThread.Factory(), screen);
        AtomicBoolean guiTaskRan = new AtomicBoolean();
        gui.getGUIThread().invokeLater(() -> guiTaskRan.set(true));
        gui.getGUIThread().processEventsAndUpdate();

        assertTrue(guiTaskRan.get());
        assertFalse(prepared.isDone());
        releaseRead.countDown();
        prepared.get(1, TimeUnit.SECONDS);
    }
}

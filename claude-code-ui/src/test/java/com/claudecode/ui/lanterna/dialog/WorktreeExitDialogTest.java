package com.claudecode.ui.lanterna.dialog;

import com.claudecode.core.state.CwdState;
import com.claudecode.tools.worktree.WorktreeService;
import com.claudecode.tools.worktree.WorktreeSession;
import com.googlecode.lanterna.input.KeyStroke;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class WorktreeExitDialogTest {

    private String savedUserDir;
    private Path savedOriginalCwd;

    @BeforeEach
    void saveGlobals() {
        savedUserDir = System.getProperty("user.dir");
        savedOriginalCwd = CwdState.getOriginalCwd();
    }

    @AfterEach
    void restoreGlobals() {
        WorktreeService.clearCurrentSessionForTests();
        System.setProperty("user.dir", savedUserDir);
        if (savedOriginalCwd != null) CwdState.setOriginalCwd(savedOriginalCwd);
    }

    @Test
    void namedSessionWithCleanWorktreeAsksInsteadOfRemoving(@TempDir Path tmp) throws Exception {
        WorktreeSession session = cleanWorktreeSession(tmp);
        AtomicReference<WorktreeExitDialog.Result> result = new AtomicReference<>();
        WorktreeExitDialog dialog = new WorktreeExitDialog();
        dialog.setSessionTitleSource(() -> "mysess");
        dialog.show(session, result::set);

        // Only the asking state reacts to an option number, so keep pressing "1" (Keep)
        // until the probe has finished and the choice lands.
        long deadline = System.currentTimeMillis() + 5_000;
        while (result.get() == null && System.currentTimeMillis() < deadline) {
            dialog.handleKey(new KeyStroke('1', false, false), new AtomicBoolean(true));
            Thread.sleep(10);
        }

        assertNotNull(result.get(), "a named session must wait for the user's choice");
        assertTrue(result.get().proceedExit());
        assertTrue(Strings.CS.startsWith(result.get().message(), "Worktree kept."), result.get().message());
        assertTrue(Files.isDirectory(Path.of(session.worktreePath())));
    }

    @Test
    void unnamedSessionWithCleanWorktreeIsRemovedWithoutAsking(@TempDir Path tmp) throws Exception {
        WorktreeSession session = cleanWorktreeSession(tmp);
        AtomicReference<WorktreeExitDialog.Result> result = new AtomicReference<>();
        WorktreeExitDialog dialog = new WorktreeExitDialog();
        dialog.setSessionTitleSource(() -> "  ");
        dialog.show(session, result::set);

        long deadline = System.currentTimeMillis() + 5_000;
        while (result.get() == null && System.currentTimeMillis() < deadline) Thread.sleep(10);

        assertNotNull(result.get());
        assertTrue(result.get().proceedExit());
        assertTrue(Strings.CS.endsWith(result.get().message(), "(no changes)"), result.get().message());
    }

    /**
     * An empty git repository stands in for the worktree; the original checkout is a plain
     * directory, so the cleanup's git commands fail harmlessly instead of touching a real repo.
     */
    private static WorktreeSession cleanWorktreeSession(Path tmp) throws Exception {
        Path worktree = Files.createDirectories(tmp.resolve("wt"));
        Path original = Files.createDirectories(tmp.resolve("orig"));
        Process gitInit = new ProcessBuilder("git", "init", "-q").directory(worktree.toFile()).start();
        assertEquals(0, gitInit.waitFor());
        WorktreeSession session = new WorktreeSession(original.toString(), worktree.toString(), "wt",
            null, "main", null, "session", null, false, 0, false);
        WorktreeService.restoreWorktreeSession(session);
        return session;
    }
}

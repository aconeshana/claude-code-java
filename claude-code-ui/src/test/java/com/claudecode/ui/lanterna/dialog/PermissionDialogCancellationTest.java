package com.claudecode.ui.lanterna.dialog;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.engine.PermissionAskCallback;
import com.claudecode.core.engine.PermissionAskContext;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.SameTextGUIThread;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PermissionDialogCancellationTest {

    @Test
    void remoteResolutionCancelsAndUnblocksTheLocalDialog() throws Exception {
        var terminal = new DefaultVirtualTerminal(new TerminalSize(100, 40));
        var screen = new TerminalScreen(terminal);
        screen.startScreen();
        var gui = new MultiWindowTextGUI(new SameTextGUIThread.Factory(), screen);
        var dialog = new PermissionDialog();
        CompletableFuture<PermissionAskCallback.Result> result = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> result.complete(dialog.showAndWait(
            gui, PermissionPreviewPreparer.standard().prepare(
                PermissionAskContext.simple("Bash", null, "tool-1")), null,
            _ -> {}, () -> {}, _ -> {}, () -> false)));

        long deadline = System.currentTimeMillis() + 2000;
        while (!dialog.isActive() && System.currentTimeMillis() < deadline) {
            gui.getGUIThread().processEventsAndUpdate();
            Thread.sleep(5);
        }
        assertTrue(dialog.isActive());

        gui.getGUIThread().invokeLater(dialog::cancelPending);
        while (dialog.isActive() && System.currentTimeMillis() < deadline) {
            gui.getGUIThread().processEventsAndUpdate();
            Thread.sleep(5);
        }

        assertFalse(result.get(2, TimeUnit.SECONDS).allowed());
        assertFalse(dialog.isActive());
    }
}

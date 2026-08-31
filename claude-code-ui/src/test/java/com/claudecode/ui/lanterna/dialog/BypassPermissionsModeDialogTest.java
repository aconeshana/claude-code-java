package com.claudecode.ui.lanterna.dialog;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class BypassPermissionsModeDialogTest {

    @Test
    void idleDialogOccupiesNoRows() {
        BypassPermissionsModeDialog dialog = new BypassPermissionsModeDialog(24);

        assertFalse(dialog.isActive());
        assertEquals(new TerminalSize(0, 0), dialog.calculatePreferredSize());
        assertEquals(new TerminalSize(0, 0),
            dialog.getChildren().iterator().next().getPreferredSize());
    }

    @Test
    void defaultSelectionDeclinesAndDownThenEnterAccepts() {
        AtomicBoolean accepted = new AtomicBoolean();
        AtomicBoolean declined = new AtomicBoolean();
        BypassPermissionsModeDialog dialog = new BypassPermissionsModeDialog(24);
        dialog.prompt(() -> accepted.set(true), () -> declined.set(true), () -> {});

        assertEquals(new TerminalSize(80, 23), dialog.calculatePreferredSize(),
            "released setup dialog fills the terminal below its one-row top margin");
        assertEquals(new TerminalSize(80, 17),
            dialog.getChildren().iterator().next().getPreferredSize(),
            "visible warning copy occupies the released seventeen rows");

        dialog.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));

        assertFalse(accepted.get());
        assertTrue(declined.get());
        assertFalse(dialog.isActive());

        accepted.set(false);
        declined.set(false);
        dialog.prompt(() -> accepted.set(true), () -> declined.set(true), () -> {});
        dialog.handleKey(new KeyStroke(KeyType.ARROW_DOWN), new AtomicBoolean(true));
        dialog.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));

        assertTrue(accepted.get());
        assertFalse(declined.get());
        assertFalse(dialog.isActive());
    }

    @Test
    void escapeIsDistinctFromDecline() {
        AtomicBoolean declined = new AtomicBoolean();
        AtomicBoolean escaped = new AtomicBoolean();
        BypassPermissionsModeDialog dialog = new BypassPermissionsModeDialog(24);
        dialog.prompt(() -> {}, () -> declined.set(true), () -> escaped.set(true));

        dialog.handleKey(new KeyStroke(KeyType.ESCAPE), new AtomicBoolean(true));

        assertFalse(declined.get());
        assertTrue(escaped.get());
        assertFalse(dialog.isActive());
    }
}

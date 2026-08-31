package com.claudecode.ui.lanterna.status;

import com.claudecode.commands.StatusProperty;
import com.googlecode.lanterna.TerminalSize;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import com.claudecode.ui.lanterna.features.settings.SettingsTabContainer;

/**
 * Verifies {@link StatusPane}'s visibility state machine. Pixel rendering is
 * covered only by manual test (see {@link SettingsTabContainer}'s class Javadoc).
 */
class StatusPaneTest {

    @Test
    void startsHidden() {
        StatusPane p = new StatusPane();
        assertFalse(p.isShowing());
    }

    @Test
    void show_activatesPane() {
        StatusPane p = new StatusPane();
        p.show(List.of(new StatusProperty("Model", "claude-sonnet-4-6")));
        assertTrue(p.isShowing());
    }

    @Test
    void hide_deactivatesPane() {
        StatusPane p = new StatusPane();
        p.show(List.of(new StatusProperty("Model", "claude-sonnet-4-6")));
        p.hide();
        assertFalse(p.isShowing());
    }

    @Test
    void show_withEmptyList_doesNotThrow() {
        StatusPane p = new StatusPane();
        assertDoesNotThrow(() -> p.show(List.of()));
        assertTrue(p.isShowing());
    }

    @Test
    void diagnosticsAddSystemDiagnosticsSection() {
        StatusPane p = new StatusPane();
        p.show(List.of(new StatusProperty("Model", "sonnet")));
        p.setDiagnostics(List.of("Large CLAUDE.md will impact performance"));

        assertEquals(List.of("Large CLAUDE.md will impact performance"), p.diagnostics());
        assertEquals(new TerminalSize(60, 7), p.getPreferredSize());
    }
}

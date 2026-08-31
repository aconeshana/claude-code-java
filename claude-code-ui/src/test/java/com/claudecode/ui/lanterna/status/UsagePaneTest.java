package com.claudecode.ui.lanterna.status;

import com.claudecode.core.engine.SessionCostState;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.graphics.BasicTextImage;
import com.googlecode.lanterna.gui2.TextGUIGraphicsBridge;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import com.claudecode.ui.lanterna.features.settings.SettingsTabContainer;

/**
 * Verifies {@link UsagePane}'s visibility state machine. Pixel rendering is
 * covered only by manual test (see {@link SettingsTabContainer}'s class Javadoc).
 */
class UsagePaneTest {

    @Test
    void startsHidden() {
        UsagePane p = new UsagePane();
        assertFalse(p.isShowing());
    }

    @Test
    void show_activatesPane() {
        UsagePane p = new UsagePane();
        p.show();
        assertTrue(p.isShowing());
    }

    @Test
    void hide_deactivatesPane() {
        UsagePane p = new UsagePane();
        p.show();
        p.hide();
        assertFalse(p.isShowing());
    }

    @Test
    void released197RendersCurrentSessionUsageAt80Columns() {
        SessionCostState.get().reset();
        UsagePane pane = new UsagePane();
        pane.show();
        TerminalSize size = new TerminalSize(80, pane.getPreferredSize().getRows());
        pane.setSize(size);
        BasicTextImage image = new BasicTextImage(size);

        pane.draw(TextGUIGraphicsBridge.wrap(null, image.newTextGraphics()));

        assertEquals(9, size.getRows());
        assertEquals("  Session", line(image, 0).stripTrailing());
        assertEquals("  Total cost:            $0.0000", line(image, 2).stripTrailing());
        assertEquals("  Total duration (API):  0s", line(image, 3).stripTrailing());
        assertTrue(line(image, 4).startsWith("  Total duration (wall): "));
        assertEquals("  Total code changes:    0 lines added, 0 lines removed",
            line(image, 5).stripTrailing());
        assertEquals("  Usage:                 0 input, 0 output, 0 cache read, 0 cache write",
            line(image, 6).stripTrailing());
        assertEquals("  Esc to cancel", line(image, 8).stripTrailing());
    }

    private static String line(BasicTextImage image, int row) {
        StringBuilder result = new StringBuilder(image.getSize().getColumns());
        for (int column = 0; column < image.getSize().getColumns(); column++) {
            result.append(image.getCharacterAt(column, row).getCharacterString());
        }
        return result.toString();
    }
}

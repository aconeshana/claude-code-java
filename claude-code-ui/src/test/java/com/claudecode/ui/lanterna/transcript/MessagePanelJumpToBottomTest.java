package com.claudecode.ui.lanterna.transcript;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import java.util.List;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

/**
 * Verifies the floating "Jump to bottom" pill painted on the bottom row of
 * {@link MessagePanel} when scrolled up, and its click-to-jump behavior.
 */
final class MessagePanelJumpToBottomTest {

    @Test
    void jumpToBottomPillRendersWhenScrolledUpAndScrollsOnClick() throws Exception {
        TerminalSize size = new TerminalSize(60, 10);
        DefaultVirtualTerminal terminal = new DefaultVirtualTerminal(size);
        TerminalScreen screen = new TerminalScreen(terminal);
        screen.startScreen();
        try {
            MultiWindowTextGUI gui = new MultiWindowTextGUI(screen);
            MessagePanel panel = new MessagePanel();
            panel.setSize(size);
            for (int i = 0; i < 40; i++) {
                panel.appendLine("Line " + i, TextColor.ANSI.DEFAULT);
            }
            BasicWindow window = new BasicWindow();
            window.setHints(List.of(Window.Hint.NO_DECORATIONS, Window.Hint.FULL_SCREEN));
            window.setComponent(panel);
            gui.addWindow(window);
            gui.updateScreen();

            // At bottom initially: pill is not shown, click is not consumed.
            assertFalse(panel.isScrolledUp());
            assertFalse(Strings.CS.contains(rowText(screen, 9), "Jump to bottom"));
            assertFalse(panel.handleJumpToBottomMouse(
                new MouseAction(MouseActionType.CLICK_DOWN, 1, new TerminalPosition(30, 9))));

            // Scroll up: pill appears on bottom row and clicking it jumps back to bottom.
            panel.pageUp();
            assertTrue(panel.isScrolledUp());
            gui.updateScreen();
            assertTrue(Strings.CS.contains(rowText(screen, 9), "Jump to bottom"));

            assertTrue(panel.handleJumpToBottomMouse(
                new MouseAction(MouseActionType.CLICK_DOWN, 1, new TerminalPosition(30, 9))));
            assertFalse(panel.isScrolledUp());
            gui.updateScreen();
            assertFalse(Strings.CS.contains(rowText(screen, 9), "Jump to bottom"));
        } finally {
            screen.stopScreen();
        }
    }

    private static String rowText(TerminalScreen screen, int row) {
        StringBuilder sb = new StringBuilder();
        for (int col = 0; col < screen.getTerminalSize().getColumns(); col++) {
            sb.append(screen.getBackCharacter(col, row).getCharacterString());
        }
        return sb.toString();
    }
}

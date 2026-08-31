package com.claudecode.ui.lanterna.theme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.SameTextGUIThread;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import com.claudecode.ui.lanterna.components.HighlightedTextBox;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Renders a real {@link HighlightedTextBox} under {@link ClaudeTheme} to prove the
 * typed text colour reaches the terminal buffer as the terminal-default
 * foreground.
 *
 * <p>InputPanel's prompt is a {@code HighlightedTextBox}, a subclass of Lanterna's
 * {@code TextBox}. Because {@code SimpleTheme.getDefinition()} is an exact-class
 * lookup, the terminal-default override must be registered against the concrete
 * class — this test exercises the exact rendering path the prompt uses and
 * asserts the character cell holding the typed text reports
 * {@link TextColor.ANSI#DEFAULT}, keeping a regression back to fixed white visible.
 */
class ClaudeTextBoxRenderColorTest {

    @AfterEach
    void resetScheme() {
        LanternaTheme.setScheme(LanternaTheme.Scheme.DARK);
    }

    @Test
    void typedTextBoxTextRendersWithTerminalDefaultForeground() throws Exception {
        DefaultVirtualTerminal terminal = new DefaultVirtualTerminal(
            new com.googlecode.lanterna.TerminalSize(60, 10));
        TerminalScreen screen = new TerminalScreen(terminal);
        screen.startScreen();

        MultiWindowTextGUI gui = new MultiWindowTextGUI(
            new SameTextGUIThread.Factory(), screen);
        gui.setTheme(ClaudeTheme.build());

        BasicWindow window = new BasicWindow();
        window.setHints(Set.of(Window.Hint.FULL_SCREEN));
        HighlightedTextBox box = new HighlightedTextBox(
            new com.googlecode.lanterna.TerminalSize(40, 1),
            com.googlecode.lanterna.gui2.TextBox.Style.SINGLE_LINE,
            () -> List.of());
        box.setText("hello");
        window.setComponent(box);
        gui.addWindow(window);

        gui.getGUIThread().processEventsAndUpdate();

        // Locate the 'h' of the typed "hello" (row 1, col 1 in the default
        // FULL_SCREEN layout) and assert its character cell foreground is the
        // terminal default — not a fixed white. (Border cells '│'/col 0 are white
        // by design via the theme's regular foreground; we must read the *text*
        // cell, not the first non-null cell.)
        TextColor typedFg = null;
        outer:
        for (int r = 0; r < 10; r++) {
            for (int col = 0; col < 60; col++) {
                var ch = terminal.getCharacter(col, r);
                if (ch.getCharacter() == 'h') { typedFg = ch.getForegroundColor(); break outer; }
            }
        }
        assertTrue(typedFg != null, "typed text must occupy a character cell");
        assertEquals(TextColor.ANSI.DEFAULT, typedFg);
    }
}
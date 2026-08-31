package com.claudecode.ui.lanterna.status;

import org.apache.commons.lang3.Strings;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.EmptySpace;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.SameTextGUIThread;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import com.claudecode.ui.lanterna.theme.LanternaTheme;

/**
 * Renders {@link StatusLineComponent} against a virtual terminal to verify it
 * paints the status-line command output: plain text, ANSI color preservation
 * (including colored bars), multi-line output, left padding, and collapse
 * to zero height when cleared.
 *
 * <p>Reads are relative to the component's global origin — the windowing GUI
 * places the borderless window at a small offset, so tests locate the
 * component and index from there rather than assuming (0,0).
 */
class StatusLineComponentTest {

    private record Rendered(DefaultVirtualTerminal term, int originCol, int originRow) {
        char at(int col, int row) {
            return term.getBufferCharacter(originCol + col, originRow + row).getCharacterString().charAt(0);
        }
        TextColor fgAt(int col, int row) {
            return term.getBufferCharacter(originCol + col, originRow + row).getForegroundColor();
        }
        String line(int row, int width) {
            StringBuilder sb = new StringBuilder();
            for (int c = 0; c < width; c++) sb.append(at(c, row));
            return sb.toString();
        }
    }

    /** Renders {@code comp} inside a borderless window and returns a reader anchored at its origin. */
    private static Rendered render(StatusLineComponent comp) throws Exception {
        DefaultVirtualTerminal term = new DefaultVirtualTerminal(new TerminalSize(80, 10));
        TerminalScreen screen = new TerminalScreen(term);
        screen.startScreen();
        MultiWindowTextGUI gui = new MultiWindowTextGUI(new SameTextGUIThread.Factory(), screen);

        Panel root = new Panel(new LinearLayout(Direction.VERTICAL));
        root.addComponent(comp, LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        root.addComponent(new EmptySpace(new TerminalSize(80, 1)));
        BasicWindow w = new BasicWindow();
        w.setHints(List.of(Window.Hint.NO_DECORATIONS));
        w.setComponent(root);
        gui.addWindow(w);
        gui.updateScreen();
        Thread.sleep(80);

        TerminalPosition origin = comp.toGlobal(TerminalPosition.of(0, 0));
        return new Rendered(term, origin.getColumn(), origin.getRow());
    }

    @Test
    void rendersPlainText() throws Exception {
        StatusLineComponent c = new StatusLineComponent();
        c.setStatusText("my-project | main", 0);
        assertTrue(c.hasText());
        Rendered r = render(c);
        assertTrue(Strings.CS.contains(r.line(0, 20), "my-project | main"), "plain text should render");
    }

    @Test
    void preservesAnsiColor() throws Exception {
        StatusLineComponent c = new StatusLineComponent();
        // green "OK" via SGR 32 (real ESC byte)
        c.setStatusText("[32mOK[0m done", 0);
        Rendered r = render(c);
        assertEquals('O', r.at(0, 0));
        TextColor fg = r.fgAt(0, 0);
        assertNotNull(fg);
        assertNotEquals(TextColor.ANSI.DEFAULT, fg, "colored 'O' must not be default fg");
        assertNotEquals(LanternaTheme.welcomeDim(), fg, "colored 'O' must not fall back to dim");
    }

    @Test
    void rendersMultipleLines() throws Exception {
        StatusLineComponent c = new StatusLineComponent();
        c.setStatusText("line one\nline two", 0);
        Rendered r = render(c);
        assertTrue(Strings.CS.contains(r.line(0, 12), "line one"), "first line");
        assertTrue(Strings.CS.contains(r.line(1, 12), "line two"), "second line (claude-hud is 2 lines)");
    }

    @Test
    void appliesLeftPadding() throws Exception {
        StatusLineComponent c = new StatusLineComponent();
        c.setStatusText("X", 3);
        Rendered r = render(c);
        // With padding 3, cols 0-2 are blank and 'X' sits at local col 3.
        assertEquals(' ', r.at(0, 0));
        assertEquals('X', r.at(3, 0));
    }

    @Test
    void clearCollapsesToNothing() throws Exception {
        StatusLineComponent c = new StatusLineComponent();
        c.setStatusText("visible", 0);
        assertTrue(c.hasText());
        c.clear();
        assertFalse(c.hasText());
        assertEquals(new TerminalSize(0, 0), c.getPreferredSize());
    }

    @Test
    void nullOrBlankTextClears() {
        StatusLineComponent c = new StatusLineComponent();
        c.setStatusText("x", 0);
        c.setStatusText(null, 0);
        assertFalse(c.hasText());
        c.setStatusText("y", 0);
        c.setStatusText("", 0);
        assertFalse(c.hasText());
    }
}

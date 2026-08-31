package com.claudecode.ui.lanterna.mouse;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.claudecode.ui.lanterna.transcript.MessagePanel;
import com.claudecode.ui.lanterna.transcript.SelectionAwareTextGUI;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.SameTextGUIThread;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SelectionControllerBareClickTest {

    @Test
    void dispatchesOnlyAReleaseWithoutDragSelection() throws Exception {
        Env env = env();
        AtomicInteger clicks = new AtomicInteger();
        env.controller.setBareClickHandler((_, _) -> {
            clicks.incrementAndGet();
            return true;
        });

        env.controller.handleMouse(mouse(MouseActionType.CLICK_DOWN, 3, 2));
        assertEquals(0, clicks.get());
        env.controller.handleMouse(mouse(MouseActionType.CLICK_RELEASE, 3, 2));
        assertEquals(1, clicks.get());

        env.controller.handleMouse(mouse(MouseActionType.CLICK_DOWN, 5, 2));
        env.controller.handleMouse(mouse(MouseActionType.DRAG, 8, 2));
        env.controller.handleMouse(mouse(MouseActionType.CLICK_RELEASE, 8, 2));
        assertEquals(1, clicks.get());
    }

    @Test
    void doubleClickAndShiftExtendedSelectionDoNotDispatchBareClicks() throws Exception {
        Env env = env();
        put(env.screen, 0, 2, "hello world");
        AtomicInteger clicks = new AtomicInteger();
        env.controller.setBareClickHandler((_, _) -> {
            clicks.incrementAndGet();
            return true;
        });

        env.controller.handleMouse(mouse(MouseActionType.CLICK_DOWN, 2, 2));
        env.controller.handleMouse(mouse(MouseActionType.CLICK_RELEASE, 2, 2));
        env.controller.handleMouse(mouse(MouseActionType.CLICK_DOWN, 2, 2));
        env.controller.handleMouse(mouse(MouseActionType.CLICK_RELEASE, 2, 2));
        assertEquals(1, clicks.get(), "the second click selects a word instead of moving the caret");

        MouseAction shiftDown = new MouseAction(MouseActionType.CLICK_DOWN, 1,
            new TerminalPosition(8, 2), false, false, true);
        env.controller.handleMouse(shiftDown);
        env.controller.handleMouse(mouse(MouseActionType.CLICK_RELEASE, 8, 2));
        assertEquals(1, clicks.get(), "Shift+click extends selection instead of dispatching a click");
    }

    private static MouseAction mouse(MouseActionType type, int col, int row) {
        return new MouseAction(type, 1, new TerminalPosition(col, row));
    }

    private static void put(TerminalScreen screen, int col, int row, String text) {
        int cursor = col;
        for (int index = 0; index < text.length(); index++) {
            TextCharacter character = TextCharacter.fromCharacter(text.charAt(index),
                TextColor.ANSI.DEFAULT, TextColor.ANSI.DEFAULT);
            screen.setCharacter(cursor, row, character);
            cursor += character.isDoubleWidth() ? 2 : 1;
        }
    }

    private static Env env() throws Exception {
        DefaultVirtualTerminal terminal = new DefaultVirtualTerminal(new TerminalSize(30, 8));
        TerminalScreen screen = new TerminalScreen(terminal);
        screen.startScreen();
        SelectionAwareTextGUI gui = new SelectionAwareTextGUI(
            new SameTextGUIThread.Factory(), screen);
        SelectionController controller = new SelectionController(gui, new MessagePanel(), false);
        return new Env(controller, screen);
    }

    private record Env(SelectionController controller, TerminalScreen screen) {}
}

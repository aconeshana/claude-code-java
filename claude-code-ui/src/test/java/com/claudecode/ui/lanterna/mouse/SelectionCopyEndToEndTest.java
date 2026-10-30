package com.claudecode.ui.lanterna.mouse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.ui.lanterna.repl.TuiOutputGuard;
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
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * End-to-end evidence for the copy path: a real {@link SelectionController}
 * driving a real {@link TerminalScreen}, with the actual OSC 52 payload the
 * controller emits captured and decoded.
 *
 * <p>This is the Java-side counterpart of the pty harness used against the
 * released 2.1.236 binary: instead of an external pty reading the terminal, the
 * test installs a terminal-output seam and reads the same {@code ESC ] 52 ; c ;
 * <base64> BEL} sequence. The layouts fed here are the ones measured from 236.
 */
class SelectionCopyEndToEndTest {

    private static final Pattern OSC52 =
        Pattern.compile("\u001b\\]52;c;([A-Za-z0-9+/=]+)");

    private static final Field ACTIVE_TERMINAL_OUT;

    static {
        try {
            ACTIVE_TERMINAL_OUT = TuiOutputGuard.class.getDeclaredField("activeTerminalOut");
            ACTIVE_TERMINAL_OUT.setAccessible(true);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static void setTerminalOut(PrintStream stream) {
        try {
            ACTIVE_TERMINAL_OUT.set(null, stream);
        } catch (IllegalAccessException failure) {
            throw new AssertionError("cannot install the terminal-output seam", failure);
        }
    }

    @AfterEach
    void restoreSeam() {
        setTerminalOut(null);
    }

    /** Capture the OSC 52 payload emitted while {@code body} runs. */
    private static String capturedCopy(Runnable body) {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        setTerminalOut(new PrintStream(sink, true, StandardCharsets.UTF_8));
        body.run();
        String raw = sink.toString(StandardCharsets.UTF_8);
        Matcher m = OSC52.matcher(raw);
        assertTrue(m.find(), "the controller must emit an OSC 52 copy sequence, got: " + raw);
        return new String(Base64.getDecoder().decode(m.group(1)), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("mouse drag across a plain row copies through OSC 52 verbatim")
    void mouseDragAcrossAPlainRowCopiesThroughOsc52() throws Exception {
        Env e = env(true);
        put(e.screen, 0, 2, "hello world");

        String copied = capturedCopy(() -> {
            e.controller.handleMouse(mouse(MouseActionType.CLICK_DOWN, 0, 2));
            e.controller.handleMouse(mouse(MouseActionType.DRAG, 4, 2));
            e.controller.handleMouse(mouse(MouseActionType.CLICK_RELEASE, 4, 2));
        });
        assertEquals("hello", copied);
    }

    @Test
    @DisplayName("a drag ending on a wide glyph's leading cell includes the glyph")
    void aDragEndingOnAWideGlyphsLeadingCellIncludesIt() throws Exception {
        Env e = env(true);
        put(e.screen, 0, 2, "ab\u4e2d\u6587cd");   // 中 at cells 2-3, 文 at 4-5

        // End the drag exactly on 中's leading cell (cell 2). Dropping the
        // glyph here is the bug the cell-owner lookup fixes.
        String copied = capturedCopy(() -> {
            e.controller.handleMouse(mouse(MouseActionType.CLICK_DOWN, 0, 2));
            e.controller.handleMouse(mouse(MouseActionType.DRAG, 2, 2));
            e.controller.handleMouse(mouse(MouseActionType.CLICK_RELEASE, 2, 2));
        });
        assertEquals("ab\u4e2d", copied,
            "ending on the leading cell of a wide glyph includes the whole glyph");
    }

    @Test
    @DisplayName("a drag ending on a wide glyph's padding cell also includes it")
    void aDragEndingOnAWideGlyphsPaddingCellIncludesIt() throws Exception {
        Env e = env(true);
        put(e.screen, 0, 2, "ab\u4e2d\u6587cd");

        // Cell 3 is 中's padding cell; 236 keeps the glyph here too.
        String copied = capturedCopy(() -> {
            e.controller.handleMouse(mouse(MouseActionType.CLICK_DOWN, 0, 2));
            e.controller.handleMouse(mouse(MouseActionType.DRAG, 3, 2));
            e.controller.handleMouse(mouse(MouseActionType.CLICK_RELEASE, 3, 2));
        });
        assertEquals("ab\u4e2d", copied,
            "a selection ending on the padding cell still includes the glyph");
    }

    @Test
    @DisplayName("a drag starting on a wide glyph's padding cell excludes it")
    void aDragStartingOnAWideGlyphsPaddingCellExcludesIt() throws Exception {
        Env e = env(true);
        put(e.screen, 0, 2, "ab\u4e2d\u6587cd");

        // Anchor on 中's padding cell (3), focus on 文's leading cell (4).
        // The padding cell owns no character, so the copy starts at 文.
        String copied = capturedCopy(() -> {
            e.controller.handleMouse(mouse(MouseActionType.CLICK_DOWN, 3, 2));
            e.controller.handleMouse(mouse(MouseActionType.DRAG, 4, 2));
            e.controller.handleMouse(mouse(MouseActionType.CLICK_RELEASE, 4, 2));
        });
        assertEquals("\u6587", copied,
            "anchoring on a padding cell excludes the glyph it pads");
    }

    @Test
    @DisplayName("a drag across rows keeps every row's content")
    void aDragAcrossRowsKeepsEveryRowsContent() throws Exception {
        Env e = env(true);
        put(e.screen, 0, 2, "first row");
        put(e.screen, 0, 3, "second");

        String copied = capturedCopy(() -> {
            e.controller.handleMouse(mouse(MouseActionType.CLICK_DOWN, 6, 2));
            e.controller.handleMouse(mouse(MouseActionType.DRAG, 5, 3));
            e.controller.handleMouse(mouse(MouseActionType.CLICK_RELEASE, 5, 3));
        });
        assertEquals("row\nsecond", copied);
    }

    @Test
    @DisplayName("the trailing padding of a row is not copied")
    void theTrailingPaddingOfARowIsNotCopied() throws Exception {
        Env e = env(true);
        put(e.screen, 0, 2, "abc");

        // Drag well past the end of "abc" — the untouched cells are padding.
        String copied = capturedCopy(() -> {
            e.controller.handleMouse(mouse(MouseActionType.CLICK_DOWN, 0, 2));
            e.controller.handleMouse(mouse(MouseActionType.DRAG, 20, 2));
            e.controller.handleMouse(mouse(MouseActionType.CLICK_RELEASE, 20, 2));
        });
        assertEquals("abc", copied);
    }

    @Test
    @DisplayName("with copy-on-select a release alone emits OSC 52")
    void withCopyOnSelectAReleaseAloneEmitsOsc52() throws Exception {
        Env e = env(true);
        put(e.screen, 0, 2, "word here");

        String copied = capturedCopy(() -> {
            e.controller.handleMouse(mouse(MouseActionType.CLICK_DOWN, 0, 2));
            e.controller.handleMouse(mouse(MouseActionType.DRAG, 3, 2));
            e.controller.handleMouse(mouse(MouseActionType.CLICK_RELEASE, 3, 2));
        });
        assertNotNull(copied);
        assertEquals("word", copied);
    }

    // ── harness ─────────────────────────────────────────────────────────────

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

    private static Env env(boolean copyOnSelect) throws Exception {
        DefaultVirtualTerminal terminal = new DefaultVirtualTerminal(new TerminalSize(30, 8));
        TerminalScreen screen = new TerminalScreen(terminal);
        screen.startScreen();
        SelectionAwareTextGUI gui = new SelectionAwareTextGUI(
            new SameTextGUIThread.Factory(), screen);
        SelectionController controller =
            new SelectionController(gui, new MessagePanel(), copyOnSelect);
        return new Env(controller, gui, screen);
    }

    private record Env(SelectionController controller, SelectionAwareTextGUI gui,
                       TerminalScreen screen) {}
}
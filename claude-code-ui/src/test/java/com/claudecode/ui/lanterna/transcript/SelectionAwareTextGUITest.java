package com.claudecode.ui.lanterna.transcript;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.AbstractComponent;
import com.googlecode.lanterna.gui2.ComponentRenderer;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.SameTextGUIThread;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import com.claudecode.ui.lanterna.input.PlainTextKeyStroke;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Screen-level selection host: {@link SelectionAwareTextGUI#rowText} back-buffer
 * extraction (CJK padding skip, trailing trim), selection mouse interception
 * above window dispatch, and the full-screen highlight overlay painted after
 * the GUI draw — including over window content, which is what makes selection
 * work in dialogs.
 */
class SelectionAwareTextGUITest {

    private record Env(DefaultVirtualTerminal term, TerminalScreen screen, SelectionAwareTextGUI gui) {}

    private static Env env(int cols, int rows) throws Exception {
        DefaultVirtualTerminal term = new DefaultVirtualTerminal(new TerminalSize(cols, rows));
        TerminalScreen screen = new TerminalScreen(term);
        screen.startScreen();
        SelectionAwareTextGUI gui = new SelectionAwareTextGUI(new SameTextGUIThread.Factory(), screen);
        return new Env(term, screen, gui);
    }

    private static void put(TerminalScreen screen, int col, int row, String text) {
        int c = col;
        for (int i = 0; i < text.length(); i++) {
            TextCharacter tc = TextCharacter.fromCharacter(text.charAt(i),
                TextColor.ANSI.DEFAULT, TextColor.ANSI.DEFAULT);
            screen.setCharacter(c, row, tc);
            c += tc.isDoubleWidth() ? 2 : 1;
        }
    }

    // ── rowText ─────────────────────────────────────────────────────────────

    @Test
    void rowTextReadsBackBufferAndTrimsTrailingPadding() throws Exception {
        Env e = env(20, 4);
        put(e.screen, 0, 0, "hello world");
        assertEquals("hello world", SelectionAwareTextGUI.rowText(e.screen, 0));
    }

    @Test
    void rowTextSkipsCjkPaddingCells() throws Exception {
        Env e = env(20, 4);
        put(e.screen, 0, 1, "你好ab");
        // 你=cells 0-1, 好=cells 2-3, a=4, b=5 — padding cells must not
        // duplicate the wide chars nor inject spaces between them.
        assertEquals("你好ab", SelectionAwareTextGUI.rowText(e.screen, 1));
    }

    @Test
    void rowTextOutOfRangeRowIsEmpty() throws Exception {
        Env e = env(20, 4);
        assertEquals("", SelectionAwareTextGUI.rowText(e.screen, -1));
        assertEquals("", SelectionAwareTextGUI.rowText(e.screen, 99));
    }

    @Test
    void selectedTextAcrossRowsUsesScreenRows() throws Exception {
        Env e = env(20, 4);
        put(e.screen, 0, 0, "first row");
        put(e.screen, 0, 1, "second");
        Selection sel = new Selection();
        sel.startSelection(6, 0);           // anchor at "r" of "row"
        sel.updateSelection(5, 1);          // focus past "second"
        String text = sel.getSelectedText(r -> SelectionAwareTextGUI.rowText(e.screen, r));
        assertEquals("row\nsecond", text);
    }

    // ── mouse interception ──────────────────────────────────────────────────

    @Test
    void interceptsSelectionMouseEventsAndConsumesThem() throws Exception {
        Env e = env(20, 4);
        List<MouseAction> seen = new ArrayList<>();
        e.gui.wireSelection(new Selection(), seen::add);

        MouseAction down = new MouseAction(MouseActionType.CLICK_DOWN, 1, new TerminalPosition(3, 2));
        MouseAction drag = new MouseAction(MouseActionType.DRAG, 1, new TerminalPosition(5, 2));
        MouseAction up   = new MouseAction(MouseActionType.CLICK_RELEASE, 1, new TerminalPosition(5, 2));
        assertTrue(e.gui.handleInput(down));
        assertTrue(e.gui.handleInput(drag));
        assertTrue(e.gui.handleInput(up));
        assertEquals(List.of(down, drag, up), seen);
    }

    @Test
    void clickableFooterGetsMouseEventBeforeTextSelection() throws Exception {
        Env e = env(20, 4);
        List<MouseAction> selected = new ArrayList<>();
        List<MouseAction> clicked = new ArrayList<>();
        e.gui.wireSelection(new Selection(), selected::add, ma -> {
            clicked.add(ma);
            return true;
        });

        MouseAction down = new MouseAction(MouseActionType.CLICK_DOWN, 1,
            new TerminalPosition(3, 2));
        MouseAction up = new MouseAction(MouseActionType.CLICK_RELEASE, 1,
            new TerminalPosition(3, 2));
        assertTrue(e.gui.handleInput(down));
        assertTrue(e.gui.handleInput(up));
        assertEquals(List.of(down, up), clicked);
        assertTrue(selected.isEmpty(), "clickable pills must not start a text selection");
    }

    @Test
    void wheelAndKeysAreNotIntercepted() throws Exception {
        Env e = env(20, 4);
        List<MouseAction> seen = new ArrayList<>();
        e.gui.wireSelection(new Selection(), seen::add);

        e.gui.handleInput(new MouseAction(MouseActionType.SCROLL_UP, 4, new TerminalPosition(3, 2)));
        e.gui.handleInput(new KeyStroke(KeyType.ENTER));
        assertTrue(seen.isEmpty());
    }

    @Test
    void activeInlineOverlayConsumesKeyboardBeforeWindowDispatch() throws Exception {
        Env e = env(20, 4);
        AtomicInteger routed = new AtomicInteger();
        e.gui.wireInlineOverlayInput(_ -> {
            routed.incrementAndGet();
            return true;
        });

        assertTrue(e.gui.handleInput(new KeyStroke(KeyType.ARROW_DOWN)));
        assertEquals(1, routed.get());
    }

    @Test
    void acceptedTextBatchDoesNotReplayThroughSingleKeyOverlayRoute() throws Exception {
        Env e = env(20, 4);
        AtomicInteger routed = new AtomicInteger();
        e.gui.wireInlineOverlayInput(_ -> {
            routed.incrementAndGet();
            return true;
        });
        e.gui.wirePlainTextBatch((_, text) -> text.equals("model"));

        assertTrue(e.gui.handleInput(new PlainTextKeyStroke("model")));
        assertEquals(0, routed.get());
    }

    @Test
    void unwiredGuiLeavesMouseEventsToNormalDispatch() throws Exception {
        Env e = env(20, 4);
        // No wireSelection: must not throw, falls through to super.
        MouseAction down = new MouseAction(MouseActionType.CLICK_DOWN, 1, new TerminalPosition(3, 2));
        assertDoesNotThrow(() -> e.gui.handleInput(down));
    }

    // ── highlight overlay ───────────────────────────────────────────────────

    @Test
    void overlayReversesSelectedCellsAcrossWindowContent() throws Exception {
        Env e = env(20, 4);
        BasicWindow w = new BasicWindow();
        w.setHints(Set.of(Window.Hint.FULL_SCREEN, Window.Hint.NO_DECORATIONS, Window.Hint.NO_POST_RENDERING));
        w.setComponent(new Label("hello dialog"));
        e.gui.addWindow(w);

        Selection sel = new Selection();
        e.gui.wireSelection(sel, _ -> {});
        sel.startSelection(0, 0);
        sel.updateSelection(4, 0);          // select cells (0,0)..(4,0) = "hello"
        e.gui.updateScreen();

        for (int col = 0; col <= 4; col++) {
            assertTrue(e.term.getBufferCharacter(col, 0).getModifiers().contains(SGR.REVERSE),
                "cell (" + col + ",0) should be reverse-video");
        }
        assertFalse(e.term.getBufferCharacter(5, 0).getModifiers().contains(SGR.REVERSE),
            "cell past the selection must stay unstyled");
        assertEquals("h", e.term.getBufferCharacter(0, 0).getCharacterString(),
            "overlay must recolor, not overwrite, window content");
    }

    @Test
    void overlayPreservesSelectedCjkWideCharacters() throws Exception {
        Env e = env(20, 4);
        BasicWindow w = new BasicWindow();
        w.setHints(Set.of(Window.Hint.FULL_SCREEN, Window.Hint.NO_DECORATIONS,
            Window.Hint.NO_POST_RENDERING));
        w.setComponent(new Label("中文 mixed"));
        e.gui.addWindow(w);

        Selection sel = new Selection();
        e.gui.wireSelection(sel, _ -> {});
        sel.startSelection(0, 0);
        sel.updateSelection(3, 0); // both cells occupied by each CJK glyph
        e.gui.updateScreen();

        assertEquals("中", e.term.getBufferCharacter(0, 0).getCharacterString(),
            "highlighting the spacer cell must not erase the leading wide glyph");
        assertEquals("文", e.term.getBufferCharacter(2, 0).getCharacterString());
        assertEquals("中文", sel.getSelectedText(
            row -> SelectionAwareTextGUI.rowText(e.screen, row)));
    }

    @Test
    void noSelectionMeansNoOverlay() throws Exception {
        Env e = env(20, 4);
        BasicWindow w = new BasicWindow();
        w.setHints(Set.of(Window.Hint.FULL_SCREEN, Window.Hint.NO_DECORATIONS, Window.Hint.NO_POST_RENDERING));
        w.setComponent(new Label("hello"));
        e.gui.addWindow(w);
        e.gui.wireSelection(new Selection(), _ -> {});
        e.gui.updateScreen();

        for (int col = 0; col < 5; col++) {
            assertFalse(e.term.getBufferCharacter(col, 0).getModifiers().contains(SGR.REVERSE));
        }
    }

    @Test
    void clearingSelectionRestoresRetainedWindowCells() throws Exception {
        Env e = env(20, 4);
        BasicWindow window = new BasicWindow();
        window.setHints(Set.of(Window.Hint.FULL_SCREEN, Window.Hint.NO_DECORATIONS,
            Window.Hint.NO_POST_RENDERING));
        window.setComponent(new Label("hello"));
        e.gui.addWindow(window);
        Selection selection = new Selection();
        e.gui.wireSelection(selection, _ -> {});
        selection.startSelection(0, 0);
        selection.updateSelection(4, 0);
        e.gui.updateScreen();
        assertTrue(e.term.getBufferCharacter(0, 0).getModifiers().contains(SGR.REVERSE));

        selection.clearSelection();
        e.gui.updateScreen();

        assertFalse(e.term.getBufferCharacter(0, 0).getModifiers().contains(SGR.REVERSE));
        assertEquals("h", e.term.getBufferCharacter(0, 0).getCharacterString());
    }

    @Test
    void closingAWindowForcesUnderlyingRetainedImageToBeRecomposited() throws Exception {
        Env e = env(20, 4);
        BasicWindow main = new BasicWindow();
        main.setHints(Set.of(Window.Hint.FULL_SCREEN, Window.Hint.NO_DECORATIONS,
            Window.Hint.NO_POST_RENDERING));
        main.setComponent(new Label("base"));
        e.gui.addWindow(main);
        e.gui.updateScreen();

        BasicWindow popup = new BasicWindow();
        popup.setHints(Set.of(Window.Hint.NO_DECORATIONS, Window.Hint.NO_POST_RENDERING,
            Window.Hint.FIXED_POSITION));
        popup.setPosition(TerminalPosition.of(0, 0));
        popup.setComponent(new Label("top"));
        e.gui.addWindow(popup);
        e.gui.updateScreen();
        assertEquals("t", e.term.getBufferCharacter(0, 0).getCharacterString());

        e.gui.removeWindow(popup);
        e.gui.updateScreen();

        assertEquals("b", e.term.getBufferCharacter(0, 0).getCharacterString());
    }

    @Test
    void fullScreenWindowSkipsTheCoveredBackgroundPanePaint() throws Exception {
        Env e = env(20, 4);
        AtomicInteger backgroundCellsPainted = new AtomicInteger();
        class CountingBackground extends AbstractComponent<CountingBackground> {
            @Override protected ComponentRenderer<CountingBackground> createDefaultRenderer() {
                return new ComponentRenderer<>() {
                    @Override public TerminalSize getPreferredSize(CountingBackground component) {
                        return new TerminalSize(20, 4);
                    }
                    @Override public void drawComponent(
                            TextGUIGraphics graphics, CountingBackground component) {
                        backgroundCellsPainted.addAndGet(
                            graphics.getSize().getColumns() * graphics.getSize().getRows());
                        graphics.fill('x');
                    }
                };
            }
        }
        e.gui.getBackgroundPane().setComponent(new CountingBackground());
        BasicWindow window = new BasicWindow();
        window.setHints(Set.of(Window.Hint.FULL_SCREEN, Window.Hint.NO_DECORATIONS,
            Window.Hint.NO_POST_RENDERING));
        window.setComponent(new Label("front"));
        e.gui.addWindow(window);

        e.gui.updateScreen();

        assertEquals(0, backgroundCellsPainted.get(),
            "the full-screen REPL window covers the background pane on every frame");
        assertEquals("f", e.term.getBufferCharacter(0, 0).getCharacterString());
    }

    /**
     * Lanterna throws from its paint path for content it refuses to render (a
     * control character in a Label, say). That throw used to unwind through
     * {@code updateScreen} into the GUI loop's fatal handler, which stops the
     * thread and freezes the whole TUI on its last frame. One window's bad
     * content must cost only that window's frame.
     */
    @Test
    void aWindowThatThrowsWhilePaintingDoesNotAbortTheFrame() throws Exception {
        Env e = env(20, 4);
        // Same shape as Label's renderer rejecting a control character.
        class ExplodingComponent extends AbstractComponent<ExplodingComponent> {
            @Override protected ComponentRenderer<ExplodingComponent> createDefaultRenderer() {
                return new ComponentRenderer<>() {
                    @Override public TerminalSize getPreferredSize(ExplodingComponent component) {
                        return new TerminalSize(4, 1);
                    }
                    @Override public void drawComponent(TextGUIGraphics graphics,
                                                        ExplodingComponent component) {
                        throw new IllegalArgumentException(
                            "Cannot create a TextCharacter from a control character (0x1b)");
                    }
                };
            }
        }
        BasicWindow exploding = new BasicWindow();
        exploding.setHints(Set.of(Window.Hint.NO_DECORATIONS, Window.Hint.NO_POST_RENDERING,
            Window.Hint.FIXED_POSITION));
        exploding.setPosition(TerminalPosition.of(0, 0));
        exploding.setComponent(new ExplodingComponent());
        BasicWindow healthy = new BasicWindow();
        healthy.setHints(Set.of(Window.Hint.NO_DECORATIONS, Window.Hint.NO_POST_RENDERING,
            Window.Hint.FIXED_POSITION));
        healthy.setPosition(TerminalPosition.of(0, 2));
        healthy.setComponent(new Label("ok"));
        e.gui.addWindow(exploding);
        e.gui.addWindow(healthy);

        assertDoesNotThrow(e.gui::updateScreen,
            "a component paint failure must not propagate to the GUI loop");
        assertEquals("o", e.term.getBufferCharacter(0, 2).getCharacterString(),
            "later windows in the same frame must still be composited");
        assertDoesNotThrow(e.gui::updateScreen, "the next frame must still render");
    }
}

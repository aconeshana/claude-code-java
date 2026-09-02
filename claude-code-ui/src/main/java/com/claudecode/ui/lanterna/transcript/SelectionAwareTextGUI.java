package com.claudecode.ui.lanterna.transcript;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.Borders;
import com.googlecode.lanterna.gui2.Interactable;
import com.googlecode.lanterna.gui2.TextGUIGraphicsBridge;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.googlecode.lanterna.gui2.TextGUIThreadFactory;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.gui2.WindowDecorationRenderer;
import com.googlecode.lanterna.gui2.WindowPostRenderer;
import com.googlecode.lanterna.graphics.BasicTextImage;
import com.googlecode.lanterna.graphics.TextImage;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;
import com.googlecode.lanterna.screen.Screen;
import java.io.IOException;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.claudecode.core.annotation.Explanation;
import com.claudecode.ui.lanterna.input.BackspaceRunKeyStroke;
import com.claudecode.ui.lanterna.input.PlainTextKeyStroke;

/**
 * {@link MultiWindowTextGUI} subclass hosting the SCREEN-LEVEL virtual text selection.
 */
public class SelectionAwareTextGUI extends MultiWindowTextGUI {

    private static final Logger log = LoggerFactory.getLogger(SelectionAwareTextGUI.class);

    /** Selection state — wired after construction via {@link #wireSelection}. */
    private volatile Selection selection;
    /** Routes intercepted press/drag/release to the SelectionController. */
    private volatile Consumer<MouseAction> selectionMouseHandler;
    /** Clickable UI surfaces get first refusal before text selection begins. */
    private volatile Predicate<MouseAction> actionMouseHandler;
    /** Retained image per Lanterna window, equivalent to MultiWindowTextGUI's private cache. */
    private final IdentityHashMap<Window, TextImage> windowBuffers = new IdentityHashMap<>();
    /** Render failures already reported, so a permanently bad window logs once. */
    private final Set<String> reportedRenderFailures = new HashSet<>();
    private volatile Runnable inputBatchStart = () -> {};
    private volatile Runnable inputBatchEnd = () -> {};
    private volatile BiPredicate<Interactable, String> plainTextBatchHandler = (_, _) -> false;
    private volatile BiPredicate<Interactable, Integer> backspaceBatchHandler = (_, _) -> false;
    private volatile Predicate<KeyStroke> inlineOverlayInputHandler = _ -> false;

    public SelectionAwareTextGUI(TextGUIThreadFactory guiThreadFactory, Screen screen) {
        super(guiThreadFactory, screen);
    }

    /**
     * Wire the selection state machine + mouse handler. Separate from the
     * constructor because the GUI is built in {@code initTerminal} while the
     * SelectionController is built later in {@code buildLayout}.
     */
    public void wireSelection(Selection selection, Consumer<MouseAction> mouseHandler) {
        wireSelection(selection, mouseHandler, null);
    }

    public void wireSelection(Selection selection, Consumer<MouseAction> mouseHandler,
                              Predicate<MouseAction> actionMouseHandler) {
        this.selection = selection;
        this.selectionMouseHandler = mouseHandler;
        this.actionMouseHandler = actionMouseHandler;
    }

    /** Wires a lightweight editor batch around Lanterna's built-in PTY drain loop. */
    public void wireInputBatch(Runnable start, Runnable end) {
        inputBatchStart = start != null ? start : () -> {};
        inputBatchEnd = end != null ? end : () -> {};
    }

    /** Wires the prompt's safe bulk-text insertion path. */
    public void wirePlainTextBatch(BiPredicate<Interactable, String> handler) {
        plainTextBatchHandler = handler != null ? handler : (_, _) -> false;
    }

    /** Wires the prompt's safe repeated-Backspace path. */
    public void wireBackspaceBatch(BiPredicate<Interactable, Integer> handler) {
        backspaceBatchHandler = handler != null ? handler : (_, _) -> false;
    }

    /**
     * Wires the active inline overlay ahead of Lanterna's window/component
     * dispatch. The window listener performs the same routing as a fallback;
     * this direct seam only removes that traversal from modal keyboard input.
     */
    public void wireInlineOverlayInput(Predicate<KeyStroke> handler) {
        inlineOverlayInputHandler = handler != null ? handler : _ -> false;
    }

    @Override
    public synchronized boolean processInput() throws IOException {
        inputBatchStart.run();
        try {
            return super.processInput();
        } finally {
            inputBatchEnd.run();
        }
    }

    /**
     * Intercept mouse events before window dispatch, so clickable footer pills
     * can run before the fallback selection state machine and selection
     * works even while a modal {@code DialogWindow} is the active window.
     * Consuming them also keeps clicks from leaking into focused components
     * as raw input.
     */
    @Override
    public synchronized boolean handleInput(KeyStroke key) {
        if (key instanceof PlainTextKeyStroke batch) {
            if (plainTextBatchHandler.test(getFocusedInteractable(), batch.text())) return true;
            // Modal/overlay/stateful editors decline the fast path. Replay the
            // original characters through the complete window routing stack.
            for (int index = 0; index < batch.text().length(); index++) {
                KeyStroke character = new KeyStroke(batch.text().charAt(index), false, false);
                boolean handled = handleInput(character);
                if (!handled) fireUnhandledKeyStroke(character);
            }
            return true;
        }
        if (key instanceof BackspaceRunKeyStroke batch) {
            if (backspaceBatchHandler.test(getFocusedInteractable(), batch.count())) return true;
            for (int index = 0; index < batch.count(); index++) {
                KeyStroke backspace = new KeyStroke(KeyType.BACKSPACE);
                boolean handled = handleInput(backspace);
                if (!handled) fireUnhandledKeyStroke(backspace);
            }
            return true;
        }
        // Selection mouse handling intentionally stays on its existing path.
        // Keyboard input for an active inline modal, however, can skip the
        // MultiWindowTextGUI -> Window -> component traversal because the
        // WindowInputRouter gives the same overlay first claim unconditionally.
        if (!(key instanceof MouseAction) && inlineOverlayInputHandler.test(key)) return true;
        Consumer<MouseAction> handler = selectionMouseHandler;
        if (handler != null && key instanceof MouseAction ma) {
            if (ma.getActionType() == MouseActionType.MOVE) {
                Predicate<MouseAction> actionHandler = actionMouseHandler;
                return actionHandler != null && actionHandler.test(ma)
                    || super.handleInput(key);
            }
            switch (ma.getActionType()) {
                case CLICK_DOWN, DRAG, CLICK_RELEASE -> {
                    Predicate<MouseAction> actionHandler = actionMouseHandler;
                    if (actionHandler != null && actionHandler.test(ma)) return true;
                    handler.accept(ma);
                    return true;
                }
                default -> { }
            }
        }
        return super.handleInput(key);
    }

    /**
     * Paint the selection highlight AFTER the full GUI (all windows) has been
     * drawn into the back buffer and before {@code screen.refresh} sends it
     * to the terminal. Reverse-video per selected cell — the native terminal
     * selection style, same as MessagePanel's former component-local overlay.
     */
    @Override
    protected synchronized void drawGUI(TextGUIGraphics graphics) {
        drawWindowsWithoutCoveredBackground(graphics);
        Selection sel = this.selection;
        if (sel == null || !sel.hasSelection()) return;
        Selection.Bounds b = sel.getSelectionBounds();
        if (b == null) return;
        Screen screen = getScreen();
        TerminalSize size = screen.getTerminalSize();
        int firstRow = Math.max(0, b.start().row());
        int lastRow  = Math.min(b.end().row(), size.getRows() - 1);
        for (int row = firstRow; row <= lastRow; row++) {
            for (int col = 0; col < size.getColumns(); col++) {
                TextCharacter tc = screen.getBackCharacter(col, row);
                if (tc == null) continue;
                if (tc.isDoubleWidth()) {
                    // A wide glyph owns this cell plus the spacer cell to its
                    // right. Writing that spacer independently makes Lanterna
                    // clear the leading glyph, so treat both cells as one
                    // render unit and only rewrite the glyph cell. Selecting
                    // either half highlights the complete visible character.
                    boolean selected = sel.isCellSelected(col, row)
                        || col + 1 < size.getColumns()
                            && sel.isCellSelected(col + 1, row);
                    if (selected) {
                        screen.setCharacter(col, row, tc.withModifier(SGR.REVERSE));
                    }
                    col++;
                    continue;
                }
                if (sel.isCellSelected(col, row)) {
                    screen.setCharacter(col, row, tc.withModifier(SGR.REVERSE));
                }
            }
        }
    }

    /**
     * The application always owns a full-screen main window. Lanterna's stock
     * compositor nevertheless clears the equally-sized background pane before
     * drawing that window, touching every terminal cell twice per keypress.
     * Retain the same per-window images and post-rendering semantics while
     * omitting only that fully covered background pass.
     */
    private void drawWindowsWithoutCoveredBackground(TextGUIGraphics graphics) {
        List<Window> windows = new ArrayList<>(getWindows());
        getWindowManager().prepareWindows(this, windows, graphics.getSize());
        boolean backgroundCovered = false;
        for (Window window : windows) {
            if (window.isVisible()
                    && TerminalPosition.of(0, 0).equals(window.getPosition())
                    && window.getDecoratedSize().getColumns() >= graphics.getSize().getColumns()
                    && window.getDecoratedSize().getRows() >= graphics.getSize().getRows()) {
                backgroundCovered = true;
                break;
            }
        }
        if (backgroundCovered) {
            // Clear the pane's invalid flag without touching a covered screen
            // cell; otherwise MultiWindowTextGUI would remain permanently dirty.
            getBackgroundPane().draw(graphics.newTextGraphics(
                TerminalPosition.of(0, 0), TerminalSize.of(0, 0)));
        } else {
            getBackgroundPane().draw(graphics);
        }
        for (Window window : windows) {
            if (!window.isVisible()) continue;
            TextImage image = windowBuffers.get(window);
            boolean freshBuffer = image == null
                || !image.getSize().equals(window.getDecoratedSize());
            if (freshBuffer) {
                image = new BasicTextImage(window.getDecoratedSize());
                windowBuffers.put(window, image);
            }

            boolean coveredFullScreenWindow = !freshBuffer
                && window.getHints().contains(Window.Hint.FULL_SCREEN)
                && window.getHints().contains(Window.Hint.NO_DECORATIONS);
            TextGUIGraphics frame = coveredFullScreenWindow
                ? TextGUIGraphicsBridge.wrapSkippingInitialFill(this, image.newTextGraphics())
                : TextGUIGraphicsBridge.wrap(this, image.newTextGraphics());
            TextGUIGraphics content = frame;
            TerminalPosition offset = TerminalPosition.of(0, 0);
            if (!window.getHints().contains(Window.Hint.NO_DECORATIONS)) {
                WindowDecorationRenderer decoration =
                    getWindowManager().getWindowDecorationRenderer(window);
                content = decoration.draw(this, frame, window);
                offset = decoration.getOffset(window);
            }
            try {
                window.draw(content);
            } catch (RuntimeException renderFailure) {
                // A single component's content must not take the GUI thread down
                // with it: Lanterna rejects control characters at paint time, and
// the throw unwinds through updateScreen into the loop's fatal
                // handler. Keep this window on its previous buffer, keep the rest
                // of the frame, and keep input alive so the user can navigate out.
                reportWindowRenderFailure(window, renderFailure);
                continue;
            }
            window.setContentOffset(offset);
            if (frame != content) Borders.joinLinesWithFrame(frame);
            compositeChangedCells(window.getPosition(), image);

            if (!window.getHints().contains(Window.Hint.NO_POST_RENDERING)) {
                WindowPostRenderer postRenderer = window.getPostRenderer();
                if (postRenderer == null) postRenderer = getWindowPostRenderer();
                if (postRenderer == null) postRenderer = getTheme().getWindowPostRenderer();
                if (postRenderer != null) postRenderer.postRender(graphics, this, window);
            }
        }
        windowBuffers.keySet().retainAll(windows);
    }

    /**
     * Copies only cells whose retained window image differs from the screen
     * back buffer. Lanterna's stock drawImage writes the complete 120x40 main
     * window on every keystroke even when the prompt changed by one run; the
     * terminal refresh performs the same comparison again immediately after.
     * Keeping the retained image preserves modal-window uncover semantics while
     * avoiding thousands of redundant back-buffer writes per input frame.
     */
    private void compositeChangedCells(TerminalPosition position, TextImage image) {
        Screen screen = getScreen();
        TerminalSize screenSize = screen.getTerminalSize();
        TerminalSize imageSize = image.getSize();
        int imageStartX = Math.max(0, -position.getColumn());
        int imageStartY = Math.max(0, -position.getRow());
        int screenStartX = Math.max(0, position.getColumn());
        int screenStartY = Math.max(0, position.getRow());
        int columns = Math.min(imageSize.getColumns() - imageStartX,
            screenSize.getColumns() - screenStartX);
        int rows = Math.min(imageSize.getRows() - imageStartY,
            screenSize.getRows() - screenStartY);
        if (columns <= 0 || rows <= 0) return;
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                TextCharacter desired = image.getCharacterAt(
                    imageStartX + column, imageStartY + row);
                int screenColumn = screenStartX + column;
                int screenRow = screenStartY + row;
                if (!desired.equals(screen.getBackCharacter(screenColumn, screenRow))) {
                    screen.setCharacter(screenColumn, screenRow, desired);
                }
                if (desired.isDoubleWidth()) column++;
            }
        }
    }

    /**
     * Log a non-fatal window render failure once per (window, failure) pair.
     * A component whose content stays invalid throws on every frame, so an
     * unfiltered log would grow without bound behind the TUI.
     */
    @Explanation("""
        Lanterna can throw while painting invalid component text. Containing and
        deduplicating the failure keeps the GUI loop alive without flooding logs.""")
    private void reportWindowRenderFailure(Window window, RuntimeException failure) {
        String signature = window.getClass().getName() + '|' + failure.getClass().getName()
            + '|' + failure.getMessage();
        if (!reportedRenderFailures.add(signature)) return;
        log.error("Skipped rendering window {} after a paint failure",
            window.getClass().getSimpleName(), failure);
    }

    /**
     * Extract one display row's text from the screen back buffer.
     */
    public static String rowText(Screen screen, int row) {
        TerminalSize size = screen.getTerminalSize();
        if (row < 0 || row >= size.getRows()) return "";
        StringBuilder sb = new StringBuilder(size.getColumns());
        for (int col = 0; col < size.getColumns(); col++) {
            TextCharacter tc = screen.getBackCharacter(col, row);
            if (tc == null) { sb.append(' '); continue; }
            sb.append(tc.getCharacterString());
            if (tc.isDoubleWidth()) col++;   // skip the padding cell
        }
        return sb.toString().stripTrailing();
    }
}

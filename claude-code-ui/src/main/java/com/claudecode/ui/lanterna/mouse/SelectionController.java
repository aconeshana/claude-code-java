package com.claudecode.ui.lanterna.mouse;

import com.claudecode.ui.lanterna.transcript.MessagePanel;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.ui.lanterna.components.OSC52Helper;
import com.claudecode.ui.lanterna.transcript.Selection;
import com.claudecode.ui.lanterna.transcript.SelectionAwareTextGUI;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import com.googlecode.lanterna.input.MouseAction;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Encapsulates virtual-text selection UX: mouse click/drag/release + double/ triple-click,
 * Shift+click extend, drag-to-autoscroll, and OSC 52 copy-to-clipboard on mouse-up ({@code
 * useCopyOnSelect}).
 */
public final class SelectionController {

    private static final Logger log = LoggerFactory.getLogger(SelectionController.class);


    private static final int MULTI_CLICK_TIMEOUT_MS = 500;

    private static final int MULTI_CLICK_DISTANCE   = 1;

    private static final ScheduledExecutorService AUTOSCROLL_EXEC =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "selection-autoscroll");
            t.setDaemon(true);
            return t;
        });

    private final WindowBasedTextGUI gui;
    private final MessagePanel       messagePanel;
    private final Selection          selection;
    private volatile boolean         copyOnSelect;
    private volatile BiPredicate<Integer, Integer> bareClickHandler = (_, _) -> false;

    // ── Multi-click state ─────────────────────────────────────────────────
    private long lastClickMs   = 0;
    private int  lastClickCol  = -1;
    private int  lastClickRow  = -1;
    private int  clickCount    = 0;

    // ── Autoscroll state ──────────────────────────────────────────────────
    private ScheduledFuture<?> autoscrollTask;
    private int                autoscrollDir;

    public SelectionController(WindowBasedTextGUI gui,
                               MessagePanel messagePanel,
                               boolean copyOnSelect) {
        this.gui          = gui;
        this.messagePanel = messagePanel;
        this.selection    = new Selection();
        this.copyOnSelect = copyOnSelect;
        // The panel tracks the selection so viewport scrolls shift it along
        // with the content (highlight walks with the text) — render + copy
        // are handled at the screen level, not by the panel.
        messagePanel.setSelection(this.selection);
    }

    /** Read the underlying {@link Selection} — for keyboard handlers to drive. */
    public Selection getSelection() { return selection; }

/**
     * Applies the immutable startup setting before the first frame is.
     */
    public void setCopyOnSelect(boolean enabled) { copyOnSelect = enabled; }

    /**
     * Installs the screen-coordinate click dispatcher used after a left-button
     * press/release that did not form a selection. Returning {@code true}
     * consumes the click before the transcript's expandable-row fallback.
     */
    public void setBareClickHandler(BiPredicate<Integer, Integer> handler) {
        bareClickHandler = handler != null ? handler : (_, _) -> false;
    }

    /**
     * Text of one screen row from the back buffer — the data source for
     * word/line select, Shift+arrow row math and clipboard copy. Public so
     * {@code WindowInputRouter}'s keyboard selection handlers share it.
     */
    public String screenRowText(int row) {
        return SelectionAwareTextGUI.rowText(gui.getScreen(), row);
    }

    /**
     * Route a mouse event to the selection state machine.
     */
    public void handleMouse(MouseAction ma) {
        TerminalSize size = gui.getScreen().getTerminalSize();
        int col = clamp(ma.getPosition().getColumn(), size.getColumns() - 1);
        int row = clamp(ma.getPosition().getRow(),    size.getRows()    - 1);

        switch (ma.getActionType()) {
            case CLICK_DOWN    -> onClickDown(ma, col, row);
            case DRAG          -> onDrag(col, row);
            case CLICK_RELEASE -> onRelease(col, row);
            default -> {}
        }
    }

    private void onClickDown(MouseAction ma, int col, int row) {

        long now = System.currentTimeMillis();
        int dCol = Math.abs(col - lastClickCol);
        int dRow = Math.abs(row - lastClickRow);
        boolean nearLast = (dCol <= MULTI_CLICK_DISTANCE && dRow <= MULTI_CLICK_DISTANCE);
        if (now - lastClickMs <= MULTI_CLICK_TIMEOUT_MS && nearLast) {
            clickCount = Math.min(clickCount + 1, 3);
        } else {
            clickCount = 1;
        }
        lastClickMs = now;
        lastClickCol = col;
        lastClickRow = row;

// Shift+Click: extend existing selection focus.
        if (ma.isShiftDown() && selection.hasSelection()) {
            selection.extendFocus(col, row);
            messagePanel.invalidate();
            autoCopyIfEnabled();
            return;
        }

        if (clickCount == 2) {
            selection.selectWordAt(screenRowText(row), col, row);
            messagePanel.invalidate();
            autoCopyIfEnabled();
            return;
        }
        if (clickCount == 3) {
            selection.selectLineAt(screenRowText(row), row);
            messagePanel.invalidate();
            autoCopyIfEnabled();
            return;
        }
        // Fresh press anywhere: new anchor (clears any previous selection —
        // a bare click leaves focus null, so nothing highlights).
        selection.startSelection(col, row);
        messagePanel.invalidate();
    }

    private void onDrag(int col, int row) {
        if (!selection.isDragging()) return;
        selection.updateSelection(col, row);
        messagePanel.invalidate();

        TerminalPosition origin = messagePanel.getGlobalPosition();
        TerminalSize     size   = messagePanel.getSize();
        Selection.Point  anchor = selection.getAnchor();
        if (origin == null || size == null || anchor == null) {
            stopAutoscroll();
            return;
        }
        int top    = origin.getRow();
        int bottom = origin.getRow() + size.getRows() - 1;
        boolean anchorInViewport = anchor.row() >= top && anchor.row() <= bottom;
        if      (anchorInViewport && row > bottom) startAutoscroll(+1);
        else if (anchorInViewport && row <= top && anchor.row() > top) startAutoscroll(-1);
        else                                       stopAutoscroll();
    }

    private void onRelease(int col, int row) {
        stopAutoscroll();
        if (selection.isDragging()) {
            boolean bareClick = !selection.hasSelection() && selection.getAnchor() != null;
            selection.finishSelection();
            messagePanel.invalidate();
            if (bareClick && !bareClickHandler.test(col, row)) {
                toggleExpandableMessageAt(col, row);
            }
            // useCopyOnSelect: mouse-up with active selection auto-copies.
            autoCopyIfEnabled();
        }
    }

    private void toggleExpandableMessageAt(int col, int row) {
        TerminalPosition origin = messagePanel.getGlobalPosition();
        TerminalSize size = messagePanel.getSize();
        if (origin == null || size == null) return;
        int localCol = col - origin.getColumn();
        int localRow = row - origin.getRow();
        if (localCol < 0 || localRow < 0
                || localCol >= size.getColumns() || localRow >= size.getRows()) {
            return;
        }
        String rowText = screenRowText(row);
        if (localCol >= FormatUtils.displayWidth(rowText)) return;
        messagePanel.toggleExpandableLogicalMessageAt(localCol, localRow);
    }

    /**
     * Copy the current selection's text to the system clipboard via OSC 52.
     */
    public void copyToClipboard() {
        if (!selection.hasSelection()) return;
        String text = selection.getSelectedText(this::screenRowText);
        if (text.isEmpty()) return;
        OSC52Helper.copyToClipboard(text);
    }

    /** Auto-copy on mouse-up / multi-click / Shift+Arrow; no-op if {@code copyOnSelect} is false. */
    public void autoCopyIfEnabled() {
        if (copyOnSelect) copyToClipboard();
    }

    /**
     * Start (or update) the autoscroll timer for drag-past-the-edge.
     */
    private void startAutoscroll(int dir) {
        if (autoscrollTask != null && !autoscrollTask.isCancelled()
                && autoscrollDir == dir) {
            return;
        }
        stopAutoscroll();
        autoscrollDir = dir;
        autoscrollTask = AUTOSCROLL_EXEC.scheduleAtFixedRate(() -> {
            try {
                gui.getGUIThread().invokeLater(() -> {
                    if (!selection.isDragging()) {
                        stopAutoscroll();
                        return;
                    }
                    if (autoscrollDir < 0) messagePanel.scrollSelectionDragUp(1);
                    else                   messagePanel.scrollSelectionDragDown(1);
                    messagePanel.invalidate();
                });
            } catch (RuntimeException schedulingFailure) {

                stopAutoscroll();
                log.debug("Stopping selection autoscroll after GUI scheduling failure",
                    schedulingFailure);
            }
        }, 0, 50, TimeUnit.MILLISECONDS);
    }

    private void stopAutoscroll() {
        if (autoscrollTask != null) {
            autoscrollTask.cancel(false);
            autoscrollTask = null;
        }
        autoscrollDir = 0;
    }

    private static int clamp(int v, int hi) {
        return Math.max(0, Math.min(hi, v));
    }
}

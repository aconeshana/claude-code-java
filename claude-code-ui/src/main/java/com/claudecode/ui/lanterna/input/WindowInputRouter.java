package com.claudecode.ui.lanterna.input;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.gui2.WindowListener;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.claudecode.keybindings.KeybindingResolver;
import com.claudecode.keybindings.KeystrokeParser;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.ui.lanterna.mouse.SelectionController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import com.claudecode.ui.lanterna.overlay.OverlayHost;
import com.claudecode.ui.lanterna.transcript.MessagePanel;
import com.claudecode.ui.lanterna.transcript.Selection;

/**
 * Window-level input router — scroll / mouse / selection / Ctrl+C keys handled <em>before</em> the
 * focused {@link InputPanel} sees them, so they work in every input mode (normal, vim,
 * message-actions).
 */
public final class WindowInputRouter implements WindowListener {

    private static final Logger log = LoggerFactory.getLogger(WindowInputRouter.class);

    private final OverlayHost overlayHost;
    private final MessagePanel messagePanel;
    private final Selection selection;
    private final SelectionController selectionController;
    /** Fired for Ctrl+C (no shift) — the screen owns the priority chain
     *  (bash-interrupt → API-interrupt → clear-input → double-press-exit),
     *  shared with the SIGINT signal handler. */
    private final Runnable onCtrlC;
    /** Opt-in keybinding resolver (gate on). Null when customization is disabled. */
    private final UserKeybindingsStore keybindingsStore;
    private final Runnable onResize;

/**
     * Wheel scroll accel — one per window, tracks last-event timestamp + multiplier across mouse
     * events.
     */
    private final MouseScrollHandler.WheelAccelState wheelAccel =
        MouseScrollHandler.newState();

    public WindowInputRouter(OverlayHost overlayHost, MessagePanel messagePanel,
                      Selection selection, SelectionController selectionController,
                      Runnable onCtrlC, UserKeybindingsStore keybindingsStore,
                      Runnable onResize) {
        this.overlayHost = overlayHost;
        this.messagePanel = messagePanel;
        this.selection = selection;
        this.selectionController = selectionController;
        this.onCtrlC = onCtrlC;
        this.keybindingsStore = keybindingsStore;
        this.onResize = onResize != null ? onResize : () -> { };
    }

    @Override public void onResized(Window w, TerminalSize o, TerminalSize n) { onResize.run(); }
    @Override public void onMoved(Window w, TerminalPosition o, TerminalPosition n) {}
    @Override public void onUnhandledInput(Window w, KeyStroke key, AtomicBoolean handled) {}

    @Override
    public void onInput(Window w, KeyStroke key, AtomicBoolean deliver) {
        // Inline overlays (effort / export / hooks / mcp) get first claim on input
        // while active — polled ahead of the global key switch so, e.g., ESCAPE closes
        // the dialog instead of leaking into "clear selection". They are mutually
        // exclusive, so registration order is only a defensive tie-break; an active
        // overlay that does not consume the key falls through to the switch. See
        // InlineOverlay.
        if (overlayHost.route(key, deliver)) return;
        // ── Resolver-driven scroll-key dispatch (opt-in, gate on) ──
        // When enabled, Scroll-context bindings (PageUp/PageDown/Ctrl+Home/
        // Ctrl+End/Ctrl+Shift+C + mouse-wheel-derived keys) are routed through
        // the resolver (defaults + user overrides) instead of the hardcoded
        // switch below. Mouse wheel and Ctrl+C (Global app:interrupt, not a
        // Scroll binding) return null from the adapter and fall through. An
        // unbound key (user set it to null) is consumed so the default never
        // fires. Everything else (Shift+Arrow selection, Esc-clear, etc.)
        // falls through unchanged.
        if (keybindingsStore != null && keybindingsStore.isEnabled()) {
            KeystrokeParser.Keystroke ks = LanternaKeyAdapter.toKeystroke(key);
            if (ks != null) {
                KeybindingResolver.ResolveResult r =
                    keybindingsStore.currentResolver().resolve(List.of("Scroll"), ks);
                if (r instanceof KeybindingResolver.ResolveResult.Match(String action)) {
                    if (dispatchScroll(action)) deliver.set(false);
                    return; // consume the matched key either way
                }
                if (r instanceof KeybindingResolver.ResolveResult.Unbound) {
                    deliver.set(false); // user explicitly disabled this binding
                    return;
                }
                // None → fall through to the existing switch.
            }
        }
        switch (key.getKeyType()) {
            case PAGE_UP    -> { messagePanel.pageUp();       deliver.set(false); }
            case PAGE_DOWN  -> { messagePanel.pageDown();     deliver.set(false); }
            case HOME       -> {
                if (key.isShiftDown() && selection.hasSelection()) {
                    selection.moveFocus(Selection.FocusDir.LINE_START,
                        selectionController::screenRowText);
                    messagePanel.invalidate();
                    selectionController.autoCopyIfEnabled();
                    deliver.set(false);
                } else if (key.isCtrlDown()) {
                    messagePanel.scrollToTop();
                    deliver.set(false);
                }
            }
            case END        -> {
                if (key.isShiftDown() && selection.hasSelection()) {
                    selection.moveFocus(Selection.FocusDir.LINE_END,
                        selectionController::screenRowText);
                    messagePanel.invalidate();
                    selectionController.autoCopyIfEnabled();
                    deliver.set(false);
                } else if (key.isCtrlDown()) {
                    messagePanel.scrollToBottom();
                    deliver.set(false);
                }
            }
            case MOUSE_EVENT -> {
                // Wheel → transcript scroll (with accel). Selection events
                // (CLICK_DOWN / DRAG / CLICK_RELEASE) are intercepted by
                // SelectionAwareTextGUI and never arrive here. Whatever else
                // does (MOVE etc.) is swallowed so it doesn't reach the
                // focused InputPanel as raw control bytes.
                int delta = MouseScrollHandler.getScrollDelta(key, wheelAccel);
                if (delta != 0) {
                    messagePanel.scrollUp(delta);
                }
                deliver.set(false);
            }
            case ESCAPE     -> {
                // Esc clears an active selection BEFORE InputPanel's
                // own Esc handler runs (which might abort a query
                // when isLoading). Without this, Esc after copy never
                // releases the highlight.
                if (selection.hasSelection()) {
                    selection.clearSelection();
                    messagePanel.invalidate();
                    deliver.set(false);
                }
            }
            case CHARACTER -> {
                Character ch = key.getCharacter();
                if (ch != null && (ch == 'C' || ch == 'c')
                        && key.isCtrlDown() && key.isShiftDown()
                        && selection.hasSelection()) {
                    selectionController.copyToClipboard();
                    deliver.set(false);
                }
                // Ctrl+C (no shift) — Lanterna's TRAP behaviour delivers
                // this here instead of System.exit(1)ing. Route to the
                // same priority chain as the SIGINT signal handler:
                //   1. in-flight bash-mode → interrupt
                //   2. in-flight API turn → interrupt
                //   3. non-empty input → clear (like Esc)
                //   4. empty input → double-press-to-exit hint
                else if (ch != null && (ch == 'c' || ch == '\003')
                        && key.isCtrlDown() && !key.isShiftDown()
                        && !key.isAltDown()) {
                    onCtrlC.run();
                    deliver.set(false);
                }
            }
            case ARROW_LEFT, ARROW_RIGHT, ARROW_UP, ARROW_DOWN -> {
// Shift+Arrow extends the selection focus by one cell/row.

                // when a selection is already active — otherwise
                // arrows fall through to InputPanel (history nav,
                // cursor move, vim hjkl, etc).
                if (key.isShiftDown() && selection.hasSelection()) {
// Shift+Alt+Left/Right = word-jump extension.
                    boolean wordJump = key.isAltDown()
                        && (key.getKeyType() == KeyType.ARROW_LEFT
                         || key.getKeyType() == KeyType.ARROW_RIGHT);
                    Selection.FocusDir dir = switch (key.getKeyType()) {
                        case ARROW_LEFT  -> wordJump
                            ? Selection.FocusDir.WORD_LEFT
                            : Selection.FocusDir.LEFT;
                        case ARROW_RIGHT -> wordJump
                            ? Selection.FocusDir.WORD_RIGHT
                            : Selection.FocusDir.RIGHT;
                        case ARROW_UP    -> Selection.FocusDir.UP;
                        case ARROW_DOWN  -> Selection.FocusDir.DOWN;
                        default          -> null;
                    };
                    if (dir != null) {
                        selection.moveFocus(dir, selectionController::screenRowText);
                        messagePanel.invalidate();
                        selectionController.autoCopyIfEnabled();
                        deliver.set(false);
                    }
                }
            }
            default         -> {}
        }
    }

    /**
     * Fire a resolved Scroll-context action. Returns {@code true} when the key
     * was consumed. Every known action routes to the same call the hardcoded
     * switch would have made, so default behaviour is unchanged; unsupported
     * actions are consumed (no-op) so they don't leak into the focused panel.
     */
    private boolean dispatchScroll(String action) {
        switch (action) {
            case "scroll:pageUp"   -> { messagePanel.pageUp(); return true; }
            case "scroll:pageDown" -> { messagePanel.pageDown(); return true; }
            case "scroll:lineUp"   -> { messagePanel.scrollUp(1); return true; }
            case "scroll:lineDown" -> { messagePanel.scrollDown(1); return true; }
            case "scroll:top"      -> { messagePanel.scrollToTop(); return true; }
            case "scroll:bottom"   -> { messagePanel.scrollToBottom(); return true; }
            case "selection:copy"  -> {
                if (selection.hasSelection()) selectionController.copyToClipboard();
                return true;
            }
            default -> {
                log.debug("[keybindings] unsupported scroll action: {}", action);
                return true;
            }
        }
    }
}

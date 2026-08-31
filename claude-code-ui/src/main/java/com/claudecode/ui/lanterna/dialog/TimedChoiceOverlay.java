package com.claudecode.ui.lanterna.dialog;

import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.ui.lanterna.input.ContextKeybindingDispatcher;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.input.KeyStroke;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Shared base for the two inline, non-blocking "timed single-choice" overlays — {@link
 * PluginHintMenu} and {@link LspRecommendationDialog}.
 */
abstract class TimedChoiceOverlay<R> extends Panel implements InlineOverlay {

    /** Whether this overlay currently owns keyboard input. Read by subclass {@code Body}. */
    protected volatile boolean active = false;
    /** Currently focused option index. Read by subclass {@code Body}. */
    protected int focus = 0;

    private BiConsumer<R, Boolean> consumer;
    private Runnable onClose;
    private Timer timeoutTimer;
    private final String timerName;
    private long timeoutMs;
    private final ContextKeybindingDispatcher keybindings =
        new ContextKeybindingDispatcher();

    protected TimedChoiceOverlay(long defaultTimeoutMs, String timerName) {
        this.timeoutMs = defaultTimeoutMs;
        this.timerName = timerName;
    }

    public final void setKeybindingsStore(UserKeybindingsStore store) {
        keybindings.setStore(store);
    }

    /** Number of selectable options, in focus order. */
    protected abstract int optionCount();

    /** Response for the option at {@code index} (focus order). */
    protected abstract R responseAt(int index);

    /** Response used for Esc and for the auto-dismiss timeout. */
    protected abstract R notNowResponse();

    /** Clears the subclass's per-show payload (e.g. the hint/recommendation). */
    protected abstract void clearPayload();

    /** Test seam — shorten the auto-dismiss timer without a real 30s wait. */
    void setTimeoutMs(long ms) {
        this.timeoutMs = ms;
    }

    /**
     * Activates the overlay and starts the inactivity timer. Subclasses call this
     * from their own {@code show(payload, ...)} after storing the payload. Runs on
     * the GUI thread.
     */
    protected final void activate(BiConsumer<R, Boolean> consumer, Runnable onClose,
                                  MultiWindowTextGUI gui) {
        this.consumer = consumer;
        this.onClose = onClose;
        this.focus = 0;
        this.active = true;
        if (timeoutTimer != null) {
            timeoutTimer.cancel();
        }
        timeoutTimer = new Timer(timerName, true);
        final MultiWindowTextGUI g = gui;
        timeoutTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (g != null) {
                    g.getGUIThread().invokeLater(() -> resolve(notNowResponse(), true));
                } else {
                    resolve(notNowResponse(), true);
                }
            }
        }, timeoutMs);
        invalidate();
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public void handleKey(KeyStroke key, AtomicBoolean deliver) {
        if (!active) {
            return;
        }
        ContextKeybindingDispatcher.Result resolved = keybindings.resolve("Select", key);
        if (resolved instanceof ContextKeybindingDispatcher.Result.Consumed) {
            deliver.set(false);
            return;
        }
        if (resolved instanceof ContextKeybindingDispatcher.Result.Action action) {
            boolean handled = switch (action.value()) {
                case "select:previous" -> {
                    focus = InlineOverlay.cycleIndex(focus, -1, optionCount());
                    yield true;
                }
                case "select:next" -> {
                    focus = InlineOverlay.cycleIndex(focus, 1, optionCount());
                    yield true;
                }
                case "select:accept" -> { resolve(responseAt(focus), false); yield true; }
                case "select:cancel" -> { resolve(notNowResponse(), false); yield true; }
                default -> false;
            };
            if (handled) {
                deliver.set(false);
                invalidate();
                return;
            }
        }
        switch (key.getKeyType()) {
            case ESCAPE -> resolve(notNowResponse(), false);
            case ARROW_UP -> focus = InlineOverlay.cycleIndex(focus, -1, optionCount());
            case ARROW_DOWN -> focus = InlineOverlay.cycleIndex(focus, +1, optionCount());
            case ENTER -> resolve(responseAt(focus), false);
            case CHARACTER -> {
                Character ch = key.getCharacter();
                if (ch != null && ch >= '1' && ch <= '0' + optionCount()) {
                    resolve(responseAt(ch - '1'), false);
                }
            }
            default -> { /* swallow any other key while active */ }
        }
        // Consume every key while active (including PASTE) so nothing leaks to the
        // main input panel behind this focus-less overlay.
        deliver.set(false);
        invalidate();
    }

    /**
     * Resolves the overlay exactly once. Tears down state (cancels the timer,
     * nulls the callbacks and payload) <em>before</em> invoking callbacks, so a
     * re-entrant call from within a callback is a no-op.
     */
    protected final void resolve(R response, boolean timedOut) {
        if (!active) {
            return;
        }
        active = false;
        if (timeoutTimer != null) {
            timeoutTimer.cancel();
            timeoutTimer = null;
        }
        BiConsumer<R, Boolean> c = consumer;
        consumer = null;
        Runnable closer = onClose;
        onClose = null;
        clearPayload();
        invalidate();
        if (c != null) {
            c.accept(response, timedOut);
        }
        if (closer != null) {
            closer.run();
        }
    }

    @Override
    public synchronized TerminalSize calculatePreferredSize() {
        if (!active) {
            return TerminalSize.of(0, 0);
        }
        return super.calculatePreferredSize();
    }
}

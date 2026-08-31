package com.claudecode.ui.lanterna.components;

import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * A spinner glyph followed by a loading message, with an optional dim subtitle below it — the
 * standalone "async operation in flight" indicator used by dialogs and pickers, as opposed to the
 * REPL's turn spinner.
 */
public final class LoadingStateLabel extends Panel {

    private static final List<String> SPINNER_FRAMES = SpinnerFrames.defaultAnimationFrames();


    private static final long FRAME_MS = 120;

    private static final ScheduledExecutorService SCHEDULER =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "loading-state-spinner");
            t.setDaemon(true);
            return t;
        });

    private final Label messageLabel = new Label("");
    private final Label subtitleLabel = new Label("");
    private final AtomicInteger frame = new AtomicInteger(0);

    private String message = "";
    private ScheduledFuture<?> animation;

    public LoadingStateLabel() {
        super(new LinearLayout(Direction.VERTICAL).setSpacing(0));
        messageLabel.setForegroundColor(LanternaTheme.inputText());
        subtitleLabel.setForegroundColor(LanternaTheme.ghostText());
        subtitleLabel.setVisible(false);
        addComponent(messageLabel);
        addComponent(subtitleLabel);
        repaintMessage();
    }

    /** Sets the text shown next to the spinner. */
    public LoadingStateLabel setMessage(String text) {
        this.message = StringUtils.defaultString(text);
        repaintMessage();
        return this;
    }


    public LoadingStateLabel setBold(boolean value) {
        if (value) messageLabel.addStyle(SGR.BOLD);
        else messageLabel.removeStyle(SGR.BOLD);
        return this;
    }


    public LoadingStateLabel setDimMessage(boolean value) {
        messageLabel.setForegroundColor(value ? LanternaTheme.ghostText() : LanternaTheme.inputText());
        return this;
    }


    public LoadingStateLabel setSubtitle(String text) {
        boolean present = StringUtils.isNotEmpty(text);
        subtitleLabel.setText(present ? text : "");
        subtitleLabel.setVisible(present);
        return this;
    }

    /**
     * Starts advancing the glyph. {@code guiInvoker} hops each frame onto the
     * GUI thread — the caller owns that bridge because a dialog and the REPL
     * reach their {@code TextGUI} differently.
     */
    public synchronized void start(Consumer<Runnable> guiInvoker) {
        if (animation != null) return;
        frame.set(0);
        animation = SCHEDULER.scheduleAtFixedRate(
            () -> {
                frame.incrementAndGet();
                if (guiInvoker != null) guiInvoker.accept(this::repaintMessage);
                else repaintMessage();
            },
            FRAME_MS, FRAME_MS, TimeUnit.MILLISECONDS);
    }

    /** Stops the animation. Idempotent — safe to call from a failure path. */
    public synchronized void stop() {
        if (animation == null) return;
        animation.cancel(false);
        animation = null;
    }

    /** True while the glyph is animating — lets tests assert the spinner was stopped. */
    public synchronized boolean isRunning() {
        return animation != null;
    }

    /** The current row text, glyph included — the render assertion hook for tests. */
    String rowText() {
        return messageLabel.getText();
    }

    private void repaintMessage() {
        messageLabel.setText(glyph() + "  " + message);
    }

    private String glyph() {
        return SpinnerFrames.REDUCED_MOTION
            ? SpinnerFrames.REDUCED_MOTION_DOT
            : SpinnerFrames.glyphAt(SPINNER_FRAMES, frame.get());
    }
}

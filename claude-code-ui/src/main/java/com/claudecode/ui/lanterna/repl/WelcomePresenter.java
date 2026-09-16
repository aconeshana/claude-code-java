package com.claudecode.ui.lanterna.repl;

import com.claudecode.ui.lanterna.components.LogoPanel;
import com.claudecode.ui.lanterna.components.WelcomeBlockHolder;
import com.claudecode.ui.lanterna.transcript.MessagePanel;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Owns the welcome block at the top of a fresh conversation: the initial render, the in-place
 * model-line repaint after {@code /model}, and the web quick-entry row that appears once the
 * gateway is up. The block's source-line range is shared with {@code PokemonFeature} through
 * {@link WelcomeBlockHolder}.
 *
 * <p>{@link #renderFresh()} is a GUI-thread call (it runs before the first layout pass so the
 * message panel reports the right height immediately); the two updaters may be called from any
 * thread and hop to the GUI thread.
 *
 * <p>TS coverage (paths relative to the claude-code repo root):
 * <ul>
 *   <li>{@code components/Logo.tsx} / {@code components/WelcomeBlock.tsx} — startup banner with
 *       the live model line.</li>
 * </ul>
 */
final class WelcomePresenter {

    private final LogoPanel panel;
    private final WelcomeBlockHolder block;
    private final MessagePanel messagePanel;
    private final IntSupplier terminalColumns;
    private final Supplier<String> model;
    private final Consumer<Runnable> guiInvoker;

    WelcomePresenter(LogoPanel panel, WelcomeBlockHolder block, MessagePanel messagePanel,
                     IntSupplier terminalColumns, Supplier<String> model,
                     Consumer<Runnable> guiInvoker) {
        this.panel = Objects.requireNonNull(panel, "panel");
        this.block = Objects.requireNonNull(block, "block");
        this.messagePanel = Objects.requireNonNull(messagePanel, "messagePanel");
        this.terminalColumns = Objects.requireNonNull(terminalColumns, "terminalColumns");
        this.model = Objects.requireNonNull(model, "model");
        this.guiInvoker = Objects.requireNonNull(guiInvoker, "guiInvoker");
    }

    /** Renders the banner for a fresh (or cleared) conversation. GUI thread. */
    void renderFresh() {
        block.set(panel.show(messagePanel, terminalColumns.getAsInt(), model.get()));
    }

    /** Repaints the model line in place after the active model changed. */
    void repaintModelLine() {
        guiInvoker.accept(() -> {
            LogoPanel.WelcomeBlock current = block.get();
            if (current == null) return;
            panel.updateModelLine(messagePanel, current, terminalColumns.getAsInt(), model.get());
        });
    }

    /**
     * Shows, updates, or (with {@code null}) clears the web quick-entry row. A whole-block
     * re-render happens only when the row is newly appearing; otherwise the row is updated
     * in place.
     */
    void updateWebLine(String url) {
        guiInvoker.accept(() -> {
            LogoPanel.WelcomeBlock current = block.get();
            if (current == null) return;
            block.set(panel.updateWebLine(
                messagePanel, current, terminalColumns.getAsInt(), model.get(), url));
        });
    }
}

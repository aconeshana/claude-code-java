package com.claudecode.ui.lanterna.input;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.runtime.sessionhost.SessionCollaborationController;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.gui2.Label;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Permanent keyboard-focusable footer entry for the optional IM collaboration
 * channel ({@code Collaboration: Off | <channel>}), always rendered inline at
 * the end of the hint row.
 *
 * <p>Session Link can change the collaboration state from a virtual thread, so
 * the subscription projects the new value through the GUI scheduler supplied
 * at bind time before touching Lanterna.
 *
 * <ul>
 *   <li>Java-side extension: the released product's footer has no
 *       collaboration entry.</li>
 * </ul>
 */
@Explanation("Permanent per-session IM collaboration footer control")
final class CollaborationPill {

    private static final Logger log = LoggerFactory.getLogger(CollaborationPill.class);

    private final Label separator = new Label(" · ");
    private final Label label = new Label("Collaboration: Off");
    private volatile SessionCollaborationController controller;
    private AutoCloseable subscription;
    private boolean selected;

    CollaborationPill() {
        separator.setForegroundColor(LanternaTheme.welcomeDim());
        label.setForegroundColor(LanternaTheme.welcomeDim());
    }

    /** Always-visible " · " joining the pill to whatever precedes it in the hint row. */
    Label separatorLabel() { return separator; }

    /** The "Collaboration: ..." text, mounted inline as the hint row's final child. */
    Label textLabel() { return label; }

    String text() { return label.getText(); }

    boolean isSelected() { return selected; }

    void setSelected(boolean selected) { this.selected = selected; }

    void render() {
        SessionCollaborationController current = controller;
        String value = current == null ? "Off" : current.current().displayValue();
        TasksPill.setTextIfChanged(label, "Collaboration: " + value);
        if (selected) label.addStyle(SGR.REVERSE);
        else label.removeStyle(SGR.REVERSE);
    }

    /**
     * Binds the shared collaboration state. {@code onChange} runs through
     * {@code guiScheduler}; a stale notification from a superseded controller
     * is dropped.
     */
    void bind(SessionCollaborationController next, Consumer<Runnable> guiScheduler,
              Runnable onChange) {
        closeSubscription();
        this.controller = next;
        if (next != null) {
            subscription = next.subscribe(_ -> guiScheduler.accept(() -> {
                if (controller == next) onChange.run();
            }));
        }
    }

    /** Releases the controller listener when the REPL is shutting down. */
    void close() {
        closeSubscription();
        controller = null;
    }

    private void closeSubscription() {
        AutoCloseable current = subscription;
        subscription = null;
        if (current == null) return;
        try { current.close(); }
        catch (Exception failure) {
            log.debug("Failed to close collaboration footer subscription", failure);
        }
    }
}
